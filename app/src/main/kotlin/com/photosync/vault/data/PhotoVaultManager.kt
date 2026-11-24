package com.photosync.vault.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import com.photosync.vault.R

data class VaultPhoto(
  val name: String,
  val isBundled: Boolean,
  val isSavedToGallery: Boolean,
  val localFile: File? = null,
)

data class RemoteImageEntry(
  val name: String,
  val url: String,
)

data class ExportResult(
  val exportedCount: Int,
  val skippedCount: Int,
  val failedCount: Int,
)

data class SyncResult(
  val downloadedCount: Int,
  val skippedCount: Int,
  val failedCount: Int,
  val totalRemoteCount: Int,
  val deletedCount: Int = 0,
)

class PhotoVaultManager(private val context: Context) {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val syncedDir = File(context.filesDir, SYNCED_DIR_NAME).apply { mkdirs() }

  fun getSyncUrl(): String =
    prefs.getString(KEY_SYNC_URL, null)?.takeIf { it.isNotBlank() }
      ?: context.getString(R.string.default_sync_url)

  fun setSyncUrl(url: String) {
    prefs.edit().putString(KEY_SYNC_URL, url.trim()).apply()
  }

  fun isAutoSyncEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SYNC, true)

  fun setAutoSyncEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
  }

  fun hasNetworkConnection(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  fun listAllPhotos(): List<VaultPhoto> {
    val savedNames = getSavedGalleryNames()
    val bundledNames = listBundledAssetNames()
    val bundledPhotos = bundledNames.map { name ->
      VaultPhoto(
        name = name,
        isBundled = true,
        isSavedToGallery = savedNames.contains(name),
      )
    }
    val bundledSet = bundledNames.toSet()
    val syncedPhotos = syncedDir.listFiles()
      ?.filter { it.isFile && isSupportedImage(it.name) && !bundledSet.contains(it.name) }
      ?.sortedBy { it.name }
      ?.map { file ->
        VaultPhoto(
          name = file.name,
          isBundled = false,
          isSavedToGallery = savedNames.contains(file.name),
          localFile = file,
        )
      }
      .orEmpty()

    return bundledPhotos + syncedPhotos
  }

  suspend fun loadThumbnail(photo: VaultPhoto, maxSizePx: Int = 320): Bitmap? =
    withContext(Dispatchers.IO) {
      runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openPhotoStream(photo)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxSizePx || bounds.outHeight / sampleSize > maxSizePx) {
          sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        openPhotoStream(photo)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
      }.getOrNull()
    }

  suspend fun exportAllToGallery(
    forceReExport: Boolean = false,
    onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
  ): ExportResult = withContext(Dispatchers.IO) {
    val photos = listAllPhotos()
    val savedNames = getSavedGalleryNames().toMutableSet()
    var exported = 0
    var skipped = 0
    var failed = 0

    photos.forEachIndexed { index, photo ->
      onProgress(index + 1, photos.size, photo.name)
      if (!forceReExport && savedNames.contains(photo.name)) {
        skipped++
        return@forEachIndexed
      }
      val ok = runCatching {
        openPhotoStream(photo)?.use { stream ->
          saveStreamToGallery(photo.name, stream)
        } ?: false
      }.getOrDefault(false)

      if (ok) {
        exported++
        savedNames.add(photo.name)
      } else {
        failed++
      }
    }

    prefs.edit().putStringSet(KEY_SAVED_NAMES, savedNames).apply()
    ExportResult(exportedCount = exported, skippedCount = skipped, failedCount = failed)
  }

  suspend fun syncFromUrl(
    manifestUrl: String,
    onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
  ): SyncResult = withContext(Dispatchers.IO) {
    val cleanUrl = manifestUrl.trim()
    require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
      "Đường dẫn phải bắt đầu bằng http:// hoặc https://"
    }
    require(hasNetworkConnection()) {
      "Thiết bị hiện không có kết nối mạng"
    }

    val entries = fetchManifestEntries(cleanUrl)
    val remoteNames = entries.map { it.name }.toSet()
    val savedNames = getSavedGalleryNames().toMutableSet()
    val rawBundledNames = listRawBundledAssetNames()
    val hiddenBundled = rawBundledNames.filterNot { remoteNames.contains(it) }.toSet()
    prefs.edit().putStringSet(KEY_HIDDEN_BUNDLED_NAMES, hiddenBundled).apply()

    var deleted = 0
    syncedDir.listFiles()
      ?.filter { it.isFile && !remoteNames.contains(it.name) }
      ?.forEach { staleFile ->
        val removedFile = staleFile.delete()
        val removedGallery = deleteFromGallery(staleFile.name)
        val removedSaved = savedNames.remove(staleFile.name)
        if (removedFile || removedGallery || removedSaved) {
          deleted++
        }
      }

    hiddenBundled.forEach { staleBundledName ->
      val removedSaved = savedNames.remove(staleBundledName)
      val removedGallery = deleteFromGallery(staleBundledName)
      if (removedSaved || removedGallery) {
        deleted++
      }
    }

    val bundledSet = rawBundledNames.filterNot { hiddenBundled.contains(it) }.toSet()

    var downloaded = 0
    var skipped = 0
    var failed = 0

    entries.forEachIndexed { index, entry ->
      onProgress(index + 1, entries.size, entry.name)
      val targetFile = File(syncedDir, entry.name)
      if ((targetFile.exists() || bundledSet.contains(entry.name)) && savedNames.contains(entry.name)) {
        skipped++
        return@forEachIndexed
      }

      val ok = runCatching {
        if (!targetFile.exists()) {
          downloadFile(entry.url, targetFile)
        }
        if (!savedNames.contains(entry.name)) {
          targetFile.inputStream().use { stream ->
            saveStreamToGallery(entry.name, stream)
          }
        } else {
          true
        }
      }.getOrDefault(false)

      if (ok) {
        downloaded++
        savedNames.add(entry.name)
      } else {
        if (targetFile.exists() && targetFile.length() == 0L) {
          targetFile.delete()
        }
        failed++
      }
    }

    prefs.edit().putStringSet(KEY_SAVED_NAMES, savedNames).apply()
    SyncResult(
      downloadedCount = downloaded,
      skippedCount = skipped,
      failedCount = failed,
      totalRemoteCount = entries.size,
      deletedCount = deleted,
    )
  }

  private fun listRawBundledAssetNames(): List<String> =
    runCatching {
      context.assets.list(ASSETS_PHOTOS_DIR)
        ?.filter { isSupportedImage(it) }
        ?.sorted()
        .orEmpty()
    }.getOrDefault(emptyList())

  private fun listBundledAssetNames(): List<String> {
    val hidden = getHiddenBundledNames()
    return listRawBundledAssetNames().filterNot { hidden.contains(it) }
  }

  private fun getHiddenBundledNames(): Set<String> =
    prefs.getStringSet(KEY_HIDDEN_BUNDLED_NAMES, emptySet()) ?: emptySet()

  private fun openPhotoStream(photo: VaultPhoto): InputStream? =
    if (photo.isBundled) {
      context.assets.open("$ASSETS_PHOTOS_DIR/${photo.name}")
    } else {
      photo.localFile?.takeIf { it.exists() }?.inputStream()
    }

  private fun getSavedGalleryNames(): Set<String> =
    prefs.getStringSet(KEY_SAVED_NAMES, emptySet()) ?: emptySet()

  private fun saveStreamToGallery(fileName: String, input: InputStream): Boolean {
    val mimeType = mimeTypeFor(fileName)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME")
        put(MediaStore.Images.Media.IS_PENDING, 1)
      }
      val resolver = context.contentResolver
      val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
      return try {
        resolver.openOutputStream(uri)?.use { output ->
          input.copyTo(output)
        } ?: return false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
      } catch (e: Exception) {
        resolver.delete(uri, null, null)
        false
      }
    } else {
      @Suppress("DEPRECATION")
      val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
      val albumDir = File(picturesDir, ALBUM_NAME).apply { mkdirs() }
      val destFile = File(albumDir, fileName)
      destFile.outputStream().use { output ->
        input.copyTo(output)
      }
      MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
      return true
    }
  }

  private fun deleteFromGallery(fileName: String): Boolean =
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(fileName, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME%")
        context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, args) > 0
      } else {
        @Suppress("DEPRECATION")
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val target = File(File(picturesDir, ALBUM_NAME), fileName)
        if (target.exists() && target.delete()) {
          MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
          true
        } else {
          false
        }
      }
    }.getOrDefault(false)

  private fun fetchManifestEntries(manifestUrl: String): List<RemoteImageEntry> {
    val conn = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
      connectTimeout = 15_000
      readTimeout = 30_000
      instanceFollowRedirects = true
      requestMethod = "GET"
    }
    try {
      val code = conn.responseCode
      if (code !in 200..299) {
        throw IllegalStateException("Server trả về lỗi HTTP $code")
      }
      val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
      return parseManifestJson(body, manifestUrl)
    } finally {
      conn.disconnect()
    }
  }

  private fun parseManifestJson(rawJson: String, baseUrl: String): List<RemoteImageEntry> {
    val array = if (rawJson.startsWith("[")) {
      JSONArray(rawJson)
    } else {
      val obj = JSONObject(rawJson)
      obj.optJSONArray("images") ?: JSONArray()
    }

    val baseUri = URL(baseUrl)
    val result = mutableListOf<RemoteImageEntry>()
    for (i in 0 until array.length()) {
      when (val item = array.get(i)) {
        is String -> {
          val resolved = URL(baseUri, item.replace(" ", "%20")).toString()
          val rawName = URLDecoder.decode(resolved.substringAfterLast('/').substringBefore('?'), "UTF-8")
          val name = sanitizeFileName(rawName, i)
          result.add(RemoteImageEntry(name = name, url = resolved))
        }
        is JSONObject -> {
          val rawUrl = item.optString("url").ifBlank { item.optString("src") }
          if (rawUrl.isNotBlank()) {
            val resolved = URL(baseUri, rawUrl.replace(" ", "%20")).toString()
            val rawName = item.optString("name").ifBlank {
              URLDecoder.decode(resolved.substringAfterLast('/').substringBefore('?'), "UTF-8")
            }
            result.add(RemoteImageEntry(name = sanitizeFileName(rawName, i), url = resolved))
          }
        }
      }
    }
    return result
  }

  private fun downloadFile(fileUrl: String, destination: File) {
    val conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
      connectTimeout = 15_000
      readTimeout = 30_000
      instanceFollowRedirects = true
      requestMethod = "GET"
    }
    try {
      val code = conn.responseCode
      if (code !in 200..299) {
        throw IllegalStateException("Tải ảnh lỗi HTTP $code")
      }
      conn.inputStream.use { input ->
        destination.outputStream().use { output ->
          input.copyTo(output)
        }
      }
    } finally {
      conn.disconnect()
    }
  }

  private fun sanitizeFileName(name: String, index: Int): String {
    val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return if (clean.isBlank() || !isSupportedImage(clean)) {
      "synced_${index + 1}.jpg"
    } else {
      clean
    }
  }

  private fun isSupportedImage(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
      lower.endsWith(".png") || lower.endsWith(".webp")
  }

  private fun mimeTypeFor(fileName: String): String =
    when {
      fileName.lowercase().endsWith(".png") -> "image/png"
      fileName.lowercase().endsWith(".webp") -> "image/webp"
      else -> "image/jpeg"
    }

  companion object {
    private const val PREFS_NAME = "photo_sync_vault_prefs"
    private const val KEY_SYNC_URL = "sync_url"
    private const val KEY_AUTO_SYNC = "auto_sync"
    private const val KEY_SAVED_NAMES = "saved_gallery_names"
    private const val KEY_HIDDEN_BUNDLED_NAMES = "hidden_bundled_names"
    private const val ASSETS_PHOTOS_DIR = "photos"
    private const val SYNCED_DIR_NAME = "synced_photos"
    const val ALBUM_NAME = "PhotoSyncVault"
  }
}

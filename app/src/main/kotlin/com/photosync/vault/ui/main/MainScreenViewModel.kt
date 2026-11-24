package com.photosync.vault.ui.main

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photosync.vault.data.PhotoVaultManager
import com.photosync.vault.data.VaultPhoto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotoVaultUiState(
  val photos: List<VaultPhoto> = emptyList(),
  val syncUrl: String = "",
  val autoSyncEnabled: Boolean = true,
  val isBusy: Boolean = false,
  val progressCurrent: Int = 0,
  val progressTotal: Int = 0,
  val progressLabel: String = "",
  val statusMessage: String? = null,
  val isError: Boolean = false,
) {
  val bundledCount: Int get() = photos.count { it.isBundled }
  val syncedCount: Int get() = photos.count { !it.isBundled }
  val savedCount: Int get() = photos.count { it.isSavedToGallery }
}

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
  private val manager = PhotoVaultManager(application.applicationContext)

  private val _uiState = MutableStateFlow(
    PhotoVaultUiState(
      photos = manager.listAllPhotos(),
      syncUrl = manager.getSyncUrl(),
      autoSyncEnabled = manager.isAutoSyncEnabled(),
    )
  )
  val uiState: StateFlow<PhotoVaultUiState> = _uiState.asStateFlow()

  init {
    val initialUrl = manager.getSyncUrl()
    if (manager.isAutoSyncEnabled() && initialUrl.isNotBlank() && manager.hasNetworkConnection()) {
      syncNow()
    }
  }

  fun refreshPhotos() {
    _uiState.update { it.copy(photos = manager.listAllPhotos()) }
  }

  fun updateSyncUrl(url: String) {
    manager.setSyncUrl(url)
    _uiState.update { it.copy(syncUrl = url) }
  }

  fun toggleAutoSync(enabled: Boolean) {
    manager.setAutoSyncEnabled(enabled)
    _uiState.update { it.copy(autoSyncEnabled = enabled) }
  }

  fun exportAllToGallery(forceReExport: Boolean = false) {
    if (_uiState.value.isBusy) return
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isBusy = true,
          progressCurrent = 0,
          progressTotal = it.photos.size,
          progressLabel = "Đang chuẩn bị lưu ảnh...",
          statusMessage = null,
          isError = false,
        )
      }
      val result = manager.exportAllToGallery(forceReExport = forceReExport) { current, total, name ->
        _uiState.update {
          it.copy(
            progressCurrent = current,
            progressTotal = total,
            progressLabel = "Đang lưu ($current/$total): $name",
          )
        }
      }
      val msg = buildString {
        append("Đã lưu ${result.exportedCount} ảnh mới vào Pictures/${PhotoVaultManager.ALBUM_NAME}")
        if (result.skippedCount > 0) append(" • Bỏ qua ${result.skippedCount} ảnh đã có")
        if (result.failedCount > 0) append(" • Lỗi ${result.failedCount} ảnh")
      }
      _uiState.update {
        it.copy(
          photos = manager.listAllPhotos(),
          isBusy = false,
          progressLabel = "",
          statusMessage = msg,
          isError = result.failedCount > 0 && result.exportedCount == 0,
        )
      }
    }
  }

  fun syncNow() {
    if (_uiState.value.isBusy) return
    val url = _uiState.value.syncUrl.trim()
    if (url.isBlank()) {
      _uiState.update {
        it.copy(statusMessage = "Vui lòng nhập đường dẫn manifest.json trước khi đồng bộ.", isError = true)
      }
      return
    }
    manager.setSyncUrl(url)

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isBusy = true,
          progressCurrent = 0,
          progressTotal = 0,
          progressLabel = "Đang kết nối tới đường dẫn đồng bộ...",
          statusMessage = null,
          isError = false,
        )
      }
      try {
        val result = manager.syncFromUrl(url) { current, total, name ->
          _uiState.update {
            it.copy(
              progressCurrent = current,
              progressTotal = total,
              progressLabel = "Đang đồng bộ ($current/$total): $name",
            )
          }
        }
        val msg = buildString {
          append("Đồng bộ xong: tải mới ${result.downloadedCount} ảnh")
          if (result.deletedCount > 0) append(" • Đã xóa ${result.deletedCount} ảnh cũ")
          if (result.skippedCount > 0) append(" • Đã có ${result.skippedCount} ảnh")
          if (result.failedCount > 0) append(" • Lỗi ${result.failedCount} ảnh")
        }
        _uiState.update {
          it.copy(
            photos = manager.listAllPhotos(),
            isBusy = false,
            progressLabel = "",
            statusMessage = msg,
            isError = result.failedCount > 0 && result.downloadedCount == 0 && result.deletedCount == 0,
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            isBusy = false,
            progressLabel = "",
            statusMessage = "Lỗi đồng bộ: ${e.message ?: "Không thể tải từ đường dẫn"}",
            isError = true,
          )
        }
      }
    }
  }

  suspend fun loadThumbnail(photo: VaultPhoto): Bitmap? = manager.loadThumbnail(photo)
}

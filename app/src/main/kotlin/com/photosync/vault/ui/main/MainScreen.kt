package com.photosync.vault.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.photosync.vault.R
import com.photosync.vault.data.PhotoVaultManager
import com.photosync.vault.data.VaultPhoto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      viewModel.exportAllToGallery(forceReExport = false)
    }
  }

  fun triggerExport(forceReExport: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) {
        legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return
      }
    }
    viewModel.exportAllToGallery(forceReExport = forceReExport)
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
      )
    },
  ) { innerPadding ->
    LazyVerticalGrid(
      columns = GridCells.Fixed(2),
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item(span = { GridItemSpan(2) }) {
        SummaryCard(
          uiState = uiState,
          onDownloadAll = { triggerExport(forceReExport = false) },
          onReExportAll = { triggerExport(forceReExport = true) },
        )
      }

      item(span = { GridItemSpan(2) }) {
        OnlineSyncCard(
          uiState = uiState,
          onUrlChange = viewModel::updateSyncUrl,
          onToggleAutoSync = viewModel::toggleAutoSync,
          onSyncNow = viewModel::syncNow,
        )
      }

      if (uiState.isBusy) {
        item(span = { GridItemSpan(2) }) {
          Card(
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text(
                text = uiState.progressLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
              )
              Spacer(modifier = Modifier.height(8.dp))
              if (uiState.progressTotal > 0) {
                LinearProgressIndicator(
                  progress = { uiState.progressCurrent.toFloat() / uiState.progressTotal.toFloat() },
                  modifier = Modifier.fillMaxWidth(),
                )
              } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              }
            }
          }
        }
      }

      uiState.statusMessage?.let { message ->
        item(span = { GridItemSpan(2) }) {
          Card(
            colors = CardDefaults.cardColors(
              containerColor = if (uiState.isError) {
                MaterialTheme.colorScheme.errorContainer
              } else {
                MaterialTheme.colorScheme.tertiaryContainer
              },
            ),
          ) {
            Text(
              text = message,
              modifier = Modifier.padding(14.dp),
              style = MaterialTheme.typography.bodyMedium,
              color = if (uiState.isError) {
                MaterialTheme.colorScheme.onErrorContainer
              } else {
                MaterialTheme.colorScheme.onTertiaryContainer
              },
            )
          }
        }
      }

      item(span = { GridItemSpan(2) }) {
        Text(
          text = "Danh sách ảnh (${uiState.photos.size})",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      items(uiState.photos, key = { "${it.isBundled}_${it.name}" }) { photo ->
        PhotoGridItem(
          photo = photo,
          loadThumbnail = { viewModel.loadThumbnail(photo) },
        )
      }
    }
  }
}

@Composable
private fun SummaryCard(
  uiState: PhotoVaultUiState,
  onDownloadAll: () -> Unit,
  onReExportAll: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = "Kho ảnh Offline & Thư viện",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "Có sẵn trong APK: ${uiState.bundledCount} ảnh • Đồng bộ thêm: ${uiState.syncedCount} ảnh\n" +
          "Đã lưu vào Pictures/${PhotoVaultManager.ALBUM_NAME}: ${uiState.savedCount}/${uiState.photos.size}",
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(modifier = Modifier.height(12.dp))
      Button(
        onClick = onDownloadAll,
        enabled = !uiState.isBusy && uiState.photos.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Tải toàn bộ ảnh vào Thư viện")
      }
      if (uiState.savedCount > 0) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
          onClick = onReExportAll,
          enabled = !uiState.isBusy && uiState.photos.isNotEmpty(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Lưu lại toàn bộ (ghi bản mới)")
        }
      }
    }
  }
}

@Composable
private fun OnlineSyncCard(
  uiState: PhotoVaultUiState,
  onUrlChange: (String) -> Unit,
  onToggleAutoSync: (Boolean) -> Unit,
  onSyncNow: () -> Unit,
) {
  Card {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = "Đồng bộ ảnh từ đường dẫn",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Nhập link file manifest.json (ví dụ trên GitHub Pages hoặc web server) để tải thêm ảnh mới khi có mạng.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(10.dp))
      OutlinedTextField(
        value = uiState.syncUrl,
        onValueChange = onUrlChange,
        label = { Text("URL manifest.json") },
        placeholder = { Text("https://example.com/photos/manifest.json") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "Tự đồng bộ khi mở app",
          style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
          checked = uiState.autoSyncEnabled,
          onCheckedChange = onToggleAutoSync,
        )
      }
      Spacer(modifier = Modifier.height(8.dp))
      Button(
        onClick = onSyncNow,
        enabled = !uiState.isBusy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Đồng bộ ngay")
      }
    }
  }
}

@Composable
private fun PhotoGridItem(
  photo: VaultPhoto,
  loadThumbnail: suspend () -> Bitmap?,
) {
  val bitmap by produceState<Bitmap?>(initialValue = null, key1 = photo.name, key2 = photo.isBundled) {
    value = loadThumbnail()
  }

  Card(shape = RoundedCornerShape(12.dp)) {
    Column {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(1f)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
          Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = photo.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .fillMaxSize()
              .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
          )
        } else {
          Text("...", style = MaterialTheme.typography.bodyMedium)
        }

        Surface(
          color = if (photo.isSavedToGallery) Color(0xCC2E7D32) else Color(0xCC455A64),
          shape = RoundedCornerShape(6.dp),
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp),
        ) {
          Text(
            text = if (photo.isSavedToGallery) "Đã lưu" else if (photo.isBundled) "Trong APK" else "Đã sync",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }

      Text(
        text = photo.name,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
      )
    }
  }
}

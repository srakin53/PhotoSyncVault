package com.photosync.vault.ui.main

import com.photosync.vault.data.VaultPhoto
import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun uiStateCountsBundledSyncedAndSavedPhotos() {
    val state = PhotoVaultUiState(
      photos = listOf(
        VaultPhoto(name = "a.png", isBundled = true, isSavedToGallery = true),
        VaultPhoto(name = "b.png", isBundled = true, isSavedToGallery = false),
        VaultPhoto(name = "c.jpg", isBundled = false, isSavedToGallery = true),
      )
    )
    assertEquals(2, state.bundledCount)
    assertEquals(1, state.syncedCount)
    assertEquals(2, state.savedCount)
  }
}

package io.github.trevortherrien.unbarred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Gives the UI the [SoundbarController] across configuration changes (rotation). The controller
 * itself lives in [UnbarredApp] so [BarControlService] can share it while the app is in the
 * background. When the Activity is finished for good (back out of the app), the link is dropped,
 * which also takes down the notification.
 */
class SoundbarViewModel(val controller: SoundbarController) : ViewModel() {
    override fun onCleared() {
        controller.disconnect()
        super.onCleared()
    }
}

/** Injects the chosen controller implementation (real BLE in the app, Fake in tests). */
class SoundbarViewModelFactory(
    private val controller: SoundbarController
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SoundbarViewModel(controller) as T
}

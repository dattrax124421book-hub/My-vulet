package com.example.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.UserPreferences
import com.example.data.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UserPreferencesRepository(application)

    val userPreferences: StateFlow<UserPreferences?> = repository.userPreferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun updateTheme(theme: String) = viewModelScope.launch { repository.updateTheme(theme) }
    fun updateVaultLockTimeout(timeout: Long) = viewModelScope.launch { repository.updateVaultLockTimeout(timeout) }
    fun updateBiometricUnlock(enabled: Boolean) = viewModelScope.launch { repository.updateBiometricUnlock(enabled) }
    fun updateEditorFontSize(size: Float) = viewModelScope.launch { repository.updateEditorFontSize(size) }
    fun updateEditorFontFamily(family: String) = viewModelScope.launch { repository.updateEditorFontFamily(family) }
    fun updateEditorTabSize(size: Int) = viewModelScope.launch { repository.updateEditorTabSize(size) }
    fun updateEditorUseSpaces(useSpaces: Boolean) = viewModelScope.launch { repository.updateEditorUseSpaces(useSpaces) }
    fun updateEditorWordWrap(wordWrap: Boolean) = viewModelScope.launch { repository.updateEditorWordWrap(wordWrap) }
    fun updateEditorAutosave(autosave: Boolean) = viewModelScope.launch { repository.updateEditorAutosave(autosave) }
    fun updateEditorAutosaveInterval(interval: Long) = viewModelScope.launch { repository.updateEditorAutosaveInterval(interval) }
    fun updateEditorDiagnosticsAuto(auto: Boolean) = viewModelScope.launch { repository.updateEditorDiagnosticsAuto(auto) }
    fun updateDefaultExportUri(uri: String) = viewModelScope.launch { repository.updateDefaultExportUri(uri) }
    fun updateConfirmDestructive(confirm: Boolean) = viewModelScope.launch { repository.updateConfirmDestructive(confirm) }
}

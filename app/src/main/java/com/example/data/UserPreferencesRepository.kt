package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UserPreferencesRepository(private val context: Context) {

    companion object {
        val THEME = stringPreferencesKey("theme") // SYSTEM, LIGHT, DARK
        val VAULT_LOCK_TIMEOUT = longPreferencesKey("vault_lock_timeout") // ms, -1 for Never
        val BIOMETRIC_UNLOCK = booleanPreferencesKey("biometric_unlock")
        val EDITOR_FONT_SIZE = floatPreferencesKey("editor_font_size") // 10f - 24f
        val EDITOR_FONT_FAMILY = stringPreferencesKey("editor_font_family")
        val EDITOR_TAB_SIZE = intPreferencesKey("editor_tab_size")
        val EDITOR_USE_SPACES = booleanPreferencesKey("editor_use_spaces")
        val EDITOR_WORD_WRAP = booleanPreferencesKey("editor_word_wrap")
        val EDITOR_AUTOSAVE = booleanPreferencesKey("editor_autosave")
        val EDITOR_AUTOSAVE_INTERVAL = longPreferencesKey("editor_autosave_interval") // ms
        val EDITOR_DIAGNOSTICS_AUTO = booleanPreferencesKey("editor_diagnostics_auto")
        val DEFAULT_EXPORT_URI = stringPreferencesKey("default_export_uri")
        val CONFIRM_DESTRUCTIVE = booleanPreferencesKey("confirm_destructive")
    }

    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { preferences ->
        UserPreferences(
            theme = preferences[THEME] ?: "SYSTEM",
            vaultLockTimeout = preferences[VAULT_LOCK_TIMEOUT] ?: 0L,
            biometricUnlock = preferences[BIOMETRIC_UNLOCK] ?: true,
            editorFontSize = preferences[EDITOR_FONT_SIZE] ?: 14f,
            editorFontFamily = preferences[EDITOR_FONT_FAMILY] ?: "MONOSPACE",
            editorTabSize = preferences[EDITOR_TAB_SIZE] ?: 4,
            editorUseSpaces = preferences[EDITOR_USE_SPACES] ?: true,
            editorWordWrap = preferences[EDITOR_WORD_WRAP] ?: false,
            editorAutosave = preferences[EDITOR_AUTOSAVE] ?: false,
            editorAutosaveInterval = preferences[EDITOR_AUTOSAVE_INTERVAL] ?: 15000L,
            editorDiagnosticsAuto = preferences[EDITOR_DIAGNOSTICS_AUTO] ?: true,
            defaultExportUri = preferences[DEFAULT_EXPORT_URI] ?: "",
            confirmDestructive = preferences[CONFIRM_DESTRUCTIVE] ?: true
        )
    }

    suspend fun updateTheme(theme: String) {
        context.dataStore.edit { it[THEME] = theme }
    }

    suspend fun updateVaultLockTimeout(timeout: Long) {
        context.dataStore.edit { it[VAULT_LOCK_TIMEOUT] = timeout }
    }

    suspend fun updateBiometricUnlock(enabled: Boolean) {
        context.dataStore.edit { it[BIOMETRIC_UNLOCK] = enabled }
    }

    suspend fun updateEditorFontSize(size: Float) {
        context.dataStore.edit { it[EDITOR_FONT_SIZE] = size }
    }

    suspend fun updateEditorFontFamily(family: String) {
        context.dataStore.edit { it[EDITOR_FONT_FAMILY] = family }
    }

    suspend fun updateEditorTabSize(size: Int) {
        context.dataStore.edit { it[EDITOR_TAB_SIZE] = size }
    }

    suspend fun updateEditorUseSpaces(useSpaces: Boolean) {
        context.dataStore.edit { it[EDITOR_USE_SPACES] = useSpaces }
    }

    suspend fun updateEditorWordWrap(wordWrap: Boolean) {
        context.dataStore.edit { it[EDITOR_WORD_WRAP] = wordWrap }
    }

    suspend fun updateEditorAutosave(autosave: Boolean) {
        context.dataStore.edit { it[EDITOR_AUTOSAVE] = autosave }
    }

    suspend fun updateEditorAutosaveInterval(interval: Long) {
        context.dataStore.edit { it[EDITOR_AUTOSAVE_INTERVAL] = interval }
    }

    suspend fun updateEditorDiagnosticsAuto(auto: Boolean) {
        context.dataStore.edit { it[EDITOR_DIAGNOSTICS_AUTO] = auto }
    }

    suspend fun updateDefaultExportUri(uri: String) {
        context.dataStore.edit { it[DEFAULT_EXPORT_URI] = uri }
    }

    suspend fun updateConfirmDestructive(confirm: Boolean) {
        context.dataStore.edit { it[CONFIRM_DESTRUCTIVE] = confirm }
    }
}

data class UserPreferences(
    val theme: String,
    val vaultLockTimeout: Long,
    val biometricUnlock: Boolean,
    val editorFontSize: Float,
    val editorFontFamily: String,
    val editorTabSize: Int,
    val editorUseSpaces: Boolean,
    val editorWordWrap: Boolean,
    val editorAutosave: Boolean,
    val editorAutosaveInterval: Long,
    val editorDiagnosticsAuto: Boolean,
    val defaultExportUri: String,
    val confirmDestructive: Boolean
)

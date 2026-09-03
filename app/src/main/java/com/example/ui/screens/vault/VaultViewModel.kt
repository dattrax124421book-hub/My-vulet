package com.example.ui.screens.vault

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.VaultItem
import com.example.security.SecurityAuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val securityAuthManager = SecurityAuthManager(application)

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _hasPin = MutableStateFlow(securityAuthManager.hasPin())
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()

    private val dao = AppDatabase.getDatabase(application).vaultItemDao()
    val vaultItems = dao.getAllVaultItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val statePrefs = application.getSharedPreferences("vault_state", Context.MODE_PRIVATE)

    fun checkForceLock() {
        if (statePrefs.getBoolean("force_lock", false)) {
            lock()
            statePrefs.edit().putBoolean("force_lock", false).apply()
        }
    }

    fun setPin(pin: String) {
        securityAuthManager.setPin(pin)
        _hasPin.value = true
        _isUnlocked.value = true
    }

    fun unlock(pin: String): Boolean {
        if (securityAuthManager.verifyPin(pin)) {
            _isUnlocked.value = true
            return true
        }
        return false
    }

    fun changePin(oldPin: String, newPin: String): Boolean {
        val success = securityAuthManager.changePin(oldPin, newPin)
        if (success) {
            _hasPin.value = true
            _isUnlocked.value = true
        }
        return success
    }

    fun resetVault() {
        securityAuthManager.clearPin()
        _hasPin.value = false
        _isUnlocked.value = false
        viewModelScope.launch {
            vaultItems.value.forEach { item ->
                try {
                    File(item.encryptedPath).delete()
                } catch (e: Exception) {
                    // ignore
                }
                dao.delete(item)
            }
        }
    }

    fun unlockWithBiometrics() {
        _isUnlocked.value = true
    }

    fun lock() {
        _isUnlocked.value = false
    }

    fun addVaultItem(item: VaultItem) {
        viewModelScope.launch { dao.insert(item) }
    }

    fun deleteVaultItem(item: VaultItem) {
        viewModelScope.launch {
            try {
                File(item.encryptedPath).delete()
            } catch (e: Exception) {
                // ignore
            }
            dao.delete(item)
        }
    }
}


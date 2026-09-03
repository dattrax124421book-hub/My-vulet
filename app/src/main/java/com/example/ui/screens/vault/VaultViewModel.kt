package com.example.ui.screens.vault

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.AppDatabase
import com.example.data.VaultItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64

class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _hasPin = MutableStateFlow(false)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()

    private val dao = AppDatabase.getDatabase(application).vaultItemDao()
    val vaultItems = dao.getAllVaultItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val sharedPrefs by lazy {
        val masterKey = MasterKey.Builder(application)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            application,
            "vault_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val statePrefs = application.getSharedPreferences("vault_state", Context.MODE_PRIVATE)

    init {
        _hasPin.value = sharedPrefs.contains("vault_pin_hash")
    }

    fun checkForceLock() {
        if (statePrefs.getBoolean("force_lock", false)) {
            lock()
            statePrefs.edit().putBoolean("force_lock", false).apply()
        }
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = hashPin(pin, salt)
        
        sharedPrefs.edit()
            .putString("vault_pin_hash", Base64.encodeToString(hash, Base64.DEFAULT))
            .putString("vault_pin_salt", Base64.encodeToString(salt, Base64.DEFAULT))
            .apply()
            
        _hasPin.value = true
        _isUnlocked.value = true
    }

    fun unlock(pin: String): Boolean {
        val storedHashBase64 = sharedPrefs.getString("vault_pin_hash", null)
        val storedSaltBase64 = sharedPrefs.getString("vault_pin_salt", null)
        
        if (storedHashBase64 != null && storedSaltBase64 != null) {
            val storedHash = Base64.decode(storedHashBase64, Base64.DEFAULT)
            val salt = Base64.decode(storedSaltBase64, Base64.DEFAULT)
            
            val attemptedHash = hashPin(pin, salt)
            
            if (attemptedHash.contentEquals(storedHash)) {
                _isUnlocked.value = true
                return true
            }
        }
        return false
    }
    
    fun changePin(oldPin: String, newPin: String): Boolean {
        if (unlock(oldPin)) {
            setPin(newPin)
            return true
        }
        return false
    }
    
    fun resetVault() {
        sharedPrefs.edit().clear().apply()
        _hasPin.value = false
        _isUnlocked.value = false
        // In a real scenario we'd also clear the vault database and encrypted files
        viewModelScope.launch {
            vaultItems.value.forEach { dao.delete(it) }
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
        viewModelScope.launch { dao.delete(item) }
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}

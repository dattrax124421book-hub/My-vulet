package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreHelper {
    private val keyAlias = "VaultKeyAlias"
    private val androidKeyStore = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"

    init {
        val keyStore = KeyStore.getInstance(androidKeyStore)
        keyStore.load(null)
        if (!keyStore.containsAlias(keyAlias)) {
            generateKey()
        }
    }

    private fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, androidKeyStore)
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(keyGenParameterSpec)
        keyGenerator.generateKey()
    }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance(androidKeyStore)
        keyStore.load(null)
        return keyStore.getKey(keyAlias, null) as SecretKey
    }

    fun encrypt(data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val iv = cipher.iv
        val encryptedData = cipher.doFinal(data)
        return Pair(iv, encryptedData)
    }

    fun decrypt(iv: ByteArray, encryptedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(transformation)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getKey(), spec)
        return cipher.doFinal(encryptedData)
    }

    fun encryptStream(inputStream: InputStream, outputStream: OutputStream) {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val iv = cipher.iv
        outputStream.write(iv)
        
        CipherOutputStream(outputStream, cipher).use { cos ->
            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                cos.write(buffer, 0, bytesRead)
            }
            cos.flush()
        }
    }

    fun decryptStream(inputStream: InputStream, outputStream: OutputStream) {
        val iv = ByteArray(12)
        var totalRead = 0
        while (totalRead < 12) {
            val r = inputStream.read(iv, totalRead, 12 - totalRead)
            if (r == -1) throw IllegalArgumentException("Corrupt encrypted file: header too short")
            totalRead += r
        }

        val cipher = Cipher.getInstance(transformation)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getKey(), spec)

        CipherInputStream(inputStream, cipher).use { cis ->
            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            while (cis.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.flush()
        }
    }
}

package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_items")
data class VaultItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val encryptedFilename: String,
    val encryptedPath: String,
    val fileSize: Long = 0L,
    val mimeType: String = "*/*",
    val originalPath: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

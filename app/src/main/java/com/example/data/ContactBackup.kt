package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_backups")
data class ContactBackup(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phoneNumber: String,
    val encryptedData: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactBackupDao {
    @Query("SELECT * FROM contact_backups ORDER BY timestamp DESC")
    fun getAllBackups(): Flow<List<ContactBackup>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(backup: ContactBackup)

    @Delete
    suspend fun delete(backup: ContactBackup)
}

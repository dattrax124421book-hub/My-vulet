package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultItemDao {
    @Query("SELECT * FROM vault_items ORDER BY timestamp DESC")
    fun getAllVaultItems(): Flow<List<VaultItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vaultItem: VaultItem)

    @Delete
    suspend fun delete(vaultItem: VaultItem)
}

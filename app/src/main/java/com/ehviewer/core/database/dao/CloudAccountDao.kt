package com.ehviewer.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ehviewer.core.database.model.CloudAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudAccountDao {
    @Query("SELECT * FROM CLOUD_ACCOUNTS WHERE PAN_TYPE = :panType ORDER BY ADDED_AT ASC")
    fun listFlowByPanType(panType: String): Flow<List<CloudAccountEntity>>

    @Query("SELECT * FROM CLOUD_ACCOUNTS WHERE ID = :id")
    suspend fun load(id: Long): CloudAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CloudAccountEntity): Long

    @Update
    suspend fun update(entity: CloudAccountEntity)

    @Delete
    suspend fun delete(entity: CloudAccountEntity)

    @Query("UPDATE CLOUD_ACCOUNTS SET IS_ACTIVE = 0 WHERE PAN_TYPE = :panType")
    suspend fun clearActiveByPanType(panType: String)

    @Query("UPDATE CLOUD_ACCOUNTS SET IS_ACTIVE = 1 WHERE ID = :id")
    suspend fun setActiveById(id: Long)

    @Transaction
    suspend fun setActive(panType: String, id: Long) {
        clearActiveByPanType(panType)
        setActiveById(id)
    }

    @Query("SELECT COUNT(*) FROM CLOUD_ACCOUNTS WHERE PAN_TYPE = :panType")
    suspend fun countByPanType(panType: String): Int

    @Query("SELECT * FROM CLOUD_ACCOUNTS WHERE PAN_TYPE = :panType AND IS_ACTIVE = 1 LIMIT 1")
    suspend fun getActiveByPanType(panType: String): CloudAccountEntity?
}
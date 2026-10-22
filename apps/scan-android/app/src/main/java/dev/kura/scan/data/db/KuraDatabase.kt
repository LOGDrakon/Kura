package dev.kura.scan.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Opération de la file offline. [state] : `PENDING` ou `ERROR`. */
@Entity(tableName = "queued_ops")
data class QueuedOpEntity(
    @PrimaryKey val opId: String,
    val itemId: String,
    val type: String,
    val value: Long,
    val clientTs: String,
    val deviceId: String?,
    val user: String?,
    val sessionId: String?,
    /** Champs scannés (JSON), variables supplémentaires de `request_mapping`. */
    val extrasJson: String,
    /** Libellé affiché dans la file (désignation si connue). */
    val label: String?,
    val state: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val errorReason: String?,
    val lastMessage: String?,
    val createdAt: Long,
)

/** Dernière fiche connue d'un article (affichage hors-ligne). */
@Entity(tableName = "item_cache")
data class ItemCacheEntity(
    @PrimaryKey val ref: String,
    val json: String,
    val fetchedAt: Long,
)

@Dao
interface QueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: QueuedOpEntity)

    @Query("SELECT * FROM queued_ops WHERE state = 'PENDING' AND nextAttemptAt <= :now ORDER BY createdAt, opId LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<QueuedOpEntity>

    @Query("SELECT * FROM queued_ops WHERE opId = :opId")
    suspend fun get(opId: String): QueuedOpEntity?

    @Query("DELETE FROM queued_ops WHERE opId = :opId")
    suspend fun delete(opId: String): Int

    @Query("UPDATE queued_ops SET state = 'ERROR', errorReason = :reason, lastMessage = :message WHERE opId = :opId")
    suspend fun markError(opId: String, reason: String, message: String?)

    @Query("UPDATE queued_ops SET attempts = :attempts, nextAttemptAt = :next, lastMessage = :message WHERE opId = :opId")
    suspend fun scheduleRetry(opId: String, attempts: Int, next: Long, message: String?)

    @Query("UPDATE queued_ops SET state = 'PENDING', nextAttemptAt = :now, errorReason = NULL, lastMessage = NULL WHERE opId = :opId")
    suspend fun requeue(opId: String, now: Long)

    /** Envoi manuel : toutes les opérations en attente deviennent dues tout de suite. */
    @Query("UPDATE queued_ops SET nextAttemptAt = :now WHERE state = 'PENDING'")
    suspend fun makeAllDue(now: Long)

    @Query("SELECT * FROM queued_ops ORDER BY createdAt DESC LIMIT 500")
    fun observeAll(): Flow<List<QueuedOpEntity>>

    @Query("SELECT COUNT(*) FROM queued_ops")
    fun observeCount(): Flow<Int>
}

@Dao
interface ItemCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: ItemCacheEntity)

    @Query("SELECT * FROM item_cache WHERE ref = :ref")
    suspend fun get(ref: String): ItemCacheEntity?

    /** Garde le cache borné (téléphones à faible stockage). */
    @Query("DELETE FROM item_cache WHERE ref NOT IN (SELECT ref FROM item_cache ORDER BY fetchedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Database(entities = [QueuedOpEntity::class, ItemCacheEntity::class], version = 1, exportSchema = true)
abstract class KuraDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao
    abstract fun itemCacheDao(): ItemCacheDao

    companion object {
        fun build(context: Context): KuraDatabase =
            Room.databaseBuilder(context, KuraDatabase::class.java, "kura.db").build()
    }
}

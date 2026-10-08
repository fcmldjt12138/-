package com.example.childpoints

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 任务状态常量，避免把状态字符串散落在各个页面。 */
object TaskStatus {
    const val WAITING = "WAITING"      // 待领取
    const val DOING = "DOING"          // 进行中
    const val REVIEW = "REVIEW"        // 待审核
    const val APPROVED = "APPROVED"    // 已通过
    const val REJECTED = "REJECTED"    // 已驳回
}

object RedemptionStatus {
    const val PENDING = "PENDING"      // 待审核
    const val APPROVED = "APPROVED"    // 已通过
    const val REJECTED = "REJECTED"    // 已驳回
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val rewardPoints: Int,
    val deadlineAt: Long,
    val status: String,
    val childNote: String?,
    val rejectReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val claimedAt: Long?,
    val submittedAt: Long?,
    val reviewedAt: Long?
)

@Entity(tableName = "shop_items")
data class ShopItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val costPoints: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "redemptions")
data class RedemptionEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val itemName: String,
    val costPoints: Int,
    val status: String,
    val note: String?,
    val rejectReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val reviewedAt: Long?
)

/** 钱包只有一条记录，id 固定为 1。 */
@Entity(tableName = "wallet")
data class WalletEntity(
    @PrimaryKey val id: Int = 1,
    val points: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TaskEntity>)
}

@Dao
interface ShopDao {
    @Query("SELECT * FROM shop_items WHERE enabled = 1 ORDER BY updatedAt DESC")
    fun observeEnabled(): Flow<List<ShopItemEntity>>

    @Query("SELECT * FROM shop_items ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ShopItemEntity>>

    @Query("SELECT * FROM shop_items ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ShopItemEntity>

    @Query("SELECT * FROM shop_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ShopItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ShopItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ShopItemEntity>)
}

@Dao
interface RedemptionDao {
    @Query("SELECT * FROM redemptions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<RedemptionEntity>>

    @Query("SELECT * FROM redemptions ORDER BY updatedAt DESC")
    suspend fun getAll(): List<RedemptionEntity>

    @Query("SELECT * FROM redemptions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RedemptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RedemptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RedemptionEntity>)
}

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet WHERE id = 1 LIMIT 1")
    fun observe(): Flow<WalletEntity?>

    @Query("SELECT * FROM wallet WHERE id = 1 LIMIT 1")
    suspend fun get(): WalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WalletEntity)
}

@Database(
    entities = [TaskEntity::class, ShopItemEntity::class, RedemptionEntity::class, WalletEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun shopDao(): ShopDao
    abstract fun redemptionDao(): RedemptionDao
    abstract fun walletDao(): WalletDao
}

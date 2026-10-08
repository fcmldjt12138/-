package com.example.childpoints

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** 网络同步的数据快照。 */
data class DataSnapshot(
    val tasks: List<TaskEntity>,
    val shopItems: List<ShopItemEntity>,
    val redemptions: List<RedemptionEntity>,
    val wallet: WalletEntity
)

/** 使用 Android 自带 org.json，避免引入额外网络/序列化库。 */
object SnapshotCodec {
    private const val TYPE = "SNAPSHOT"

    fun encode(snapshot: DataSnapshot): String {
        val root = JSONObject().put("type", TYPE)
        val tasks = JSONArray()
        snapshot.tasks.forEach {
            tasks.put(JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("description", it.description)
                .put("rewardPoints", it.rewardPoints)
                .put("deadlineAt", it.deadlineAt)
                .put("status", it.status)
                .put("childNote", it.childNote)
                .put("rejectReason", it.rejectReason)
                .put("createdAt", it.createdAt)
                .put("updatedAt", it.updatedAt)
                .put("claimedAt", it.claimedAt)
                .put("submittedAt", it.submittedAt)
                .put("reviewedAt", it.reviewedAt))
        }
        val shops = JSONArray()
        snapshot.shopItems.forEach {
            shops.put(JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("description", it.description)
                .put("costPoints", it.costPoints)
                .put("enabled", it.enabled)
                .put("createdAt", it.createdAt)
                .put("updatedAt", it.updatedAt))
        }
        val redemptions = JSONArray()
        snapshot.redemptions.forEach {
            redemptions.put(JSONObject()
                .put("id", it.id)
                .put("itemId", it.itemId)
                .put("itemName", it.itemName)
                .put("costPoints", it.costPoints)
                .put("status", it.status)
                .put("note", it.note)
                .put("rejectReason", it.rejectReason)
                .put("createdAt", it.createdAt)
                .put("updatedAt", it.updatedAt)
                .put("reviewedAt", it.reviewedAt))
        }
        val wallet = JSONObject()
            .put("id", 1)
            .put("points", snapshot.wallet.points)
            .put("createdAt", snapshot.wallet.createdAt)
            .put("updatedAt", snapshot.wallet.updatedAt)

        return root.put("tasks", tasks)
            .put("shopItems", shops)
            .put("redemptions", redemptions)
            .put("wallet", wallet)
            .toString()
    }

    fun decode(line: String): DataSnapshot? = runCatching {
        val root = JSONObject(line)
        if (root.optString("type") != TYPE) return null

        val tasks = buildList {
            val a = root.optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(TaskEntity(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    description = o.optString("description"),
                    rewardPoints = o.optInt("rewardPoints"),
                    deadlineAt = o.optLong("deadlineAt"),
                    status = o.optString("status"),
                    childNote = o.optNullableString("childNote"),
                    rejectReason = o.optNullableString("rejectReason"),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                    claimedAt = o.optNullableLong("claimedAt"),
                    submittedAt = o.optNullableLong("submittedAt"),
                    reviewedAt = o.optNullableLong("reviewedAt")
                ))
            }
        }
        val shops = buildList {
            val a = root.optJSONArray("shopItems") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(ShopItemEntity(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    description = o.optString("description"),
                    costPoints = o.optInt("costPoints"),
                    enabled = o.optBoolean("enabled", true),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt")
                ))
            }
        }
        val redemptions = buildList {
            val a = root.optJSONArray("redemptions") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(RedemptionEntity(
                    id = o.getString("id"),
                    itemId = o.getString("itemId"),
                    itemName = o.getString("itemName"),
                    costPoints = o.optInt("costPoints"),
                    status = o.optString("status"),
                    note = o.optNullableString("note"),
                    rejectReason = o.optNullableString("rejectReason"),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                    reviewedAt = o.optNullableLong("reviewedAt")
                ))
            }
        }
        val o = root.optJSONObject("wallet")
        val wallet = WalletEntity(
            id = 1,
            points = o?.optInt("points", 0) ?: 0,
            createdAt = o?.optLong("createdAt", 0L) ?: 0L,
            updatedAt = o?.optLong("updatedAt", 0L) ?: 0L
        )
        DataSnapshot(tasks, shops, redemptions, wallet)
    }.getOrNull()

    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key)
    private fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key)) null else optLong(key)
}

/** 原生 TCP Socket 客户端：断开后自动每 3 秒重连。 */
class ChildSocketClient(
    private val scope: CoroutineScope,
    private val onSnapshot: suspend (DataSnapshot) -> Unit,
    private val onState: (String) -> Unit
) {
    private val sendLock = Any()
    private var connectionJob: Job? = null
    private var socket: Socket? = null
    private var desiredIp: String = ""

    fun connect(ip: String) {
        desiredIp = ip.trim()
        if (desiredIp.isBlank()) {
            onState("请输入家长端局域网 IP")
            return
        }
        connectionJob?.cancel()
        connectionJob = scope.launch(Dispatchers.IO) {
            while (desiredIp.isNotBlank() && coroutineContext.isActive) {
                var connectedSocket: Socket? = null
                try {
                    onState("正在连接家长端 $desiredIp:18765 …")
                    connectedSocket = Socket()
                    connectedSocket.connect(InetSocketAddress(desiredIp, 18765), 3000)
                    socket = connectedSocket
                    onState("已连接家长端")

                    // 建立连接后先把本机离线数据发送给家长端，再接收合并结果。
                    send(currentSnapshot())

                    val reader = BufferedReader(InputStreamReader(connectedSocket.getInputStream(), Charsets.UTF_8))
                    while (!connectedSocket.isClosed) {
                        val line = reader.readLine() ?: break
                        SnapshotCodec.decode(line)?.let { onSnapshot(it) }
                    }
                    onState("连接已断开，3 秒后自动重连")
                } catch (e: Exception) {
                    onState("连接失败：${e.message ?: "无法连接"}，3 秒后重试")
                } finally {
                    if (socket === connectedSocket) socket = null
                    runCatching { connectedSocket?.close() }
                }
                delay(3000)
            }
        }
    }

    suspend fun send(snapshot: DataSnapshot) = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext
        runCatching {
            synchronized(sendLock) {
                val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                writer.write(SnapshotCodec.encode(snapshot))
                writer.newLine()
                writer.flush()
            }
        }.onFailure { onState("同步发送失败：${it.message ?: "连接已断开"}") }
    }

    suspend fun currentSnapshot(): DataSnapshot = AppDatabaseHolder.db.let { db ->
        val wallet = db.walletDao().get() ?: WalletEntity(1, 0, now(), now())
        DataSnapshot(
            db.taskDao().getAll().sortedBy { it.id },
            db.shopDao().getAll().sortedBy { it.id },
            db.redemptionDao().getAll().sortedBy { it.id },
            wallet
        )
    }

    fun close() {
        desiredIp = ""
        connectionJob?.cancel()
        runCatching { socket?.close() }
        socket = null
    }
}

class ChildRepository(
    private val db: AppDatabase,
    private val client: ChildSocketClient
) {
    suspend fun claimTask(id: String): Result<Unit> = try {
        val task = db.taskDao().getById(id) ?: error("任务不存在")
        if (task.status != TaskStatus.WAITING && task.status != TaskStatus.REJECTED) error("任务当前状态不可领取")
        val t = now()
        if (task.deadlineAt < t) error("任务已超过截止时间")
        db.taskDao().upsert(task.copy(status = TaskStatus.DOING, claimedAt = t, updatedAt = t))
        push()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun submitTask(id: String, note: String): Result<Unit> = try {
        val task = db.taskDao().getById(id) ?: error("任务不存在")
        if (task.status != TaskStatus.DOING) error("任务当前状态不可提交")
        val t = now()
        db.taskDao().upsert(task.copy(
            status = TaskStatus.REVIEW,
            childNote = note.trim(),
            submittedAt = t,
            updatedAt = t
        ))
        push()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun requestRedemption(itemId: String, note: String): Result<Unit> = try {
        val item = db.shopDao().getById(itemId) ?: error("商品不存在")
        val wallet = db.walletDao().get() ?: WalletEntity(1, 0, now(), now())
        if (!item.enabled) error("商品已下架")
        if (wallet.points < item.costPoints) error("积分不足")
        val t = now()
        db.redemptionDao().upsert(
            RedemptionEntity(
                id = UUID.randomUUID().toString(),
                itemId = item.id,
                itemName = item.name,
                costPoints = item.costPoints,
                status = RedemptionStatus.PENDING,
                note = note.trim(),
                rejectReason = null,
                createdAt = t,
                updatedAt = t,
                reviewedAt = null
            )
        )
        push()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun mergeRemote(remote: DataSnapshot) {
        val local = currentSnapshot()
        val mergedTasks = mergeById(local.tasks, remote.tasks) { it.id }
        val mergedShop = mergeById(local.shopItems, remote.shopItems) { it.id }
        val mergedRedemption = mergeById(local.redemptions, remote.redemptions) { it.id }
        val mergedWallet = if (remote.wallet.updatedAt > local.wallet.updatedAt) remote.wallet else local.wallet
        val merged = DataSnapshot(mergedTasks, mergedShop, mergedRedemption, mergedWallet)

        db.withTransaction {
            merged.tasks.forEach { db.taskDao().upsert(it) }
            merged.shopItems.forEach { db.shopDao().upsert(it) }
            merged.redemptions.forEach { db.redemptionDao().upsert(it) }
            db.walletDao().upsert(merged.wallet)
        }

        // 如果孩子端保留了更晚的离线数据，则把合并结果回传给家长端。
        if (SnapshotCodec.encode(merged) != SnapshotCodec.encode(remote)) {
            client.send(merged)
        }
    }

    suspend fun currentSnapshot(): DataSnapshot {
        val wallet = db.walletDao().get() ?: WalletEntity(1, 0, now(), now())
        return DataSnapshot(
            db.taskDao().getAll().sortedBy { it.id },
            db.shopDao().getAll().sortedBy { it.id },
            db.redemptionDao().getAll().sortedBy { it.id },
            wallet
        )
    }

    private suspend fun push() = client.send(currentSnapshot())

    private fun <T : Any> mergeById(local: List<T>, remote: List<T>, idOf: (T) -> String): List<T> {
        val result = linkedMapOf<String, T>()
        local.forEach { result[idOf(it)] = it }
        remote.forEach { incoming ->
            val old = result[idOf(incoming)]
            if (old == null || updatedAt(incoming) > updatedAt(old)) result[idOf(incoming)] = incoming
        }
        return result.values.sortedBy(idOf)
    }

    private fun updatedAt(value: Any): Long = when (value) {
        is TaskEntity -> value.updatedAt
        is ShopItemEntity -> value.updatedAt
        is RedemptionEntity -> value.updatedAt
        else -> 0L
    }
}

/** Application 级容器：本地 Room + Socket 客户端在整个进程内复用。 */
class ChildApplication : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var client: ChildSocketClient
        private set
    lateinit var repository: ChildRepository
        private set
    lateinit var scope: CoroutineScope
        private set

    val socketState = AtomicReference("未连接")

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "child_points.db").build()
        AppDatabaseHolder.db = db
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        client = ChildSocketClient(
            scope = scope,
            onSnapshot = { snapshot -> repository.mergeRemote(snapshot) },
            onState = { socketState.set(it) }
        )
        repository = ChildRepository(db, client)
        scope.launch { ensureWallet() }
    }

    override fun onTerminate() {
        client.close()
        scope.cancel()
        super.onTerminate()
    }

    private suspend fun ensureWallet() {
        if (db.walletDao().get() == null) {
            val t = now()
            db.walletDao().upsert(WalletEntity(1, 0, t, t))
        }
    }
}

/** 客户端只需要一套 Room，给 Socket 类访问当前数据库即可。 */
private object AppDatabaseHolder {
    lateinit var db: AppDatabase
}

private fun now(): Long = System.currentTimeMillis()

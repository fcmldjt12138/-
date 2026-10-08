package com.example.parentpoints

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 网络同步的数据快照。列表会按 id 去重、按 updatedAt 决定新旧。 */
data class DataSnapshot(
    val tasks: List<TaskEntity>,
    val shopItems: List<ShopItemEntity>,
    val redemptions: List<RedemptionEntity>,
    val wallet: WalletEntity
)

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

    fun decode(line: String): DataSnapshot? {
        return runCatching {
            val root = JSONObject(line)
            if (root.optString("type") != TYPE) return null
            val tasks = mutableListOf<TaskEntity>()
            val tasksJson = root.optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until tasksJson.length()) {
                val o = tasksJson.getJSONObject(i)
                tasks += TaskEntity(
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
                )
            }

            val shops = mutableListOf<ShopItemEntity>()
            val shopsJson = root.optJSONArray("shopItems") ?: JSONArray()
            for (i in 0 until shopsJson.length()) {
                val o = shopsJson.getJSONObject(i)
                shops += ShopItemEntity(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    description = o.optString("description"),
                    costPoints = o.optInt("costPoints"),
                    enabled = o.optBoolean("enabled", true),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt")
                )
            }

            val redemptions = mutableListOf<RedemptionEntity>()
            val redemptionJson = root.optJSONArray("redemptions") ?: JSONArray()
            for (i in 0 until redemptionJson.length()) {
                val o = redemptionJson.getJSONObject(i)
                redemptions += RedemptionEntity(
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
                )
            }

            val walletObject = root.optJSONObject("wallet")
            val wallet = WalletEntity(
                id = 1,
                points = walletObject?.optInt("points", 0) ?: 0,
                createdAt = walletObject?.optLong("createdAt", 0L) ?: 0L,
                updatedAt = walletObject?.optLong("updatedAt", 0L) ?: 0L
            )
            DataSnapshot(tasks, shops, redemptions, wallet)
        }.getOrNull()
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key)) null else optLong(key)
}

/** 原生 TCP Socket 服务端。这里只服务同一局域网中的一个孩子端。 */
class ParentSocketServer(
    private val port: Int = 18765,
    private val scope: CoroutineScope,
    private val onSnapshot: suspend (DataSnapshot) -> Unit,
    private val onState: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var serverJob: Job? = null
    private val sending = Any()

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                onState("等待孩子端连接（端口 $port）")
                while (true) {
                    val socket = serverSocket?.accept() ?: break
                    clientSocket?.close()
                    clientSocket = socket
                    onState("孩子端已连接：${socket.inetAddress.hostAddress}")
                    handleClient(socket)
                }
            } catch (e: Exception) {
                onState("Socket服务停止：${e.message ?: "未知错误"}")
            }
        }
    }

    suspend fun send(snapshot: DataSnapshot) = withContext(Dispatchers.IO) {
        val socket = clientSocket ?: return@withContext
        runCatching {
            synchronized(sending) {
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                writer.write(SnapshotCodec.encode(snapshot))
                writer.newLine()
                writer.flush()
            }
        }.onFailure {
            onState("发送同步数据失败：${it.message ?: "连接已断开"}")
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            while (!socket.isClosed) {
                val line = reader.readLine() ?: break
                SnapshotCodec.decode(line)?.let { onSnapshot(it) }
            }
        } catch (e: Exception) {
            onState("孩子端连接异常：${e.message ?: "未知错误"}")
        } finally {
            if (clientSocket === socket) clientSocket = null
            runCatching { socket.close() }
            onState("孩子端已断开，等待重连")
        }
    }

    fun close() {
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        serverJob?.cancel()
    }
}

/** 负责从 Room 生成完整快照，以及按时间戳合并远端快照。 */
class SyncRepository(private val db: AppDatabase) {
    suspend fun currentSnapshot(): DataSnapshot {
        val wallet = db.walletDao().get() ?: WalletEntity(1, 0, now(), now())
        return DataSnapshot(
            tasks = db.taskDao().getAll().sortedBy { it.id },
            shopItems = db.shopDao().getAll().sortedBy { it.id },
            redemptions = db.redemptionDao().getAll().sortedBy { it.id },
            wallet = wallet
        )
    }

    /**
     * 按每条记录 updatedAt 比较：时间戳更新的记录覆盖旧记录。
     * 返回的 remoteNeedsUpdate=true 表示本地数据比对方更新，需要回传合并后的快照。
     */
    suspend fun mergeRemote(remote: DataSnapshot): Boolean {
        val local = currentSnapshot()
        val mergedTasks = mergeById(local.tasks, remote.tasks) { it.id }
        val mergedShop = mergeById(local.shopItems, remote.shopItems) { it.id }
        val mergedRedemption = mergeById(local.redemptions, remote.redemptions) { it.id }
        val mergedWallet = if (remote.wallet.updatedAt > local.wallet.updatedAt) remote.wallet else local.wallet

        val merged = DataSnapshot(mergedTasks, mergedShop, mergedRedemption, mergedWallet)
        db.withTransaction {
            mergedTasks.forEach { db.taskDao().upsert(it) }
            mergedShop.forEach { db.shopDao().upsert(it) }
            mergedRedemption.forEach { db.redemptionDao().upsert(it) }
            db.walletDao().upsert(mergedWallet)
        }

        return snapshotKey(merged) != snapshotKey(remote)
    }

    private fun <T : Any> mergeById(local: List<T>, remote: List<T>, idOf: (T) -> String): List<T> {
        val result = linkedMapOf<String, T>()
        local.forEach { result[idOf(it)] = it }
        remote.forEach { incoming ->
            val existing = result[idOf(incoming)]
            if (existing == null || updatedAt(incoming) > updatedAt(existing)) {
                result[idOf(incoming)] = incoming
            }
        }
        return result.values.sortedBy { idOf(it) }
    }

    private fun updatedAt(value: Any): Long = when (value) {
        is TaskEntity -> value.updatedAt
        is ShopItemEntity -> value.updatedAt
        is RedemptionEntity -> value.updatedAt
        else -> 0L
    }

    private fun snapshotKey(snapshot: DataSnapshot): String = SnapshotCodec.encode(snapshot)
}

class ParentRepository(
    private val db: AppDatabase,
    private val socketServer: ParentSocketServer,
    private val scope: CoroutineScope
) {
    private val sync = SyncRepository(db)

    init {
        scope.launch {
            ensureWallet()
            socketServer.start()
        }
    }

    suspend fun createTask(name: String, description: String, points: Int, deadlineAt: Long) {
        val t = now()
        db.taskDao().upsert(
            TaskEntity(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                description = description.trim(),
                rewardPoints = points,
                deadlineAt = deadlineAt,
                status = TaskStatus.WAITING,
                childNote = null,
                rejectReason = null,
                createdAt = t,
                updatedAt = t,
                claimedAt = null,
                submittedAt = null,
                reviewedAt = null
            )
        )
        pushSnapshot()
    }

    suspend fun createShopItem(name: String, description: String, costPoints: Int) {
        val t = now()
        db.shopDao().upsert(
            ShopItemEntity(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                description = description.trim(),
                costPoints = costPoints,
                enabled = true,
                createdAt = t,
                updatedAt = t
            )
        )
        pushSnapshot()
    }

    suspend fun reviewTask(id: String, approve: Boolean, reason: String?) {
        val task = db.taskDao().getById(id) ?: return
        if (task.status != TaskStatus.REVIEW) return
        val t = now()
        val updated = task.copy(
            status = if (approve) TaskStatus.APPROVED else TaskStatus.REJECTED,
            rejectReason = if (approve) null else reason?.trim(),
            reviewedAt = t,
            updatedAt = t
        )
        db.withTransaction {
            db.taskDao().upsert(updated)
            if (approve) {
                val wallet = db.walletDao().get() ?: WalletEntity(1, 0, t, t)
                db.walletDao().upsert(wallet.copy(points = wallet.points + task.rewardPoints, updatedAt = t))
            }
        }
        pushSnapshot()
    }

    suspend fun reviewRedemption(id: String, approve: Boolean, reason: String?) {
        val item = db.redemptionDao().getById(id) ?: return
        if (item.status != RedemptionStatus.PENDING) return
        val t = now()
        val wallet = db.walletDao().get() ?: WalletEntity(1, 0, t, t)
        if (approve && wallet.points < item.costPoints) {
            db.redemptionDao().upsert(item.copy(
                status = RedemptionStatus.REJECTED,
                rejectReason = "积分不足，无法兑换",
                reviewedAt = t,
                updatedAt = t
            ))
            pushSnapshot()
            return
        }
        db.withTransaction {
            db.redemptionDao().upsert(item.copy(
                status = if (approve) RedemptionStatus.APPROVED else RedemptionStatus.REJECTED,
                rejectReason = if (approve) null else reason?.trim(),
                reviewedAt = t,
                updatedAt = t
            ))
            if (approve) {
                db.walletDao().upsert(wallet.copy(points = wallet.points - item.costPoints, updatedAt = t))
            }
        }
        pushSnapshot()
    }

    suspend fun onRemoteSnapshot(remote: DataSnapshot) {
        val remoteNeedsUpdate = sync.mergeRemote(remote)
        // 合并后把更新后的完整快照回传，确保孩子端最终拿到相同状态。
        if (remoteNeedsUpdate) socketServer.send(sync.currentSnapshot())
    }

    suspend fun pushSnapshot() = socketServer.send(sync.currentSnapshot())

    private suspend fun ensureWallet() {
        if (db.walletDao().get() == null) {
            val t = now()
            db.walletDao().upsert(WalletEntity(1, 0, t, t))
        }
    }

    companion object {
        private fun now(): Long = System.currentTimeMillis()
    }
}

object LanUtils {
    fun localIpv4(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
                ?.hostAddress
                ?: "未检测到局域网 IPv4"
        }.getOrElse { "获取IP失败：${it.message}" }
    }

    private fun <T> java.util.Enumeration<T>.toList(): List<T> {
        val result = mutableListOf<T>()
        while (hasMoreElements()) result += nextElement()
        return result
    }
}

private fun now(): Long = System.currentTimeMillis()

/** Application 持有 Room 与 Socket，旋转屏幕不会丢失连接。 */
class ParentApplication : android.app.Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var server: ParentSocketServer
        private set
    lateinit var repository: ParentRepository
        private set
    lateinit var appScope: CoroutineScope
        private set

    var socketState: AtomicReference<String> = AtomicReference("初始化中…")
        private set
    val lastError = AtomicReference<String?>(null)

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "parent_points.db").build()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = ParentSocketServer(
            scope = appScope,
            onSnapshot = { snapshot ->
                repository.onRemoteSnapshot(snapshot)
            },
            onState = { state -> socketState.set(state) }
        )
        repository = ParentRepository(db, server, appScope)
    }

    override fun onTerminate() {
        server.close()
        appScope.cancel()
        super.onTerminate()
    }
}

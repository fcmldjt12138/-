package com.example.parentpoints

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ParentApplication
        setContent {
            MaterialTheme {
                ParentRoot(app)
            }
        }
    }
}

@Composable
private fun ParentRoot(app: ParentApplication) {
    val tasks by app.db.taskDao().observeAll().collectAsState(initial = emptyList())
    val shopItems by app.db.shopDao().observeAll().collectAsState(initial = emptyList())
    val redemptions by app.db.redemptionDao().observeAll().collectAsState(initial = emptyList())
    val wallet by app.db.walletDao().observe().collectAsState(initial = null)
    var socketState by remember { mutableStateOf(app.socketState.get()) }
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        while (true) {
            socketState = app.socketState.get()
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("家长积分管理") })
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本机局域网 IP", style = MaterialTheme.typography.labelLarge)
                    Text(LanUtils.localIpv4(), style = MaterialTheme.typography.headlineSmall)
                    Text("孩子端输入上面的 IP，并连接端口 18765")
                    Text("Socket：$socketState")
                    Text("当前积分：${wallet?.points ?: 0}", style = MaterialTheme.typography.titleMedium)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("任务", "创建任务", "商城", "兑换审核").forEachIndexed { index, title ->
                    if (tab == index) {
                        Button(onClick = { tab = index }, modifier = Modifier.weight(1f)) { Text(title) }
                    } else {
                        OutlinedButton(onClick = { tab = index }, modifier = Modifier.weight(1f)) { Text(title) }
                    }
                }
            }

            when (tab) {
                0 -> TaskManagement(tasks, scope, snackbar, app.repository)
                1 -> CreateTaskScreen(scope, snackbar, app.repository)
                2 -> ShopScreen(shopItems, scope, snackbar, app.repository)
                3 -> RedemptionScreen(redemptions, scope, snackbar, app.repository)
            }
        }
    }
}

@Composable
private fun TaskManagement(
    tasks: List<TaskEntity>,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    repository: ParentRepository
) {
    var rejectTask by remember { mutableStateOf<TaskEntity?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item { Text("任务记录（共 ${tasks.size} 条）", style = MaterialTheme.typography.titleLarge) }
        items(tasks, key = { it.id }) { task ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text(task.description.ifBlank { "无任务描述" })
                    Text("奖励：${task.rewardPoints} 分 · 状态：${taskStatusText(task.status)}")
                    Text("截止：${formatTime(task.deadlineAt)}")
                    task.childNote?.takeIf { it.isNotBlank() }?.let { Text("孩子备注：$it") }
                    task.rejectReason?.takeIf { it.isNotBlank() }?.let { Text("驳回理由：$it") }
                    if (task.status == TaskStatus.REVIEW) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    try {
                                        repository.reviewTask(task.id, true, null)
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar("审核失败：${e.message ?: "数据库异常"}")
                                    }
                                }
                            }) { Text("通过并发积分") }
                            OutlinedButton(onClick = { rejectTask = task }) { Text("驳回") }
                        }
                    }
                }
            }
        }
    }

    rejectTask?.let { task ->
        var reason by remember(task.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectTask = null },
            title = { Text("驳回任务") },
            text = {
                TextField(value = reason, onValueChange = { reason = it }, label = { Text("驳回理由") })
            },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    scope.launch {
                        try {
                            repository.reviewTask(task.id, false, reason)
                        } catch (e: Exception) {
                            snackbar.showSnackbar("操作失败：${e.message ?: "数据库异常"}")
                        }
                        rejectTask = null
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { rejectTask = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun CreateTaskScreen(
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    repository: ParentRepository
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var points by remember { mutableStateOf("10") }
    var deadlineAt by remember { mutableStateOf(System.currentTimeMillis() + 86_400_000L) }
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("创建任务工单", style = MaterialTheme.typography.titleLarge)
        TextField(name, { name = it }, label = { Text("任务名称") }, modifier = Modifier.fillMaxWidth())
        TextField(description, { description = it }, label = { Text("任务描述") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        TextField(points, { points = it.filter(Char::isDigit) }, label = { Text("奖励积分") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { showDateTimePicker(context, deadlineAt) { deadlineAt = it } }, modifier = Modifier.fillMaxWidth()) {
            Text("截止时间：${formatTime(deadlineAt)}")
        }
        Button(
            enabled = name.isNotBlank() && (points.toIntOrNull() ?: 0) > 0,
            onClick = {
                scope.launch {
                    try {
                        repository.createTask(
                            name,
                            description,
                            points.toIntOrNull() ?: 0,
                            deadlineAt
                        )
                        snackbar.showSnackbar("任务已创建并同步")
                    } catch (e: Exception) {
                        snackbar.showSnackbar("创建失败：${e.message ?: "数据库异常"}")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("创建并推送到孩子端") }
    }
}

@Composable
private fun ShopScreen(
    items: List<ShopItemEntity>,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    repository: ParentRepository
) {
    var showAdd by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("积分商城", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { showAdd = true }) { Text("新增商品") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.description.ifBlank { "无商品描述" })
                        Text("兑换需要：${item.costPoints} 分")
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var cost by remember { mutableStateOf("20") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新增商城物品") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextField(name, { name = it }, label = { Text("物品名称") })
                    TextField(description, { description = it }, label = { Text("物品描述") })
                    TextField(cost, { cost = it.filter(Char::isDigit) }, label = { Text("所需积分") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && (cost.toIntOrNull() ?: 0) > 0, onClick = {
                    scope.launch {
                        try {
                            repository.createShopItem(name, description, cost.toIntOrNull() ?: 0)
                            snackbar.showSnackbar("商品已添加并同步")
                        } catch (e: Exception) {
                            snackbar.showSnackbar("添加失败：${e.message ?: "数据库异常"}")
                        }
                        showAdd = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun RedemptionScreen(
    items: List<RedemptionEntity>,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    repository: ParentRepository
) {
    var reject by remember { mutableStateOf<RedemptionEntity?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item { Text("兑换申请（共 ${items.size} 条）", style = MaterialTheme.typography.titleLarge) }
        items(items, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.itemName, style = MaterialTheme.typography.titleMedium)
                    Text("扣除积分：${item.costPoints} · 状态：${redemptionStatusText(item.status)}")
                    item.note?.takeIf { it.isNotBlank() }?.let { Text("孩子备注：$it") }
                    item.rejectReason?.takeIf { it.isNotBlank() }?.let { Text("驳回理由：$it") }
                    if (item.status == RedemptionStatus.PENDING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    try {
                                        repository.reviewRedemption(item.id, true, null)
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar("审核失败：${e.message ?: "数据库异常"}")
                                    }
                                }
                            }) { Text("确认兑换") }
                            OutlinedButton(onClick = { reject = item }) { Text("驳回") }
                        }
                    }
                }
            }
        }
    }

    reject?.let { item ->
        var reason by remember(item.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reject = null },
            title = { Text("驳回兑换申请") },
            text = { TextField(reason, { reason = it }, label = { Text("驳回理由") }) },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    scope.launch {
                        try {
                            repository.reviewRedemption(item.id, false, reason)
                        } catch (e: Exception) {
                            snackbar.showSnackbar("操作失败：${e.message ?: "数据库异常"}")
                        }
                        reject = null
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { reject = null }) { Text("取消") } }
        )
    }
}

private fun taskStatusText(status: String): String = when (status) {
    TaskStatus.WAITING -> "待领取"
    TaskStatus.DOING -> "进行中"
    TaskStatus.REVIEW -> "待审核"
    TaskStatus.APPROVED -> "已通过"
    TaskStatus.REJECTED -> "已驳回"
    else -> status
}

private fun redemptionStatusText(status: String): String = when (status) {
    RedemptionStatus.PENDING -> "待审核"
    RedemptionStatus.APPROVED -> "已通过"
    RedemptionStatus.REJECTED -> "已驳回"
    else -> status
}

private fun formatTime(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))

private fun showDateTimePicker(context: Context, initialTime: Long, onSelected: (Long) -> Unit) {
    val calendar = Calendar.getInstance().apply { timeInMillis = initialTime }
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val result = Calendar.getInstance().apply {
                        set(year, month, dayOfMonth, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onSelected(result.timeInMillis)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}


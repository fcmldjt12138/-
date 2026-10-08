package com.example.childpoints

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ChildApplication
        setContent { MaterialTheme { ChildRoot(app) } }
    }
}

@Composable
private fun ChildRoot(app: ChildApplication) {
    val tasks by app.db.taskDao().observeAll().collectAsState(initial = emptyList())
    val shopItems by app.db.shopDao().observeEnabled().collectAsState(initial = emptyList())
    val redemptions by app.db.redemptionDao().observeAll().collectAsState(initial = emptyList())
    val wallet by app.db.walletDao().observe().collectAsState(initial = null)
    var tab by remember { mutableIntStateOf(0) }
    var ip by remember { mutableStateOf("") }
    var socketState by remember { mutableStateOf(app.socketState.get()) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        while (true) {
            socketState = app.socketState.get()
            delay(500)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("孩子积分助手") }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("我的积分", style = MaterialTheme.typography.labelLarge)
                    Text("${wallet?.points ?: 0}", style = MaterialTheme.typography.headlineLarge)
                    Text("Socket：$socketState")
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextField(
                    value = ip,
                    onValueChange = { ip = it },
                    singleLine = true,
                    label = { Text("家长端局域网 IP") },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = ip.isNotBlank(),
                    onClick = { app.client.connect(ip) }
                ) { Text("连接") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("任务", "商城", "我的记录").forEachIndexed { index, title ->
                    if (tab == index) {
                        Button(onClick = { tab = index }, modifier = Modifier.weight(1f)) { Text(title) }
                    } else {
                        OutlinedButton(onClick = { tab = index }, modifier = Modifier.weight(1f)) { Text(title) }
                    }
                }
            }

            when (tab) {
                0 -> ChildTaskScreen(tasks, scope, snackbar, app.repository)
                1 -> ChildShopScreen(shopItems, wallet?.points ?: 0, scope, snackbar, app.repository)
                2 -> ChildHistoryScreen(tasks, redemptions)
            }
        }
    }
}

@Composable
private fun ChildTaskScreen(
    tasks: List<TaskEntity>,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    repository: ChildRepository
) {
    var submitTask by remember { mutableStateOf<TaskEntity?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item { Text("家长发布的任务", style = MaterialTheme.typography.titleLarge) }
        items(tasks, key = { it.id }) { task ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text(task.description.ifBlank { "无描述" })
                    Text("奖励：${task.rewardPoints} 分 · ${statusText(task.status)}")
                    Text("截止：${formatTime(task.deadlineAt)}")
                    task.rejectReason?.takeIf { it.isNotBlank() }?.let { Text("驳回理由：$it") }
                    when (task.status) {
                        TaskStatus.WAITING -> Button(onClick = {
                            scope.launch {
                                repository.claimTask(task.id).fold(
                                    onSuccess = { snackbar.showSnackbar("已领取任务") },
                                    onFailure = { snackbar.showSnackbar("领取失败：${it.message ?: "数据库异常"}") }
                                )
                            }
                        }) { Text("领取任务") }
                        TaskStatus.DOING -> Button(onClick = { submitTask = task }) { Text("完成并提交审核") }
                        TaskStatus.REVIEW -> Text("已提交，等待家长审核")
                        TaskStatus.APPROVED -> Text("已通过，奖励已发放")
                        TaskStatus.REJECTED -> Button(onClick = { submitTask = task }) { Text("重新完成并提交") }
                    }
                }
            }
        }
    }

    submitTask?.let { task ->
        var note by remember(task.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { submitTask = null },
            title = { Text("提交任务") },
            text = { TextField(note, { note = it }, label = { Text("完成备注") }, minLines = 3) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // 被驳回的任务重新进入进行中状态，然后提交。
                        if (task.status == TaskStatus.REJECTED) {
                            val result = repository.claimTask(task.id)
                            if (result.isFailure) {
                                snackbar.showSnackbar("重新开始失败：${result.exceptionOrNull()?.message}")
                                submitTask = null
                                return@launch
                            }
                        }
                        repository.submitTask(task.id, note).fold(
                            onSuccess = { snackbar.showSnackbar("已提交，等待家长审核") },
                            onFailure = { snackbar.showSnackbar("提交失败：${it.message ?: "数据库异常"}") }
                        )
                        submitTask = null
                    }
                }) { Text("提交") }
            },
            dismissButton = { TextButton(onClick = { submitTask = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ChildShopScreen(
    items: List<ShopItemEntity>,
    points: Int,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    repository: ChildRepository
) {
    var redeemItem by remember { mutableStateOf<ShopItemEntity?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text("积分商城 · 当前 $points 分", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
        }
        items(items, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(item.description.ifBlank { "无描述" })
                    Text("需要 ${item.costPoints} 分")
                    Button(
                        enabled = points >= item.costPoints,
                        onClick = { redeemItem = item }
                    ) { Text(if (points >= item.costPoints) "申请兑换" else "积分不足") }
                }
            }
        }
    }

    redeemItem?.let { item ->
        var note by remember(item.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { redeemItem = null },
            title = { Text("申请兑换：${item.name}") },
            text = { TextField(note, { note = it }, label = { Text("备注，可选") }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.requestRedemption(item.id, note).fold(
                            onSuccess = { snackbar.showSnackbar("兑换申请已提交") },
                            onFailure = { snackbar.showSnackbar("申请失败：${it.message ?: "数据库异常"}") }
                        )
                        redeemItem = null
                    }
                }) { Text("提交") }
            },
            dismissButton = { TextButton(onClick = { redeemItem = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ChildHistoryScreen(tasks: List<TaskEntity>, redemptions: List<RedemptionEntity>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item { Text("任务历史", style = MaterialTheme.typography.titleLarge) }
        items(tasks, key = { "t_${it.id}" }) { task ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text("状态：${statusText(task.status)} · 奖励 ${task.rewardPoints} 分")
                    task.childNote?.takeIf { it.isNotBlank() }?.let { Text("备注：$it") }
                }
            }
        }
        item { Text("兑换历史", style = MaterialTheme.typography.titleLarge) }
        items(redemptions, key = { "r_${it.id}" }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.itemName, style = MaterialTheme.typography.titleMedium)
                    Text("${redemptionText(item.status)} · ${item.costPoints} 分")
                    item.rejectReason?.takeIf { it.isNotBlank() }?.let { Text("驳回：$it") }
                }
            }
        }
    }
}

private fun statusText(status: String): String = when (status) {
    TaskStatus.WAITING -> "待领取"
    TaskStatus.DOING -> "进行中"
    TaskStatus.REVIEW -> "待审核"
    TaskStatus.APPROVED -> "已通过"
    TaskStatus.REJECTED -> "已驳回"
    else -> status
}

private fun redemptionText(status: String): String = when (status) {
    RedemptionStatus.PENDING -> "待家长确认"
    RedemptionStatus.APPROVED -> "兑换成功"
    RedemptionStatus.REJECTED -> "兑换被驳回"
    else -> status
}

private fun formatTime(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))

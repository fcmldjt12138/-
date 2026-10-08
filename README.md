# 儿童积分工单管理系统

包含两个完全独立的 Android Studio 项目：

- ParentApp：家长端 APK，原生 TCP Socket 服务端 + Room 本地数据库。
- ChildApp：孩子端 APK，原生 TCP Socket 客户端 + Room 本地数据库。

## 技术栈

- Kotlin 2.2.21
- Android Gradle Plugin 8.13.2
- Gradle 8.13
- Jetpack Compose BOM 2026.09.00
- Room 2.8.5 + KSP 2.2.21-2.0.5
- Activity 1.13.0
- Lifecycle 2.11.0
- kotlinx-coroutines-android 1.10.2
- minSdk 26 / targetSdk 36
- 原生 java.net.ServerSocket / Socket
- Android 自带 org.json

## 同步规则

每台设备所有业务数据保存到本地 Room。两端通过同一 Wi-Fi 下 TCP 18765 端口交换完整快照；每条业务记录有 createdAt 和 updatedAt，合并时按 updatedAt 较新的记录覆盖较旧记录。

联网恢复后孩子端自动每 3 秒重连；连接建立后自动交换快照。孩子端在离线期间创建的领取/提交/兑换记录会在重新连接后同步。

## 已知限制

- 当前设计针对“一名孩子 + 一台家长手机”场景，一个家长端 Socket 服务端只维护一个孩子连接。
- Socket 服务运行在 App 进程内，没有独立后端服务器；Android 系统若彻底杀死 App 进程，Socket 会断开，重新打开 App 即可恢复数据库并重新建立服务/连接。
- 当前未加入账号体系、加密认证和配对码。局域网内拥有该 APK 的设备理论上可以尝试连接，因此不应把它用于不可信公共 Wi-Fi。
- 钱包积分只由家长端的审核操作改变，孩子端只接受同步结果，避免孩子端直接修改余额。

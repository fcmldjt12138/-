# 构建说明

本项目已修正源码中会阻止 Kotlin 编译的挂起函数错误，并附带 GitHub Actions 自动构建配置。

当前运行环境没有 Android SDK / Build Tools / Gradle，并且无法从容器直接访问 Google SDK 下载地址，因此无法在此环境中真实生成 APK 文件。

连接 GitHub 后，将此项目推送到仓库并运行 `Build Parent and Child APKs` workflow，即可得到两个可安装的 Debug APK：

- `ParentPoints-debug.apk`：家长端
- `ChildPoints-debug.apk`：孩子端

Debug APK 使用 Android 的 debug 签名，可直接用于两台 Android 手机测试安装。

# Android APK

这是自用的 Android 客户端最小实现。工程现在是标准 Gradle Android 项目，使用 Kotlin + Jetpack Compose + OkHttp WebSocket。

## 功能

- 配置 Linux bridge 的 host / port / token
- WebSocket 连接和断线提示
- 会话列表与会话切换
- 发送消息到 `turn.send`
- 流式接收 `item/agentMessage/delta`
- 审批卡片与 `approval.response`
- 协作模式、会话目标、模型、推理强度和沙盒设置
- 图片发送、查看、复制与保存
- Markdown、HTML、图片和二进制文件预览或下载
- 后台、跨会话、审批和任务完成通知
- 新会话远程项目目录选择
- 聊天 WebView 基于 `conversationItems` 做增量 DOM patch
- 会话恢复时按快照恢复聊天滚动位置
- 键盘弹出和视口变化时尽量保持当前阅读位置稳定

## 构建

初始化本机个人签名 key，一份 key 同时用于 debug 和 release：

```bash
npm run apk:release:setup
```

Debug APK:

```bash
bash android/scripts/build-apk.sh
```

Debug 产物：

```text
android/app/build/outputs/apk/debug/codex-remote-control-v0.2.0-debug.apk
```

Release APK:

```bash
bash android/scripts/build-release-apk.sh
```

Release 产物：

```text
android/app/build/outputs/apk/release/codex-remote-control-v0.2.0-release.apk
```

Debug 和 release APK 都使用本机 `android/release.keystore` 签名，后续升级必须继续使用同一份 keystore 和 `release-signing.properties`。

## Q&A

### 网络怎么解决？

Android 客户端需要能访问运行 bridge 的主机。局域网内可以直接使用 bridge 输出的 `ws://HOST:PORT/?token=...`；跨网络使用时，推荐自行使用外网穿透或异地组网方案，例如反向隧道、WireGuard、Tailscale、ZeroTier 或其他自维护 overlay network。不要把 bridge 直接裸露到公网。

## 说明

- 可以直接用 Android Studio 打开 `android/`。
- 已引入 Compose 处理真实移动端布局、输入法和消息界面体验。
- 聊天历史仍以 `session.content` 快照和 Activity 内 `conversationItems` 为准，WebView 只负责渲染与局部更新。
- 暂时没有引入 Room / Hilt，避免自用版本过早变成大工程。
- 网络层使用 OkHttp WebSocket，不再维护手写 WebSocket 协议。

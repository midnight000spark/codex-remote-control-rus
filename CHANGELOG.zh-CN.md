# 更新日志

[English Changelog](CHANGELOG.md)

## v0.2.0 - 2026-08-05

本次发布将 Android 客户端从基础远程聊天扩展为更完整的 Codex 会话移动端控制工具。

### 新增

- 聊天输入栏新增协作模式、会话目标、模型、推理强度、沙盒和 Token 状态控制。
- Android 支持上传图片和发送多图消息，并可鉴权查看、缩放、复制和保存会话图片。
- 支持鉴权下载会话文件、预览图片、打开或保存二进制文件，并提供 APK 安装引导。
- 支持 Markdown 和受限的静态 HTML 预览，可切换源码与渲染视图，并解析相对图片路径。
- 新增后台、跨会话、审批、连接失败和任务完成通知。
- 支持显示北京时间消息时间，并持久化相关设置及纳入备份恢复。
- 新建会话时支持浏览和选择远程项目目录。

### 优化与调整

- Bridge 会优先选择可用的最高版本 Codex 二进制，同时保留显式 `--codex-bin` 配置的最高优先级。
- 缩小会话列表响应体积，支持按时间范围筛选历史会话，并可在打开侧边栏时自动刷新。
- 桌面端 rollout 监控改为增量读取追加内容，可处理不完整行、文件截断与轮转，并对实时事件去重。
- Android release 构建在本地 Maven 缓存可用时会自动复用缓存。

### 修复

- 修复新建、切换、刷新和恢复会话时模型、推理强度、沙盒选择与当前会话状态不同步的问题。
- 即使后端线程摘要尚未更新，也会持久化成功发送消息时使用的模型和推理强度。
- 恢复聊天输入框的回车换行能力。
- 稳定用户消息标识，使实时投影能够与权威会话快照正确对账。
- 持久化并展示 Codex 后端连接失败，附带可操作的网络排查提示。

### 发布产物

- Android debug APK：`android/app/build/outputs/apk/debug/codex-remote-control-v0.2.0-debug.apk`
- Android release APK：`android/app/build/outputs/apk/release/codex-remote-control-v0.2.0-release.apk`

### 注意事项

- 已安装的旧版本必须继续使用同一份 `android/release.keystore` 和 `android/release-signing.properties`，才能原地升级。
- Android 13 及以上版本需要先授予通知权限，才能显示后台通知。
- 当前 WebSocket 生命周期仍由 Activity 管理，后台消息为尽力送达；详见 `docs/android-background-connection.md`。

## v0.1.0 - 2026-05-27

Codex Remote Control 的首个正式版本。

### 新增

- 用于连接私有网络 Codex Bridge 的 Android 客户端。
- 支持会话列表、恢复会话、新建聊天、发送消息和流式显示助手回复。
- Android 端支持展示审批请求并转发审批结果。
- 以会话快照为权威来源的聊天记录渲染、快照对账和增量更新。
- 浏览当前 Codex 工作区中的代码、diff 和文件变更。
- 共享 Bridge 缓存、连接历史、重连反馈和设置控制。
- 对接 `codex app-server --listen stdio://` 的 Node.js WebSocket Bridge。
- 持久化 Bridge 标识、状态和 Token，并支持配置运行时路径。
- 提供 Mock Backend 和 Node 侧测试，支持 Bridge 测试与 Android UI 开发。
- 提供共享本机个人签名的 debug 和 release APK 构建脚本。

### 发布产物

- Android debug APK：`android/app/build/outputs/apk/debug/codex-remote-control-v0.1.0-debug.apk`
- Android release APK：`android/app/build/outputs/apk/release/codex-remote-control-v0.1.0-release.apk`

### 注意事项

- Debug 和 release APK 使用 `npm run apk:release:setup` 创建的同一份本机个人签名。
- Bridge 和 Android 客户端面向可信私有网络设计，不应直接暴露到公网。

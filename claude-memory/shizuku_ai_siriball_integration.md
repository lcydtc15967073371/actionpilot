---
name: Shizuku AI 球整合
description: 将 BallApp 的 SiriBall 悬浮球整合到 Shizuku AI 浮窗中
type: project
originSessionId: 3a28d8b3-4076-4b99-860d-ccf1cd8dd6df
---
2026-05-11: 将 BallApp 的 SiriBall 功能整合到 Shizuku AI。具体改动：

- 创建 `SiriBallView.java` — 纯 Canvas+ValueAnimator 实现 Siri 风格动态球体，不依赖 Compose
- 修改 `FloatService.java` — 启动时先显示 SiriBall 小球，点击切换完整浮窗；浮窗标题栏加 ⚪ 按钮可缩小回球；拖拽支持
- 修改 `float_layout.xml` — 标题栏新增 minimize 按钮
- 版本号从 1.5.0 → 1.6.0

APK 位置: `f:/app/Claude/app/shizuku-ai/app/build/outputs/apk/debug/app-debug.apk`

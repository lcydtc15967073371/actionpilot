---
name: ActionPilot 产品方向（2026-05-07）
description: 用户对 ActionPilot 功能的真实需求和想法
type: project
originSessionId: 9de5cf41-4298-4cb4-8a88-9b5feff3ab4c
---
# ActionPilot 产品需求记录

- **录制方式**：Shizuku polling dumpsys window（每 500ms），记录用户实际操作路径
- **准确优先于完整**：只记实际经过的路径，不预测完整导航树。开 App 跳转到中间页，就从中问页开始记，不做假设
- **跨 App 导航**：用户在不同 App 之间切，地图要能看清 App 边界。不同 App 要不同颜色/分组

## 用户真实反馈（待实现）

1. **App 自动标注/进度条** — 录制时显示当前抓到了几个 App、几个节点
2. **导出格式** — 导出的 JSON 最终是用来分析操作流程，重点是**每个 App 内部操作序列**，按 App 分段的导航流

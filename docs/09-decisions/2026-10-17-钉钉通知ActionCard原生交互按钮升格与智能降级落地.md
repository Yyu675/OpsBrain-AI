# 决策记录：钉钉通知 ActionCard 原生交互按钮升格与 Markdown 智能降级落地

**日期**：2026-10-17  
**状态**：已批准并落地  
**分支**：`arena/56300a0a-opsbrain-ai`  
**关联 PR**：PR #32  

---

### 一、背景与问题

在前期重构中，平台通知卡片（`AlertNotifyTemplate`）已实现了纯函数、防注入的受信任平台 HTTPS 回链机制（如 `[查看告警并处理](https://.../alerts/id)`、`[查看工单](https://.../tickets/id)`）。
然而在钉钉客户端的实际展示中：
1. **纯 Markdown 超链接点击区域窄**：
   - 在移动端或小尺寸窗口中，正文尾部的行内 Markdown 链接文字面积过小，一线运维值班人员容易误触或需要多次缩放定位；
2. **缺乏即时通讯原生动作按钮**：
   - 钉钉官方支持 `actionCard`（独立交互卡片），可以在卡片底部提供醒目的独立动作按钮（如“查看告警并处理”、“查看工单”）；
   - 在此前实现中，钉钉一律以 `msgtype=markdown` 发送，未发挥企业即时通讯平台的原生交互能力。

---

### 二、决策方案与技术实现

1. **智能识别受信任回链与原生 ActionCard 升格**（`NotifyPayloads.java`）：
   - `dingtalkPayload` 在打包时，正则匹配正文中的 HTTPS 操作回链（`[title](https://url)`）；
   - 当回链存在且数量合理（1~3 个）时：
     - 正文自动剔除尾部回链文本，保留清晰纯净的排障上下文；
     - `msgtype` 自动升格为原生 `actionCard`；
     - 生成横向排列的独立动作按钮数组（`btns: [{title, actionURL}, ...]`）；
     - 若为紧急告警（`urgent=true`），正文自动追加 `@所有人` 并附带强提醒。
   - 当无回链（如未配置可信部署地址）或链接超额时：
     - 安全回退为标准 `msgtype=markdown`，保证绝对兼容性与零阻断。

2. **严格单测与契约保证**（`NotifyPayloadsTest.java`）：
   - 新增 `DingTalkPayloadCard` 测试集；
   - 验证含平台操作回链时升格为 `actionCard` 且按钮标题与 URL 完整正确；
   - 验证无回链时平滑回退为标准 `markdown`，紧急状态 `@所有人` 强提醒正常附着。

---

### 三、利弊与影响分析

- **利**：
  1. 移动端与桌面端钉钉群内形成标准的企业级监控告警卡片交互体验，一键直达处理或工单页；
  2. 依然保持单源构造与上层调用无侵入（`NotifyMessage` 保持中性模型不变，完全符合 `NotifierContractTest` 可插拔契约）；
  3. 回链仅解析已经过 `validBase` 严格防注入校验的合法 HTTPS 链接，安全性坚固。
- **弊**：
  - 无负面影响，不支持 ActionCard 的特殊情况会自动走 Markdown 兜底。

# OpsBrain 值班 SOP（S5-2 步骤 5-2.6 / 批 37）

> 原则：先降冲击面，再查根因；能切换默认值/开关解决的，不在深夜里动代码。
> 诊断/自愈系统本身的降级语义在代码里写好——值班的职责是**认得那些降级信号**，不是替系统工作。

## 1. AI 问答不可用（用户报「问不了」）

迹象：前端 SSE 无流、报系统繁忙；`docker compose logs app` 见 LLM 调用失败。
1. **先判级**:AI_MODE=REAL?ALIBABA_* key 过期/欠费/限流？——现网限流口径
   「配速非等待」(S0-3 案）：见日志 `RateLimited`。
2. **降冲击**：临时切 `AI_MODE=MOCK`（改 .env + `docker compose up -d app`)
   ——保底问答链断流但系统不躺；**切回前记工单**（策略 A 成本列差异口径）。
3. 查熔断：`/ai/actuator/health` 的 circuitBreakers 段——OPEN 态名即下游病灶。
4. 恢复后**必须**补一条故障工单回环（复盘闭环，报告 108 §阶段 2 接口）。

## 2. 向量库/知识检索异常

迹象：问答全走兜底话术、知识命中为零；`/ai/actuator/health` postgres DOWN。
1. `docker compose ps postgres` + `docker compose logs postgres --tail 50`——
   pgvector 扩展缺失时 schema 自检（SCHEMA_FAIL_FAST）会让 app 拒载。
2. 检索接口降级是设计内行为（三态取证 R-04)；确认 `SysKnowledgeDoc` 数及
   最近 ingest 日志，别在降级链上自责为 bug。
3. 恢复验证：SLB/K8s 手册话题问一题，应命中 `expectedDocs` 文档。

## 3. 告警风暴（同告警刷屏/工单爆炸）

迹象：工单量突增且描述雷同；Prometheus 单规则高频触发。
1. **已有机制先信**:dedup 指纹 + 聚合抑制（S4-1 钉测两分支零调用即证据）——
   风暴下工单数应仍被聚合压扁；若没压扁，别调代码，先看告警源是否换了
   fingerprint 相关 label(alertname/instance 变更=新指纹，dedup 自然失效）。
2. **不失手也要设闸**:Alertmanager 侧对该规则临时 silence(Web UI 或 amtool),
   记 silence 到故障工单，**超时销毁**——无超时 silence 是第二次风暴的种子。
3. 长期：风暴是路线图表引用的 MQ 候选触发器（D-07)；真到拖垮同步链的量级，
   把证据（QPS×时长×工单增量）附在 D-07 拍板材料里，不要只靠记忆报「很多」。

## 4. 磁盘满（第三大常见事故）

迹象：容器写失败、postgres 报 disk full;Prometheus HostDiskFull（如有）触发。
1. 大头三查：`docker system df` / `du -sh backups/*` / `docker compose logs` 体积——
   历史首犯往往是没有有界日志，本批已立 50m×5 预算（compose x-default-logging)。
2. 备份修剪：`RETENTION_DAYS` 默认 14——临时救火勿直接删最新批次，先导出后再剪。
3. `docker system prune` 只清悬空层，**别加 -a --volumes**(pg_data 无名卷会被带走）。

## 5. 通用收尾

- 每次值班处置必填：故障工单 + 处置时间线 + 「下次能不能让系统自己扛」一句；
- 重复出现的处置动作就是执行器矩阵扩展的候选（路线图 §10.2「执行器矩阵扩展」），
  别惯着自己做第三遍同一件事。

## 6. 批 45-52 落件后的新排障口（增量速查）

| 现场 | 新口 |
| --- | --- |
| 用户报「登录/对话被 429」 | 就是限流闸在工作，不是故障；核 IP 是否被代理吞（`TRUSTED_PROXIES` 未配=全站一只桶）——手册 §6 |
| 日志全是 JSON 看不懂 | `jq -c 'select(.traceId=="…")'`(prod 是 ECS 格式，批 48)——手册 §7 |
| 工单/审批积压告警响 | 业务告警组（批 50）起步阈值，先拆构成再定增员/排班，阈值别随手调大——手册 §8 |
| 「指标能不能推到公司监控」 | OTLP:`OTLP_EXPORT_ENABLED=true`+URL(60s 推；告警仍看 prom 抓）——手册 §9 |

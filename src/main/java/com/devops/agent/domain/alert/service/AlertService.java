package com.devops.agent.domain.alert.service;

import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.biz.entity.TicketEnums;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import com.devops.agent.domain.notify.NotifyMessage;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 告警处理服务（L2 实时监测）
 * <p>
 * 职责：接收 Prometheus Alertmanager 告警推送，完成去重、持久化、自动建单。
 * </p>
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>计算去重键 {@code SHA-256(alertName + service + 排序后的标签)}</li>
 *   <li>按去重键查询活跃告警（FIRING / ACKNOWLEDGED）</li>
 *   <li>已存在 → 递增 {@code occurrence_count}、刷新 {@code last_occurred_at}</li>
 *   <li>不存在 → 创建新告警 + 自动建单（通过 TicketService, Single Writer 契约 6.10）</li>
 *   <li>收到 resolved → 标记对应告警为 RESOLVED</li>
 * </ol>
 *
 * <h3>映射规则</h3>
 * <ul>
 *   <li>Level → Priority：P0/P1→HIGH, P2/P3→MEDIUM, P4→LOW</li>
 *   <li>Module → Category：DB→数据库, POD/K8S→容器/K8s, NETWORK→网络, 其余→其他</li>
 *   <li>Severity → Level：CRITICAL→P0, WARNING→P2, INFO→P4, 默认→P3</li>
 *   <li>SLA：HIGH→4h/8h, MEDIUM→8h/24h, LOW→24h</li>
 * </ul>
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>自动建单失败不阻塞告警入库——告警本体有效，工单是附属增值</li>
 *   <li>去重键排除 alertname/service/severity——这些字段已单独处理</li>
 *   <li>标签排序用 TreeMap 保证确定性——HashMap 迭代顺序不可靠</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-14
 */
@Slf4j
@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final TicketService ticketService;
    private final AlertWebSocketNotifier alertNotifier;
    /** 通知渠道：依赖接口而非具体厂商实现（可插拔） */
    private final com.devops.agent.domain.notify.Notifier notifier;

    public AlertService(AlertRepository alertRepository, TicketService ticketService,
                        AlertWebSocketNotifier alertNotifier,
                        com.devops.agent.domain.notify.Notifier notifier) {
        this.alertRepository = alertRepository;
        this.ticketService = ticketService;
        this.alertNotifier = alertNotifier;
        this.notifier = notifier;
    }

    // ==================== 配置注入（application.yml devops.alert.*） ====================
    // 6.20 契约：配置项必须有代码读取它——存在但无人读的配置比没有更糟。

    /** 告警接收总开关。关闭后 Webhook 端点仍返回 200 但跳过全部处理（Prometheus 对非 200 会重试） */
    @Value("${devops.alert.enabled:true}")
    private boolean alertEnabled;

    /** 自动建单开关。关闭后告警仍入库去重，但不触发自动建单（Single Writer 契约 6.10） */
    @Value("${devops.alert.auto-ticket-enabled:true}")
    private boolean autoTicketEnabled;

    /** 自动建单创建人标识（默认值为 ALERT_CREATOR "alert-bot"） */
    @Value("${devops.alert.ticket-creator:alert-bot}")
    private String alertCreator;

    /** 告警聚合降噪开关（方向 E）。关闭后回退为每条告警各建单（原行为） */
    @Value("${devops.alert.aggregate-enabled:true}")
    private boolean aggregateEnabled;

    /** 聚合时间窗口（分钟）：窗口内同 service+module 的不同告警聚合到同一工单 */
    @Value("${devops.alert.aggregate-window-minutes:5}")
    private int aggregateWindowMinutes;

    // ==================== Level → Priority 映射 ====================

    /**
     * 告警级别 → 工单优先级
     * <p>
     * <b>B0 改造</b>：工单优先级改四档 P0~P3 后，告警的 P0~P4 可以几乎一一对应，
     * 不再塌缩。此前 P0/P1 都映射为 HIGH、P2/P3 都映射为 MEDIUM——
     * 一条 P0 生产宕机告警与一条 P1 告警建出的工单优先级完全相同，
     * 分级响应无从谈起。
     * </p>
     * <p>
     * 仅 P4（信息类）与 P3 合并为工单 P3——工单侧无第五档，
     * 而 P4 本就不要求响应时限。
     * </p>
     */
    private static final Map<String, String> LEVEL_TO_PRIORITY = Map.of(
            "P0", TicketEnums.Priority.P0,
            "P1", TicketEnums.Priority.P1,
            "P2", TicketEnums.Priority.P2,
            "P3", TicketEnums.Priority.P3,
            "P4", TicketEnums.Priority.P3
    );

    // ==================== Module → Category 映射 ====================

    private static final Map<String, String> MODULE_TO_CATEGORY = Map.of(
            "DB", "数据库",
            "POD", "容器/K8s",
            "K8S", "容器/K8s",
            "NETWORK", "网络",
            "HOST", "其他",
            "CACHE", "其他"
    );

    private static final String DEFAULT_CATEGORY = "其他";
    private static final String ALERT_CREATOR = "alert-bot";

    // ==================== 对外入口 ====================

    /**
     * 处理 Alertmanager Webhook 推送（批量告警）
     * <p>
     * 单条失败不影响其余；失败路径记 ERROR 日志并继续。
     * </p>
     *
     * @param webhook Alertmanager 回调负载
     */
    public void processWebhook(AlertmanagerWebhook webhook) {
        // 总开关防护（控制器已按同开关提前拦截，此处为防御性二道校验）
        if (!alertEnabled) {
            log.warn("⏸️ [AlertService] 告警接收已关闭（devops.alert.enabled=false），跳过处理");
            return;
        }

        if (webhook == null || webhook.getAlerts() == null || webhook.getAlerts().isEmpty()) {
            log.warn("⚠️ [AlertService] 收到空告警负载，跳过处理");
            return;
        }

        for (AlertmanagerWebhook.Alert incoming : webhook.getAlerts()) {
            try {
                processSingleAlert(incoming);
            } catch (Exception e) {
                log.error("❌ [AlertService] 单条告警处理失败 | alertName={} | fingerprint={} | error={}",
                        incoming.getLabel("alertname"), incoming.getFingerprint(), e.getMessage(), e);
            }
        }
    }

    // ==================== 单条处理 ====================

    /**
     * 处理单条告警
     * <p>
     * 流程：计算去重键 → 查活跃告警 → 已存在则递增次数 / 不存在则创建 + 建单 / 已恢复则标记解决。
     * </p>
     */
    private void processSingleAlert(AlertmanagerWebhook.Alert incoming) {
        String alertName = incoming.getLabel("alertname");
        String service = incoming.getLabel("service");
        String severity = incoming.getLabel("severity");

        if (alertName == null || alertName.isBlank()) {
            log.warn("⚠️ [AlertService] 告警缺少 alertname，跳过 | fingerprint={}", incoming.getFingerprint());
            return;
        }

        // 计算去重键：排除 alertname/service/severity 避免重复
        String dedupKey = computeDedupKey(alertName, service, incoming.getLabels());

        // 已恢复告警：标记活跃告警为 RESOLVED
        if (incoming.isResolved()) {
            handleResolvedAlert(dedupKey);
            return;
        }

        // 查找活跃告警（FIRING / ACKNOWLEDGED）
        Optional<Alert> existing = alertRepository.findActiveByDedupKey(dedupKey);

        if (existing.isPresent()) {
            // 去重命中：递增次数 + 刷新最后触发时间
            Alert alert = existing.get();
            alertRepository.incrementOccurrence(alert.getId());
            log.info("🔁 [AlertService] 重复告警 | alertName={} | service={} | dedupKey={} | occurrence={}",
                    alertName, service, dedupKey, alert.getOccurrenceCount() + 1);
            // WebSocket 广播更新（非阻塞旁路——推送失败不影响主流程）
            alertNotifier.broadcastUpdate(alert);
        } else {
            // 新告警：创建告警记录 + 自动建单
            createNewAlert(incoming, alertName, service, severity, dedupKey);
        }
    }

    // ==================== 已恢复告警处理 ====================

    /**
     * 处理已恢复告警
     * <p>
     * Alertmanager 会在告警恢复时推送 status=resolved 的 webhook。
     * 将匹配的活跃告警标记为 RESOLVED；无活跃记录时仅 DEBUG 日志不报错
     * （可能已在超时窗口内自动恢复）。
     * </p>
     */
    private void handleResolvedAlert(String dedupKey) {
        Optional<Alert> existing = alertRepository.findActiveByDedupKey(dedupKey);
        if (existing.isPresent()) {
            Alert alert = existing.get();
            alertRepository.resolve(alert.getId());
            log.info("✅ [AlertService] 告警已恢复 | id={} | dedupKey={}", alert.getId(), dedupKey);
            // WebSocket 广播恢复（非阻塞旁路——推送失败不影响主流程）
            alertNotifier.broadcastResolved(alert);
        } else {
            log.debug("ℹ️ [AlertService] 收到已恢复告警，但无活跃记录 | dedupKey={}", dedupKey);
        }
    }

    // ==================== 新告警创建 ====================

    /**
     * 创建新告警记录 + 自动建单
     * <p>
     * 先落告警再建单，建单失败不阻塞告警入库——告警本体有效，
     * 工单是附属增值。建单失败时 ERROR 日志留存，运维可手动补单。
     * </p>
     */
    private void createNewAlert(AlertmanagerWebhook.Alert incoming,
                                 String alertName, String service,
                                 String severity, String dedupKey) {
        // 推断模块与级别
        String module = inferModule(incoming);
        String level = normalizeLevel(severity);

        // 构建告警实体
        Alert alert = new Alert();
        alert.setSource("prometheus");
        alert.setAlertName(alertName);
        alert.setLevel(level);
        alert.setTitle(buildAlertTitle(alertName, service));
        alert.setDescription(incoming.descriptionText());
        alert.setStatus("FIRING");
        alert.setDedupKey(dedupKey);
        alert.setService(service);
        alert.setModule(module);
        alert.setOccurrenceCount(1);
        alert.setFirstOccurredAt(toLocalDateTime(incoming.getStartsAt()));
        alert.setLastOccurredAt(LocalDateTime.now());
        alert.setCreateTime(LocalDateTime.now());
        alert.setUpdateTime(LocalDateTime.now());

        // 落库告警
        Alert saved = alertRepository.save(alert);
        log.info("🚨 [AlertService] 新告警已入库 | id={} | alertName={} | level={} | service={} | module={}",
                saved.getId(), alertName, level, service, module);

        // WebSocket 广播新告警（非阻塞旁路——推送失败不影响主流程）
        alertNotifier.broadcastNew(saved);

        // 方向 E：告警风暴聚合抑制。窗口内同 service+module 已有已建单的活跃告警时，
        // 新告警关联其工单而不新建单——避免一个故障源（如节点宕机）引发的多条不同告警
        // 各建一张工单刷屏。被抑制的告警仍已入库（上方 save），列表可见（告警可见性铁律），
        // 只是不重复建单、不重复强提醒。
        if (autoTicketEnabled && aggregateEnabled) {
            Optional<Alert> group = alertRepository.findActiveGroupTicket(service, module, aggregateWindowMinutes);
            if (group.isPresent() && group.get().getTicketId() != null) {
                String groupTicketId = group.get().getTicketId();
                alertRepository.updateTicketId(saved.getId(), groupTicketId);
                saved.setTicketId(groupTicketId);
                log.info("🧲 [AlertService] 告警聚合抑制 | id={} | alertName={} | service={} | module={} | 关联工单={} | 窗口={}min",
                        saved.getId(), alertName, service, module, groupTicketId, aggregateWindowMinutes);
                // 关联到组工单：追加活动流 + 聚合通知（不重复强提醒）
                appendAggregatedAlert(groupTicketId, saved, alertName);
                return;
            }
        }

        // 自动建单（Single Writer 契约 6.10：通过 TicketService 写入，不直写 Repository）
        createAutoTicket(saved, alertName, service, module);
    }

    /**
     * 为告警自动创建工单
     * <p>
     * 建单失败的告警仍可在告警列表查看，运维可手动触发建单。
     * 使用 8 参 {@link TicketService#createTicket(String, String, String, String, String, String, String, String)}。
     * </p>
     */
    private void createAutoTicket(Alert alert, String alertName, String service, String module) {
        // 自动建单开关关闭时跳过（devops.alert.auto-ticket-enabled），告警仍入库供列表查看
        if (!autoTicketEnabled) {
            log.info("⏸️ [AlertService] 自动建单已关闭，跳过 | alertId={} | alertName={}", alert.getId(), alertName);
            return;
        }

        try {
            String priority = mapLevelToPriority(alert.getLevel());
            String category = MODULE_TO_CATEGORY.getOrDefault(module, DEFAULT_CATEGORY);
            String sla = mapPriorityToSla(priority);
            String title = "【告警】" + alertName + (service != null && !service.isBlank() ? " - " + service : "");
            String description = alert.getDescription() != null ? alert.getDescription() : title;

            DevOpsTicket ticket = ticketService.createTicket(title, priority, module, description,
                    null, category, sla, alertCreator);

            // 回填工单号：不回填则告警与工单彻底失联——列表页与详情页的「关联工单」
            // 永远显示「—」，运维看到告警却找不到对应工单，自动建单等于白做。
            if (ticket != null && ticket.getId() != null) {
                alertRepository.updateTicketId(alert.getId(), ticket.getId());
                alert.setTicketId(ticket.getId());
            } else {
                log.warn("⚠️ [AlertService] 建单返回空工单号，无法回填关联 | alertId={} | alertName={}",
                        alert.getId(), alertName);
            }

            log.info("🎫 [AlertService] 告警自动建单成功 | alertId={} | alertName={} | ticketId={} | priority={} | category={}",
                    alert.getId(), alertName, ticket != null ? ticket.getId() : null, priority, category);

            // L2 通知（方向二）：高危告警强提醒值班 SRE（蓝图 §二 P0/P1 一键弹窗强提醒）。
            // 旁路——DingTalkNotifier 内部异步 + 失败仅 WARN，不影响建单主流程。
            String ticketId = ticket != null ? ticket.getId() : null;
            notifyAlert(alert, alertName, service, priority, description, ticketId);
        } catch (Exception e) {
            log.error("❌ [AlertService] 告警自动建单失败 | alertId={} | alertName={} | error={}",
                    alert.getId(), alertName, e.getMessage(), e);
        }
    }

    /**
     * 推送告警通知到钉钉
     * <p>P0/P1 高危 → @所有人强提醒；P2~P4 → 普通通知。旁路，不阻塞建单。</p>
     */
    private void notifyAlert(Alert alert, String alertName, String service,
                             String priority, String description, String ticketId) {
        try {
            String title = (alert.isHighRisk() ? "🚨 高危告警 " : "⚠️ 告警 ") + priority + " · " + alertName;
            StringBuilder md = new StringBuilder();
            md.append("### ").append(title).append("\n\n")
              .append("- **级别**：").append(priority).append(alert.isHighRisk() ? "（需人工介入）" : "").append("\n")
              .append("- **服务**：").append(service != null && !service.isBlank() ? service : "—").append("\n")
              .append("- **模块**：").append(alert.getModule() != null ? alert.getModule() : "—").append("\n")
              .append("- **详情**：").append(description != null ? description : "—").append("\n");
            if (ticketId != null) {
                md.append("- **关联工单**：").append(ticketId).append("\n");
            }
            NotifyMessage msg = alert.isHighRisk()
                    ? NotifyMessage.urgent(title, md.toString())
                    : NotifyMessage.normal(title, md.toString());
            notifier.send(msg);
        } catch (Exception e) {
            // 通知构造异常也不影响建单主流程
            log.warn("⚠️ [AlertService] 告警通知构造失败（已忽略）| alertId={} | {}", alert.getId(), e.getMessage());
        }
    }

    /**
     * 聚合关联：把被抑制的告警关联到组工单（方向 E）
     * <p>
     * <b>只记活动流留痕，不推钉钉</b>——这是降噪核心：组内后续告警不再骚扰值班 SRE
     * （首告警建单时已推过组通知）。被抑制的告警本身已入库、列表可见（告警可见性铁律），
     * 工单活动流也留有「关联告警」记录，信息不丢失，只是不重复建单、不重复强提醒。
     * </p>
     * <p>旁路：留痕失败仅 WARN，不影响告警入库与聚合抑制主流程。</p>
     */
    private void appendAggregatedAlert(String ticketId, Alert alert, String alertName) {
        try {
            String detail = "聚合关联告警：" + alertName
                    + (alert.getDescription() != null && !alert.getDescription().isBlank()
                        ? " — " + alert.getDescription() : "");
            ticketService.recordActivity(ticketId, "warning", "关联告警", detail, ALERT_CREATOR, false);
        } catch (Exception e) {
            log.warn("⚠️ [AlertService] 聚合关联留痕失败（已忽略）| ticketId={} | alertId={} | {}",
                    ticketId, alert.getId(), e.getMessage());
        }
    }

    // ==================== 去重键计算 ====================

    /**
     * 计算 SHA-256 去重键
     * <p>
     * 组成：{@code alertName | service | key1=value1 | key2=value2 | ...}
     * </p>
     * <ul>
     *   <li>标签用 {@link TreeMap} 排序保证确定性——{@link HashMap} 迭代顺序不可靠</li>
     *   <li>排除 {@code alertname}、{@code service}、{@code severity}——它们已单独出现在键中或无需参与去重</li>
     *   <li>null 安全：任一字段为 null 时以空字符串替代</li>
     * </ul>
     */
    private String computeDedupKey(String alertName, String service, Map<String, String> labels) {
        StringBuilder sb = new StringBuilder();
        sb.append(alertName != null ? alertName : "");
        sb.append("|").append(service != null ? service : "");

        if (labels != null && !labels.isEmpty()) {
            // TreeMap 保证确定性排序
            Map<String, String> sorted = new TreeMap<>(labels);
            // 排除已在键中单独出现的字段
            sorted.remove("alertname");
            sorted.remove("service");
            sorted.remove("severity");
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                sb.append("|").append(e.getKey()).append("=").append(e.getValue());
            }
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制支持的算法，正常不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ==================== 映射与工具方法 ====================

    /**
     * 从告警标签推断业务模块
     * <p>
     * 优先级：显式 {@code module} 标签 > 默认 OTHER。
     * 后续可扩展按 service 名称模式匹配（如含 "mysql" 则映射 DB）。
     * </p>
     */
    private String inferModule(AlertmanagerWebhook.Alert incoming) {
        String module = incoming.getLabel("module");
        if (module != null && !module.isBlank()) {
            return module.trim().toUpperCase();
        }
        return "OTHER";
    }

    /**
     * 归一化告警级别
     * <p>
     * Alertmanager 常用 {@code severity} 为 {@code critical/warning/info}，
     * 需要映射为项目的 P0-P4 分级体系。
     * </p>
     */
    private String normalizeLevel(String severity) {
        if (severity == null) return "P3";
        String s = severity.trim().toUpperCase();
        // 已经是 P0-P4 格式则直接使用
        if (s.matches("P[0-4]")) return s;
        return switch (s) {
            case "CRITICAL" -> "P0";
            case "WARNING" -> "P2";
            case "INFO" -> "P4";
            default -> "P3";
        };
    }

    /**
     * Level → Priority 映射
     * <p>P0→P0, P1→P1, P2→P2, P3/P4→P3, 未知→P2</p>
     */
    private String mapLevelToPriority(String level) {
        return LEVEL_TO_PRIORITY.getOrDefault(level, TicketEnums.Priority.P2);
    }

    /**
     * Priority → SLA 映射
     * <p>
     * 委托 {@link TicketEnums.Sla#describe} 单一来源。此前本方法自带一套
     * 硬编码字符串，与 {@link TicketService} 里的另一套重复——两处都要改，
     * 漏一处就会出现「告警建单的 SLA 与手动建单不同」的诡异现象。
     * </p>
     */
    private String mapPriorityToSla(String priority) {
        return TicketEnums.Sla.describe(priority);
    }

    /**
     * 构建告警标题
     * <p>格式：{@code 【告警】alertName - service}</p>
     */
    private String buildAlertTitle(String alertName, String service) {
        if (service != null && !service.isBlank()) {
            return "【告警】" + alertName + " - " + service;
        }
        return "【告警】" + alertName;
    }

    /**
     * OffsetDateTime → LocalDateTime（<b>系统默认时区</b>）
     *
     * <h3>为什么不是 UTC</h3>
     * <p>
     * 此前这里写的是 {@code atZoneSameInstant(ZoneOffset.UTC)}，把 Alertmanager
     * 下发的 RFC3339 时间转成 UTC 墙钟再存。但库里 {@code sys_alert.first_occurred_at}
     * 是无时区的 {@code TIMESTAMP}，而<b>同一张表的其它时间列全部是本地时间</b>——
     * {@code last_occurred_at}/{@code create_time} 由 {@code LocalDateTime.now()} 写入，
     * 数据库默认值是 {@code CURRENT_TIMESTAMP}，容器 TZ 固定 {@code Asia/Shanghai}
     * （见 Dockerfile 与 docker-compose）。
     * </p>
     * <p>
     * 一列存 UTC、邻列存 +08:00，两者在同一行里相差 8 小时，而<b>没有任何字段
     * 记录这个差异</b>。所有下游都无从分辨，只能一律按本地时间解释。
     * </p>
     *
     * <h3>用户可见后果</h3>
     * <ul>
     *   <li>告警详情页的「持续时长」把 {@code firstOccurredAt} 与
     *       {@code resolvedAt}/当前时间相减，前者晚 8 小时 →
     *       <b>刚触发的告警显示已持续 8 小时</b>；</li>
     *   <li>处置时间线上「首次发生」排在「已恢复」之后，顺序倒置；</li>
     *   <li>前端 {@code parseDate} 把无时区字符串统一按 {@code +08:00} 解释
     *       （{@code utils/time.ts} 已明确注释「服务器固定 Asia/Shanghai」），
     *       所以列表里的相对时间会显示成「8 小时前」而非「刚刚」。</li>
     * </ul>
     *
     * <h3>为什么用系统默认时区而非硬编码 +08:00</h3>
     * <p>
     * 目标是「与同表其它列口径一致」，而那些列用的是
     * {@code LocalDateTime.now()} 与数据库 {@code CURRENT_TIMESTAMP}——
     * 两者都跟随部署环境的时区。硬编码 +08:00 会在部署到其它时区时
     * 重新制造同一个偏差，而且更隐蔽（本地跑测试正常、线上错 N 小时）。
     * </p>
     *
     * <p>null 安全：入参为 null 时返回当前时间——同样是本地时区，口径一致。</p>
     */
    private LocalDateTime toLocalDateTime(OffsetDateTime odt) {
        if (odt == null) return LocalDateTime.now();
        return odt.atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }
}
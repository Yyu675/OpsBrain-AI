package com.devops.agent.domain.biz.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 运维工单实体
 * <p>
 * 对应数据库表: sys_devops_ticket
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
public class DevOpsTicket implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 工单流水号(格式: TKT-yyyyMMdd-序号)
     */
    private String id;

    /**
     * 工单标题
     */
    private String title;

    /**
     * 优先级: P0 / P1 / P2 / P3
     * <p>B0 起改为四档（原 HIGH/MEDIUM/LOW），对齐 PRD §2.3 分级首响 SLA。</p>
     * @see TicketEnums.Priority
     */
    private String priority;

    /**
     * 故障模块: K8S / ALIYUN_SLB / MYSQL / NETWORK / OTHER
     * @see TicketEnums.Module
     */
    private String module;

    /**
     * 故障描述
     */
    private String description;

    /**
     * 堆栈信息(可选)
     */
    private String stackTrace;

    /**
     * 工单状态: PENDING / PROCESSING / RESOLVED / CLOSED / VOID
     * @see TicketEnums.Status
     */
    private String status;

    /**
     * 来源问答追踪 ID
     */
    private String sourceTraceId;

    /**
     * 负责人
     */
    private String assignee;

    /**
     * 创建人
     */
    private String creator;

    /**
     * 分类(前端使用)
     */
    private String category;

    /**
     * SLA 展示串(如: "30m 响应 / 8h 解决")
     * <p>B0 起由 {@link TicketEnums.Sla#describe} 按优先级派生，不再硬编码。</p>
     */
    private String sla;

    /**
     * 首响截止时刻
     * <p>
     * 建单时按优先级派生并<b>冻结</b>——SLA 时限策略若调整，历史工单的截止时间
     * 不应被追溯改写，否则考核数据失真。
     * </p>
     */
    private LocalDateTime responseDeadline;

    /**
     * 解决截止时刻（MTTR 考核基线）
     */
    private LocalDateTime resolveDeadline;

    /**
     * 首次响应时刻（B1）
     * <p>
     * NULL 表示尚未首响。判定口径见 {@code TicketService.markFirstResponse}——
     * <b>AI 自动回复不算首响</b>，否则建单时自动触发的 AI 分析会让每张工单
     * 建单即「已首响」，首响 SLA 形同虚设。
     * </p>
     */
    private LocalDateTime firstResponseAt;

    /** 首响人 */
    private String firstResponder;

    /**
     * 首响是否已超时
     * <p>由定时扫描置位并持久化，而非每次实时判断——超时是一个既成事实，
     * 一旦发生就应固化留痕，不能因为后来补了首响而让历史超时记录消失。</p>
     */
    private Boolean responseBreached;

    /** 升级时刻（NULL=未升级） */
    private LocalDateTime escalatedAt;

    /** 升级原因 */
    private String escalateReason;

    /**
     * 处置阶段（B2，仅 status=PROCESSING 时有效）
     * <p>TRIAGE排查中 / MITIGATED已止损 / FIXING修复中 / VERIFYING验证中</p>
     */
    private String handlingStage;

    /**
     * 止损完成时刻（业务恢复）
     * <p>MTTM = mitigated_at - create_time。不等同于「已解决」——业务虽恢复，根因可能未定位。</p>
     */
    private LocalDateTime mitigatedAt;

    // ==================== B3 根因分析 + 修复验证 ====================

    /** 人工确认的根因（≠ AI 建议——AI 建议在 sys_ticket_ai_analysis） */
    private String rootCause;

    /** 根因分类（CONFIG/CAPACITY/CODE/DEPENDENCY/NETWORK/DATA/HUMAN/EXTERNAL/UNKNOWN） */
    private String rootCauseCategory;

    /** 根因确认人 */
    private String rootCauseBy;

    /** 根因确认时刻 */
    private LocalDateTime rootCauseAt;

    /** 验证通过时刻（MTTR 终点） */
    private LocalDateTime verifiedAt;

    /** 验证人 */
    private String verifier;

    /** 验证方式（MONITOR/LOG/BUSINESS/MANUAL） */
    private String verifyMethod;

    /** 验证结论 */
    private String verifyConclusion;

    /** 是否跳过验证（D3：必填但允许带理由跳过） */
    private Boolean verifySkipped;

    /** 跳过验证的理由（verify_skipped=true 时必填） */
    private String verifySkipReason;

    /**
     * 标签列表
     * <p>
     * 存于关联表 {@code sys_ticket_tag}，非本表字段。
     * 查询时按需装填，更新时全量替换。
     * </p>
     */
    private java.util.List<String> tags;

    // ==================== SLA 进度（派生字段，不入库）====================

    /**
     * SLA 计时终点
     * <p>
     * 终态工单（已解决/已关闭/已作废）冻结在 {@code updateTime}——SLA 计时已停止，
     * 不应随时间继续增长；进行中的工单算到当前时刻。
     * </p>
     */
    private LocalDateTime slaEndPoint() {
        return isTerminalStatus()
                ? (updateTime != null ? updateTime : LocalDateTime.now())
                : LocalDateTime.now();
    }

    /**
     * 解决时限总分钟数
     * <p>
     * B0 起优先用 {@code resolveDeadline}（建单时冻结的精确时刻）；
     * 仅当该字段为空（迁移前的历史数据）才退化为解析 {@code sla} 串。
     * </p>
     */
    private long resolveBudgetMinutes() {
        if (createTime != null && resolveDeadline != null) {
            return java.time.Duration.between(createTime, resolveDeadline).toMinutes();
        }
        // 退化路径：解析展示串（精度低，仅兼容历史数据）
        int hours = (sla == null || sla.isBlank()) ? 0 : parseResolveHours(sla);
        return (long) hours * 60;
    }

    /**
     * SLA 已消耗百分比（0~100）
     * <p>
     * 由创建时间、当前时间与解决时限推算。
     * 此前该值在前端硬编码为 0，导致进度条恒为 0%、
     * SLA 预警（≥70%）永不触发，整块功能是死代码。
     * </p>
     * <p>
     * 终态工单的进度冻结在 {@code updateTime}，不再随时间增长。
     * </p>
     */
    public int getSlaProgress() {
        if (createTime == null) {
            return 0;
        }
        long budget = resolveBudgetMinutes();
        if (budget <= 0) {
            return 0;
        }

        long elapsedMinutes = java.time.Duration.between(createTime, slaEndPoint()).toMinutes();
        if (elapsedMinutes <= 0) {
            return 0;
        }

        long pct = elapsedMinutes * 100 / budget;

        // 上限 100：超时后显示 100% 而非无意义的 350%
        return (int) Math.min(100, pct);
    }

    /**
     * 距解决截止还剩多少分钟（负数表示已超时的分钟数）
     * <p>
     * 关闭 6.42 遗留限制：此前列表悬浮卡只能显示「已消耗 75%」，
     * 而运维真正需要的是「还剩 45 分钟」——百分比无法转化为行动。
     * 绝对剩余时间必须由后端算（6.15 契约：派生字段在后端算），
     * 前端重复解析 SLA 串会与本类漂移。
     * </p>
     *
     * @return 剩余分钟数；无法计算时返回 null（前端据此隐藏该项而非显示 0）
     */
    public Long getSlaRemainingMinutes() {
        if (createTime == null) return null;
        long budget = resolveBudgetMinutes();
        if (budget <= 0) return null;
        long elapsed = java.time.Duration.between(createTime, slaEndPoint()).toMinutes();
        return budget - elapsed;
    }

    /**
     * SLA 是否已超时
     * <p>与百分比分开提供：进度封顶 100 后无法区分「刚好用完」与「严重超时」。</p>
     */
    public boolean isSlaBreached() {
        Long remaining = getSlaRemainingMinutes();
        return remaining != null && remaining < 0;
    }

    // ==================== B1 首响派生字段 ====================

    /**
     * 是否已首响
     * <p>供前端直接判断，避免各处重复写 {@code firstResponseAt != null}。</p>
     */
    public boolean isFirstResponded() {
        return firstResponseAt != null;
    }

    /**
     * MTTA：首响耗时（分钟）
     * <p>
     * 从建单到首次人工响应。这是考核值班响应速度的核心指标。
     * </p>
     *
     * @return 首响耗时分钟数；尚未首响返回 null（不返回 0——0 意为「秒级响应」，
     *         与「还没响应」是完全不同的事实）
     */
    public Long getFirstResponseMinutes() {
        if (createTime == null || firstResponseAt == null) return null;
        return java.time.Duration.between(createTime, firstResponseAt).toMinutes();
    }

    /**
     * 距首响截止还剩多少分钟（负数=已超时的分钟数）
     * <p>
     * 已首响的工单返回 null——首响完成后这个倒计时就没有意义了，
     * 继续显示「还剩 -30 分钟」会让人误以为仍在超时状态。
     * </p>
     */
    public Long getResponseRemainingMinutes() {
        if (responseDeadline == null) return null;
        if (firstResponseAt != null) return null;   // 已首响，倒计时终止
        if (isTerminalStatus()) return null;        // 终态工单不再计时
        return java.time.Duration.between(LocalDateTime.now(), responseDeadline).toMinutes();
    }

    /**
     * 首响状态（供列表列与速览卡直接展示）
     * <p>
     * 四态：{@code RESPONDED}已首响 / {@code BREACHED}首响超时 /
     * {@code AT_RISK}即将超时(剩余 ≤ 20% 时限) / {@code WAITING}待首响。
     * </p>
     * <p>
     * 由后端计算而非前端派生——遵循 6.15「派生字段在后端算」，
     * 且「即将超时」的阈值属业务规则，不应散落在各前端页面。
     * </p>
     */
    public String getFirstResponseState() {
        if (firstResponseAt != null) return "RESPONDED";
        if (isTerminalStatus()) return "RESPONDED";   // 终态但无首响记录（历史数据），不标超时污染看板
        if (Boolean.TRUE.equals(responseBreached)) return "BREACHED";
        Long remaining = getResponseRemainingMinutes();
        if (remaining == null) return "WAITING";
        if (remaining < 0) return "BREACHED";
        // 剩余不足 20% 时限视为即将超时
        if (createTime != null && responseDeadline != null) {
            long budget = java.time.Duration.between(createTime, responseDeadline).toMinutes();
            if (budget > 0 && remaining * 5 <= budget) return "AT_RISK";
        }
        return "WAITING";
    }

    /**
     * 从 SLA 串解析解决时限（小时）
     * <p>
     * 支持格式：「4h 响应 / 8h 解决」取 8；「24h 响应」无解决时限则退化取响应时限。
     * </p>
     *
     * @return 小时数，无法解析返回 0
     */
    private int parseResolveHours(String slaText) {
        // 优先匹配「解决」前的数字
        java.util.regex.Matcher resolve = java.util.regex.Pattern
                .compile("(\\d+)\\s*h\\s*解决").matcher(slaText);
        if (resolve.find()) {
            return Integer.parseInt(resolve.group(1));
        }
        // 退化：只有响应时限时以其为准，避免整块进度失效
        java.util.regex.Matcher respond = java.util.regex.Pattern
                .compile("(\\d+)\\s*h").matcher(slaText);
        if (respond.find()) {
            return Integer.parseInt(respond.group(1));
        }
        return 0;
    }

    /**
     * 是否终态（SLA 计时已停止）
     * <p>
     * B1 起改为 public：Service 层判断「工单已终结，不允许确认接单/升级」时需要，
     * 让每个调用方各写一遍 status 字符串比较会造成终态定义散落多处而漂移。
     * </p>
     */
    public boolean isTerminalStatus() {
        if (status == null) return false;
        String s = status.toUpperCase();
        return "RESOLVED".equals(s) || "CLOSED".equals(s) || "VOID".equals(s);
    }

    /**
     * 乐观锁版本号（P1-4）
     * <p>
     * 每次更新 +1。更新时以 {@code WHERE id=? AND version=?} 为条件，
     * 受影响行数为 0 表示已被他人修改，需提示用户刷新后重试。
     * </p>
     * <p>
     * 前端提交更新时应回传读取到的 version；未回传则退化为
     * 无锁覆盖（兼容旧客户端，但会丢失并发保护）。
     * </p>
     */
    private Integer version;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    // ==================== Getters & Setters ====================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceTraceId() {
        return sourceTraceId;
    }

    public void setSourceTraceId(String sourceTraceId) {
        this.sourceTraceId = sourceTraceId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSla() {
        return sla;
    }

    public void setSla(String sla) {
        this.sla = sla;
    }

    public LocalDateTime getResponseDeadline() {
        return responseDeadline;
    }

    public void setResponseDeadline(LocalDateTime responseDeadline) {
        this.responseDeadline = responseDeadline;
    }

    public LocalDateTime getResolveDeadline() {
        return resolveDeadline;
    }

    public void setResolveDeadline(LocalDateTime resolveDeadline) {
        this.resolveDeadline = resolveDeadline;
    }

    public LocalDateTime getFirstResponseAt() {
        return firstResponseAt;
    }

    public void setFirstResponseAt(LocalDateTime firstResponseAt) {
        this.firstResponseAt = firstResponseAt;
    }

    public String getFirstResponder() {
        return firstResponder;
    }

    public void setFirstResponder(String firstResponder) {
        this.firstResponder = firstResponder;
    }

    public Boolean getResponseBreached() {
        return responseBreached;
    }

    public void setResponseBreached(Boolean responseBreached) {
        this.responseBreached = responseBreached;
    }

    public LocalDateTime getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(LocalDateTime escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public String getEscalateReason() {
        return escalateReason;
    }

    public void setEscalateReason(String escalateReason) {
        this.escalateReason = escalateReason;
    }

    public String getHandlingStage() {
        return handlingStage;
    }

    public void setHandlingStage(String handlingStage) {
        this.handlingStage = handlingStage;
    }

    public LocalDateTime getMitigatedAt() {
        return mitigatedAt;
    }

    public void setMitigatedAt(LocalDateTime mitigatedAt) {
        this.mitigatedAt = mitigatedAt;
    }

    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }

    public String getRootCauseCategory() { return rootCauseCategory; }
    public void setRootCauseCategory(String rootCauseCategory) { this.rootCauseCategory = rootCauseCategory; }

    public String getRootCauseBy() { return rootCauseBy; }
    public void setRootCauseBy(String rootCauseBy) { this.rootCauseBy = rootCauseBy; }

    public LocalDateTime getRootCauseAt() { return rootCauseAt; }
    public void setRootCauseAt(LocalDateTime rootCauseAt) { this.rootCauseAt = rootCauseAt; }

    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }

    public String getVerifier() { return verifier; }
    public void setVerifier(String verifier) { this.verifier = verifier; }

    public String getVerifyMethod() { return verifyMethod; }
    public void setVerifyMethod(String verifyMethod) { this.verifyMethod = verifyMethod; }

    public String getVerifyConclusion() { return verifyConclusion; }
    public void setVerifyConclusion(String verifyConclusion) { this.verifyConclusion = verifyConclusion; }

    public Boolean getVerifySkipped() { return verifySkipped; }
    public void setVerifySkipped(Boolean verifySkipped) { this.verifySkipped = verifySkipped; }

    public String getVerifySkipReason() { return verifySkipReason; }
    public void setVerifySkipReason(String verifySkipReason) { this.verifySkipReason = verifySkipReason; }

    /**
     * MTTR：解决耗时（分钟）
     * <p>
     * 从建单到验证通过。只对 {@code verify_skipped=false} 的工单有效——
     * 跳过验证的工单不计入 MTTR，另列「跳过验证率」。
     * 否则"点一下已解决"就能刷低 MTTR，考核数据失真（6.41 契约）。
     * </p>
     *
     * @return 解决耗时分钟数；未验证或跳过验证返回 null
     */
    public Long getMttrMinutes() {
        if (createTime == null || verifiedAt == null) return null;
        if (Boolean.TRUE.equals(verifySkipped)) return null;
        return java.time.Duration.between(createTime, verifiedAt).toMinutes();
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public java.util.List<String> getTags() {
        return tags;
    }

    public void setTags(java.util.List<String> tags) {
        this.tags = tags;
    }
}

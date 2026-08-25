package com.devops.agent.domain.biz.service;

import com.devops.agent.common.exception.OptimisticLockException;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketAction;
import com.devops.agent.domain.biz.entity.TicketActivity;
import com.devops.agent.domain.biz.entity.TicketEnums;
import com.devops.agent.domain.biz.entity.TicketReply;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.repository.TicketActivityRepository;
import com.devops.agent.domain.biz.repository.TicketReplyRepository;
import com.devops.agent.domain.biz.repository.TicketQuery;
import com.devops.agent.domain.biz.repository.TicketTagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 运维工单服务
 * <p>
 * 职责: 工单创建、流水号生成、落库
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final DevOpsTicketRepository ticketRepository;
    private final TicketReplyRepository replyRepository;
    private final TicketActivityRepository activityRepository;
    private final TicketTagRepository tagRepository;
    private final com.devops.agent.domain.biz.repository.TicketActionRepository actionRepository;
    private final com.devops.agent.domain.biz.repository.TicketPostmortemRepository postmortemRepository;
    private final com.devops.agent.domain.notify.DingTalkNotifier dingTalkNotifier;
    private final StringRedisTemplate redisTemplate;

    public TicketService(DevOpsTicketRepository ticketRepository,
                        TicketReplyRepository replyRepository,
                        TicketActivityRepository activityRepository,
                        TicketTagRepository tagRepository,
                        com.devops.agent.domain.biz.repository.TicketActionRepository actionRepository,
                        com.devops.agent.domain.biz.repository.TicketPostmortemRepository postmortemRepository,
                        com.devops.agent.domain.notify.DingTalkNotifier dingTalkNotifier,
                        StringRedisTemplate redisTemplate) {
        this.ticketRepository = ticketRepository;
        this.replyRepository = replyRepository;
        this.activityRepository = activityRepository;
        this.tagRepository = tagRepository;
        this.actionRepository = actionRepository;
        this.postmortemRepository = postmortemRepository;
        this.dingTalkNotifier = dingTalkNotifier;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 创建工单
     *
     * @param title         工单标题
     * @param priority      优先级 [HIGH/MEDIUM/LOW]
     * @param module        故障模块 [K8S/ALIYUN_SLB/MYSQL/NETWORK]
     * @param description   故障描述
     * @param stackTrace    堆栈信息(可选)
     * @param sourceTraceId 来源追踪 ID
     * @return 工单流水号
     */
    // 事务：工单 + 标签 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public String saveTicket(String title, String priority, String module, String description, String stackTrace, String sourceTraceId) {
        log.info("📥 [TicketService] 开始创建工单 - title: {}, priority: {}, module: {}, sourceTraceId: {}",
                title, priority, module, sourceTraceId);

        // 生成流水号: TKT-yyyyMMdd-序号
        String ticketId;
        try {
            ticketId = generateTicketId();
            log.debug("✅ [TicketService] 工单流水号生成成功: {}", ticketId);
        } catch (Exception e) {
            log.error("❌ [TicketService] 工单流水号生成失败", e);
            throw new RuntimeException("工单流水号生成失败: " + e.getMessage(), e);
        }

        // 归一化优先级：AI 工具可能传入旧三档或 URGENT 别名，
        // 不归一化会把非法值直接写库，导致排序权重与 SLA 计时都落到兜底分支
        String normalizedPriority = normalizePriority(priority);

        // 根据优先级映射 SLA
        String sla = mapPriorityToSla(normalizedPriority);

        DevOpsTicket ticket = new DevOpsTicket();
        ticket.setId(ticketId);
        ticket.setTitle(title);
        ticket.setPriority(normalizedPriority);
        ticket.setModule(module);
        ticket.setDescription(description);
        ticket.setStackTrace(stackTrace);
        ticket.setStatus("PENDING"); // 初始状态
        ticket.setSourceTraceId(sourceTraceId);
        ticket.setAssignee("待分配"); // 默认待分配
        ticket.setCreator("devops-admin"); // MVP 阶段硬编码
        ticket.setCategory(mapModuleToCategory(module)); // 根据 module 映射 category
        ticket.setSla(sla);
        ticket.setVersion(0);   // P1-4：初始版本号
        ticket.setCreateTime(LocalDateTime.now());
        ticket.setUpdateTime(LocalDateTime.now());
        // B0：AI 建单路径同样冻结 SLA 截止时刻，否则告警/AI 建的单没有计时基线
        applySlaDeadlines(ticket, ticket.getCreateTime());

        log.debug("📦 [TicketService] 工单实体构建完成 - id: {}, assignee: {}, creator: {}, category: {}, sla: {}",
                ticket.getId(), ticket.getAssignee(), ticket.getCreator(), ticket.getCategory(), ticket.getSla());

        // 调用 Repository 保存工单（增加异常捕获）
        try {
            int affectedRows = ticketRepository.save(ticket);
            log.info("💾 [TicketService] 工单入库成功 - ticketId: {}, affectedRows: {}", ticketId, affectedRows);

            if (affectedRows == 0) {
                log.error("⚠️ [TicketService] 工单入库失败：受影响行数为 0 - ticketId: {}", ticketId);
                throw new RuntimeException("工单入库失败：受影响行数为 0");
            }
        } catch (Exception e) {
            log.error("❌ [TicketService] 工单入库异常 - ticketId: {}, error: {}", ticketId, e.getMessage(), e);
            throw new RuntimeException("工单入库失败: " + e.getMessage(), e);
        }

        log.info("🎫 [TicketService] 工单创建成功: {} | 优先级: {} | 模块: {} | 负责人: {}",
                ticketId, priority, module, ticket.getAssignee());

        // 活动流：AI 建单路径需明确标注来源，与人工建单区分
        recordActivity(ticketId, "primary", "AI 自动创建工单",
                "分类为「" + ticket.getCategory() + "」，优先级「"
                        + priorityLabel(priority) + "」", "AI 助手", true);
        if (ticket.getAssignee() != null && !"待分配".equals(ticket.getAssignee())) {
            recordActivity(ticketId, "primary", "负责人分配",
                    ticket.getAssignee(), "系统自动", true);
        }

        return ticketId;
    }

    /**
     * 手动创建工单（前端表单入口）
     * <p>
     * 与 {@link #saveTicket} 的差异：本方法供人工填单使用，
     * 允许显式指定负责人与分类，且 {@code sourceTraceId} 为空
     * （非 AI 生成，无关联会话）。
     * </p>
     * <p>对应 CLAUDE.md 6.2 决策：工单创建双入口（AI 对话 + 手动表单）。</p>
     *
     * @param title       标题
     * @param priority    优先级 HIGH/MEDIUM/LOW
     * @param module      故障模块
     * @param description 问题描述
     * @param assignee    负责人，空则「待分配」
     * @param category    分类，空则按 module 推导
     * @param sla         SLA，空则按优先级推导
     * @param creator     创建人，空则「devops-admin」
     * @return 创建后的完整工单
     */
    public DevOpsTicket createTicket(String title, String priority, String module,
                                     String description, String assignee,
                                     String category, String sla, String creator) {
        return createTicket(title, priority, module, description, assignee, category, sla, creator, null);
    }

    /**
     * 手动创建工单（含标签）
     *
     * @param tags 标签列表，可为 null
     */
    public DevOpsTicket createTicket(String title, String priority, String module,
                                     String description, String assignee,
                                     String category, String sla, String creator,
                                     List<String> tags) {
        // 入参校验：标题与描述是工单可处理的最低信息量
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("工单标题不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("问题描述不能为空");
        }

        String normalizedPriority = normalizePriority(priority);
        String normalizedModule = (module == null || module.isBlank()) ? "OTHER" : module.trim().toUpperCase();

        String ticketId = generateTicketId();

        DevOpsTicket ticket = new DevOpsTicket();
        ticket.setId(ticketId);
        ticket.setTitle(title.trim());
        ticket.setPriority(normalizedPriority);
        ticket.setModule(normalizedModule);
        ticket.setDescription(description.trim());
        ticket.setStackTrace(null);
        ticket.setStatus("PENDING");
        ticket.setSourceTraceId(null);  // 手动创建无关联会话
        ticket.setAssignee((assignee == null || assignee.isBlank()) ? "待分配" : assignee.trim());
        ticket.setCreator((creator == null || creator.isBlank()) ? "devops-admin" : creator.trim());
        ticket.setCategory((category == null || category.isBlank())
                ? mapModuleToCategory(normalizedModule) : category.trim());
        ticket.setSla((sla == null || sla.isBlank()) ? mapPriorityToSla(normalizedPriority) : sla.trim());
        ticket.setVersion(0);   // P1-4：初始版本号，前端据此做后续并发校验
        ticket.setTags(tags);   // 标签在 save 后写入关联表
        ticket.setCreateTime(LocalDateTime.now());
        ticket.setUpdateTime(LocalDateTime.now());
        // B0：按优先级冻结 SLA 截止时刻，供首响超时扫描与 MTTR 统计
        applySlaDeadlines(ticket, ticket.getCreateTime());

        int rows = ticketRepository.save(ticket);
        if (rows == 0) {
            throw new IllegalStateException("工单入库失败，受影响行数为 0");
        }

        // 标签持久化：此前用户输入的标签在提交时被丢弃
        if (ticket.getTags() != null && !ticket.getTags().isEmpty()) {
            int requested = ticket.getTags().size();
            int written = tagRepository.replaceTags(ticket.getId(), ticket.getTags());
            List<String> actual = tagRepository.findByTicketId(ticket.getId());
            ticket.setTags(actual);

            // 标签写入失败不回滚工单——工单本体有效，
            // 因附属元数据失败而丢弃它代价更大。
            // 但必须记 ERROR 而非静默：用户提交了标签却没存上是数据丢失，
            // 前端会比对提交值与返回值并提示用户。
            if (actual.isEmpty() && requested > 0) {
                log.error("🚨 [TicketService] 标签全部写入失败 | ticketId={} | 提交={} 个 | 工单已创建但标签丢失",
                        ticket.getId(), requested);
            } else if (actual.size() < written) {
                log.warn("⚠️ [TicketService] 标签部分写入 | ticketId={} | 提交={} 实存={}",
                        ticket.getId(), requested, actual.size());
            }
        }

        // 活动流：工单创建是时间线起点
        recordActivity(ticket.getId(), "gray", "工单创建", null,
                ticket.getCreator(), false);
        if (ticket.getAssignee() != null && !ticket.getAssignee().isBlank()
                && !"待分配".equals(ticket.getAssignee())) {
            recordActivity(ticket.getId(), "primary", "负责人分配",
                    ticket.getAssignee(), ticket.getCreator(), true);
        }

        log.info("🎫 [TicketService] 手动创建工单成功 | ticketId={} | 优先级={} | 模块={} | 负责人={}",
                ticketId, normalizedPriority, normalizedModule, ticket.getAssignee());
        return ticket;
    }

    /**
     * 更新工单（P1-4 乐观锁）
     * <p>
     * 采用「读取-合并-写回」：仅覆盖传入的非空字段，
     * 避免前端漏传字段导致数据被清空。
     * </p>
     * <p>
     * <b>并发控制</b>：{@code patch.version} 非空时启用 CAS——
     * 若数据库版本已变，抛 {@link OptimisticLockException} 而非静默覆盖。
     * 为空时退化为无锁覆盖（兼容不传版本的调用方）。
     * </p>
     *
     * @param ticketId 工单号
     * @param patch    待更新字段（null 表示不改该字段；version 用于并发校验）
     * @return 更新后的完整工单
     * @throws IllegalStateException     工单不存在
     * @throws OptimisticLockException   版本冲突（已被他人修改）
     */
    // 事务：工单 + 标签 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket updateTicket(String ticketId, DevOpsTicket patch) {
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }

        // 版本预检：客户端持有的版本与库中不一致时立即拒绝，
        // 不浪费后续合并计算，也让冲突提示更准确
        Integer clientVersion = patch.getVersion();
        if (clientVersion != null && !clientVersion.equals(existing.getVersion())) {
            log.warn("⚠️ [TicketService] 版本冲突 | ticketId={} | 客户端={} | 当前={}",
                    ticketId, clientVersion, existing.getVersion());
            throw new OptimisticLockException(ticketId, clientVersion, existing.getVersion());
        }

        // 合并前留存快照，供活动流描述具体变化了哪些字段
        DevOpsTicket before = snapshotOf(existing);

        // 合并：仅覆盖非空字段
        if (patch.getTitle() != null && !patch.getTitle().isBlank()) {
            existing.setTitle(patch.getTitle().trim());
        }
        if (patch.getDescription() != null && !patch.getDescription().isBlank()) {
            existing.setDescription(patch.getDescription().trim());
        }
        if (patch.getPriority() != null && !patch.getPriority().isBlank()) {
            String newPriority = normalizePriority(patch.getPriority());
            existing.setPriority(newPriority);
            // 优先级变更时，若前端未显式指定 SLA，则按新优先级重算
            if (patch.getSla() == null || patch.getSla().isBlank()) {
                existing.setSla(mapPriorityToSla(newPriority));
            }
            // B0：SLA 截止时刻同步重算。仍以建单时刻为基准——
            // 用当前时刻会把已消耗的时间一笔勾销（详见 applySlaDeadlines 说明）
            applySlaDeadlines(existing, existing.getCreateTime());
        }
        if (patch.getModule() != null && !patch.getModule().isBlank()) {
            existing.setModule(patch.getModule().trim().toUpperCase());
        }
        if (patch.getStatus() != null && !patch.getStatus().isBlank()) {
            existing.setStatus(patch.getStatus().trim().toUpperCase());
        }
        if (patch.getAssignee() != null && !patch.getAssignee().isBlank()) {
            existing.setAssignee(patch.getAssignee().trim());
        }
        if (patch.getCategory() != null && !patch.getCategory().isBlank()) {
            existing.setCategory(patch.getCategory().trim());
        }
        if (patch.getSla() != null && !patch.getSla().isBlank()) {
            existing.setSla(patch.getSla().trim());
        }
        if (patch.getStackTrace() != null) {
            existing.setStackTrace(patch.getStackTrace());
        }

        // 传入版本号以启用 SQL 层 CAS。
        // 预检与 CAS 双保险：预检给出精确的版本号对比信息，
        // CAS 拦住预检之后到 UPDATE 之前这个时间窗内的并发写。
        existing.setVersion(clientVersion);

        int rows = ticketRepository.update(existing);
        if (rows == 0) {
            if (clientVersion != null) {
                // 落在预检与 UPDATE 之间的并发写：重查当前版本给出准确提示
                DevOpsTicket latest = ticketRepository.findById(ticketId);
                throw new OptimisticLockException(ticketId, clientVersion,
                        latest != null ? latest.getVersion() : null);
            }
            throw new IllegalStateException("工单更新失败，受影响行数为 0: " + ticketId);
        }

        log.info("✏️ [TicketService] 工单已更新 | ticketId={} | version={} → {}",
                ticketId, clientVersion, clientVersion != null ? clientVersion + 1 : "?");

        // 活动流：记录实际变化的字段，而非笼统的"已更新"
        String changes = describeChanges(before, existing);
        if (changes != null) {
            recordActivity(ticketId, "primary", "工单编辑", changes, "当前用户", false);
        }

        // 标签：patch.tags 非 null 才替换。
        // 区分「不传」（保持原样）与「传空列表」（清空全部标签）。
        if (patch.getTags() != null) {
            replaceTags(ticketId, patch.getTags());
        }

        return getTicketWithTags(ticketId);
    }

    /**
     * 浅拷贝工单快照
     * <p>仅复制活动流对比所需字段，不复制时间戳与版本号。</p>
     */
    private DevOpsTicket snapshotOf(DevOpsTicket src) {
        DevOpsTicket s = new DevOpsTicket();
        s.setTitle(src.getTitle());
        s.setPriority(src.getPriority());
        s.setModule(src.getModule());
        s.setDescription(src.getDescription());
        s.setAssignee(src.getAssignee());
        s.setSla(src.getSla());
        s.setStatus(src.getStatus());
        return s;
    }

    /**
     * 描述字段变化
     * <p>
     * 活动流要能回答"改了什么"，只写"工单已更新"对排查毫无价值。
     * </p>
     *
     * @return 变化描述，无变化返回 null
     */
    private String describeChanges(DevOpsTicket before, DevOpsTicket after) {
        List<String> diffs = new java.util.ArrayList<>();
        if (!Objects.equals(before.getTitle(), after.getTitle())) {
            diffs.add("标题");
        }
        if (!Objects.equals(before.getPriority(), after.getPriority())) {
            diffs.add("优先级 " + priorityLabel(before.getPriority())
                    + " → " + priorityLabel(after.getPriority()));
        }
        if (!Objects.equals(before.getModule(), after.getModule())) {
            diffs.add("模块 " + before.getModule() + " → " + after.getModule());
        }
        if (!Objects.equals(before.getDescription(), after.getDescription())) {
            diffs.add("描述");
        }
        if (!Objects.equals(before.getAssignee(), after.getAssignee())) {
            diffs.add("负责人 " + before.getAssignee() + " → " + after.getAssignee());
        }
        if (!Objects.equals(before.getSla(), after.getSla())) {
            diffs.add("SLA " + before.getSla() + " → " + after.getSla());
        }
        return diffs.isEmpty() ? null : String.join("，", diffs);
    }

    /**
     * 变更工单状态
     *
     * @param ticketId 工单号
     * @param status   目标状态 PENDING/PROCESSING/RESOLVED/CLOSED/VOID
     * @return 更新后的工单
     */
    // 事务：工单 + 活动流 + 首响。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket updateStatus(String ticketId, String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("状态不能为空");
        }
        String target = TicketEnums.Status.normalize(status);
        if (!TicketEnums.Status.isValid(status)) {
            throw new IllegalArgumentException(
                    "非法工单状态: " + status + "，合法值为 " + TicketEnums.Status.ALL);
        }
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }

        // 幂等：状态相同直接返回，不报错
        if (target.equals(existing.getStatus())) {
            log.debug("ℹ️ [TicketService] 状态未变化，跳过 | ticketId={} | status={}", ticketId, target);
            return existing;
        }

        // 流转合法性校验。
        // 此前只校验「目标值是不是合法枚举」，不校验「能不能从当前状态走过去」——
        // 于是 CLOSED 可以被改回 PENDING、VOID（作废）可以被复活，
        // 导致 SLA 统计、首响计时、复盘归档全部失真，且无任何报错。
        if (!TicketEnums.Status.canTransition(existing.getStatus(), target)) {
            throw new IllegalStateException(String.format(
                    "非法状态流转：%s → %s。当前状态允许流转到 %s",
                    statusLabel(existing.getStatus()), statusLabel(target),
                    TicketEnums.Status.nextStates(existing.getStatus())));
        }

        ticketRepository.updateStatus(ticketId, target);
        log.info("🔄 [TicketService] 工单状态变更 | ticketId={} | {} → {}",
                ticketId, existing.getStatus(), target);

        // 活动流留痕：状态变更是工单生命周期的关键节点
        recordActivity(ticketId,
                statusColor(target),
                "状态变更",
                statusLabel(existing.getStatus()) + " → " + statusLabel(target),
                "当前用户", false);

        // B1：PENDING → PROCESSING 即视为首响（有人开始处理了）。
        // 幂等由 SQL 的 first_response_at IS NULL 保证，重复调用安全
        if (TicketEnums.Status.PENDING.equals(existing.getStatus())
                && TicketEnums.Status.PROCESSING.equals(target)) {
            markFirstResponse(ticketId, existing.getAssignee());
        }

        return ticketRepository.findById(ticketId);
    }

    /**
     * 转派工单
     *
     * @param ticketId 工单号
     * @param assignee 新负责人
     * @return 更新后的工单
     */
    // 事务：工单 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket transferTicket(String ticketId, String assignee) {
        if (assignee == null || assignee.isBlank()) {
            throw new IllegalArgumentException("负责人不能为空");
        }
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }

        ticketRepository.updateAssignee(ticketId, assignee.trim());
        log.info("👤 [TicketService] 工单转派 | ticketId={} | {} → {}",
                ticketId, existing.getAssignee(), assignee.trim());

        // 活动流留痕：转派需高亮，责任转移是问责关键
        recordActivity(ticketId, "primary", "工单转派",
                (existing.getAssignee() == null ? "未分配" : existing.getAssignee())
                        + " → " + assignee.trim(),
                "当前用户", true);

        return ticketRepository.findById(ticketId);
    }

    /**
     * 删除工单
     * <p>
     * ⚠️ 物理删除不可逆。业务上建议优先用 {@link #voidTicket} 作废，
     * 保留审计痕迹。本方法供前端「删除」操作使用（含 5 秒撤销窗口，
     * 撤销由前端重新创建实现）。
     * </p>
     *
     * @param ticketId 工单号
     * @return 被删除的工单（供前端撤销时回填）
     * @throws IllegalStateException 工单不存在
     */
    // 事务：工单 + 回复 + 活动流 + 标签 + 处置 + 复盘（6 张表）。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket deleteTicket(String ticketId) {
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }

        int rows = ticketRepository.deleteById(ticketId);
        if (rows == 0) {
            throw new IllegalStateException("工单删除失败，受影响行数为 0: " + ticketId);
        }

        // 级联清理子表：工单已物理删除，回复/活动流/标签/处置动作/复盘失去归属对象，
        // 不清理会积累孤儿数据（表无外键约束，需应用层保证）
        int replies = replyRepository.deleteByTicketId(ticketId);
        int activities = activityRepository.deleteByTicketId(ticketId);
        int tags = tagRepository.deleteByTicketId(ticketId);
        int actions = actionRepository.deleteByTicketId(ticketId);
        int postmortem = postmortemRepository.deleteByTicketId(ticketId);

        log.warn("🗑️ [TicketService] 工单已删除 | ticketId={} | title={} | 级联清理 回复={} 活动={} 标签={} 动作={} 复盘={}",
                ticketId, existing.getTitle(), replies, activities, tags, actions, postmortem);
        // 注：附件清理由 TicketController 调用 TicketAttachmentService 完成。
        // 不在此处直接注入：TicketAttachmentService 依赖 TicketService（记活动流），
        // 反向注入会形成循环依赖。
        return existing;
    }

    /**
     * 归一化优先级
     * <p>
     * 委托给 {@link TicketEnums.Priority#normalize}——此前本方法自带一套三档
     * switch，与枚举类重复定义，改档位时必然漏改一处而漂移。
     * 遵循 6.20「同一事实只允许一处定义」。
     * </p>
     */
    private String normalizePriority(String priority) {
        if (TicketEnums.Priority.isLegacyValue(priority)) {
            log.warn("⚠️ [TicketService] 收到旧三档优先级，已按兼容映射转换 | 入参={} | 结果={}",
                    priority, TicketEnums.Priority.normalize(priority));
        }
        return TicketEnums.Priority.normalize(priority);
    }

    /**
     * 按优先级派生并写入 SLA 截止时刻
     * <p>
     * 以 {@code baseTime}（建单时刻）为基准，加上 {@link TicketEnums.Sla} 定义的时限。
     * </p>
     * <p>
     * <b>为何以建单时刻而非当前时刻为基准</b>：SLA 考核的是「从工单产生到响应/解决」
     * 的耗时。若优先级中途调整时用当前时刻重算，等于把已消耗的时间一笔勾销——
     * 一张已挂 3 小时的工单改优先级后会显示「SLA 消耗 0%」，考核数据失真。
     * </p>
     */
    private void applySlaDeadlines(DevOpsTicket ticket, LocalDateTime baseTime) {
        String p = ticket.getPriority();
        LocalDateTime base = baseTime != null ? baseTime : LocalDateTime.now();
        ticket.setResponseDeadline(base.plusMinutes(TicketEnums.Sla.responseMinutes(p)));
        ticket.setResolveDeadline(base.plusMinutes(TicketEnums.Sla.resolveMinutes(p)));
    }

    // ==================== B1 首响 / 升级 ====================

    /**
     * 记录首响（幂等，多触发点共用）
     * <p>
     * 触发点（任一即可，取最早时刻）：
     * <ol>
     *   <li>状态 {@code PENDING → PROCESSING}</li>
     *   <li>首次添加非 AI 回复</li>
     *   <li>显式「确认接单」（{@link #acknowledgeTicket}）</li>
     * </ol>
     * </p>
     * <p>
     * <b>AI 回复不算首响</b>：AI 分析在建单时自动触发（6.39），若计入则每张工单
     * 建单即「已首响」，首响 SLA 形同虚设——与 6.24 P1-4「配额 key 用 traceId
     * 导致永不累积」是同一类「指标被自动行为稀释」的错误。
     * </p>
     * <p>
     * 幂等由 SQL 的 {@code first_response_at IS NULL} 条件保证，
     * 故本方法可安全地在多处无条件调用。
     * </p>
     *
     * @param responder 首响人；空则记为「未知」而非留空（便于追溯）
     * @return true=本次即首响（调用方可据此记活动流）
     */
    public boolean markFirstResponse(String ticketId, String responder) {
        String who = (responder == null || responder.isBlank()) ? "未知" : responder.trim();
        try {
            int rows = ticketRepository.markFirstResponse(ticketId, who, LocalDateTime.now());
            if (rows > 0) {
                DevOpsTicket t = ticketRepository.findById(ticketId);
                Long mtta = t != null ? t.getFirstResponseMinutes() : null;
                recordActivity(ticketId, "success", "首次响应",
                        who + " 首次响应" + (mtta != null ? "，耗时 " + mtta + " 分钟" : ""),
                        who, false);
                return true;
            }
        } catch (Exception e) {
            // 首响记录是旁路数据，失败不应中断主业务（状态变更/回复本身已成功）
            log.warn("⚠️ [TicketService] 首响记录失败（不影响主流程）| ticketId={} | {}",
                    ticketId, e.getMessage());
        }
        return false;
    }

    /**
     * 确认接单（显式首响）
     * <p>
     * 对应告警侧的 ACKNOWLEDGED 语义。若工单仍是 PENDING，同时推进为 PROCESSING——
     * 「已确认接单但状态还是待处理」是自相矛盾的状态。
     * </p>
     *
     * @param assignee 可选：确认的同时接单给自己
     * @return 更新后的工单
     */
    // 事务：本方法内部串联 markFirstResponse + transferTicket + updateStatus，
    // 最多写 4 张表（工单、活动流 ×3）。
    //
    // 这里的 @Transactional 是**必须**的，原因不只是「多表要原子」：
    // transferTicket 与 updateStatus 各自都标了 @Transactional，但它们是被
    // this.xxx() 直接调用的——Spring 的事务由 AOP 代理织入，
    // 自调用不经过代理，那两个注解在这条路径上**完全不生效**。
    //
    // 也就是说，修复前这里是「三次独立的自动提交写入」：
    // 转派成功但状态变更失败时，工单会停在
    // 「负责人已改、状态仍是待处理、活动流只留了转派记录」的半截状态，
    // 而调用方收到异常会以为整个操作都没发生。
    //
    // 加在最外层方法上之后，内层自调用共用同一个事务，任一步失败整体回滚。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket acknowledgeTicket(String ticketId, String responder, String assignee) {
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }
        if (existing.isTerminalStatus()) {
            throw new IllegalStateException("工单已终结，无法确认接单: " + ticketId);
        }

        boolean isFirst = markFirstResponse(ticketId, responder);

        // 顺带认领：仅在显式传入且与当前不同时才改，避免无谓的版本变动
        if (assignee != null && !assignee.isBlank()
                && !assignee.trim().equals(existing.getAssignee())) {
            transferTicket(ticketId, assignee.trim());
        }

        // 仍是待处理则推进为处理中——确认接单意味着已开始处理
        if (TicketEnums.Status.PENDING.equals(existing.getStatus())) {
            updateStatus(ticketId, TicketEnums.Status.PROCESSING);
        }

        log.info("👋 [TicketService] 确认接单 | ticketId={} | responder={} | 本次即首响={}",
                ticketId, responder, isFirst);
        return ticketRepository.findById(ticketId);
    }

    /**
     * 升级工单
     * <p>
     * L1 阶段只记录 + 留痕 + 通知，<b>不自动改优先级或换负责人</b>——
     * 那属于 L3 审批工作流范畴（6.3 决策：高风险需人工审批）。
     * 自动提优先级会让 SLA 时限被动改写，绕过人的判断。
     * </p>
     *
     * @param reason 升级原因，必填——无理由的升级无法追溯，也无法据此改进流程
     */
    // 事务：工单 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket escalateTicket(String ticketId, String reason, String operator) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("升级原因不能为空");
        }
        if (reason.length() > 255) {
            throw new IllegalArgumentException("升级原因过长（上限 255 字）");
        }
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }

        String who = (operator == null || operator.isBlank()) ? "未知" : operator.trim();
        ticketRepository.markEscalated(ticketId, reason.trim(), LocalDateTime.now());

        recordActivity(ticketId, "warning", "工单升级", reason.trim(), who, true);
        log.warn("⬆️ [TicketService] 工单已升级 | ticketId={} | operator={} | reason={}",
                ticketId, who, reason.trim());

        // L2 钉钉通知（方向二）：升级上报是「需要更多人关注」的强信号，@所有人。
        // 旁路——DingTalkNotifier 内部异步 + 失败仅 WARN，不影响升级主流程。
        // 只在升级这一个工单事件推送：普通状态流转有活动流+列表可见即可，
        // 群机器人 @所有人 不适合逐条状态变更（会刷屏）。
        try {
            String title = "⬆️ 工单升级 " + existing.getPriority() + " · " + existing.getTitle();
            String md = "### " + title + "\n\n"
                    + "- **工单**：" + ticketId + "\n"
                    + "- **优先级**：" + existing.getPriority() + "\n"
                    + "- **升级人**：" + who + "\n"
                    + "- **升级原因**：" + reason.trim() + "\n"
                    + "- **负责人**：" + (existing.getAssignee() != null ? existing.getAssignee() : "待分配") + "\n";
            dingTalkNotifier.send(com.devops.agent.domain.notify.NotifyMessage.urgent(title, md));
        } catch (Exception e) {
            log.warn("⚠️ [TicketService] 升级通知构造失败（已忽略）| ticketId={} | {}", ticketId, e.getMessage());
        }
        return ticketRepository.findById(ticketId);
    }

    /**
     * 查询 SLA 风险清单
     *
     * @param withinMinutes 前瞻窗口（分钟），0=只看已超时
     */
    public List<DevOpsTicket> findSlaAtRisk(int withinMinutes, int limit) {
        int safeWindow = Math.max(0, withinMinutes);
        int safeLimit = Math.min(Math.max(1, limit), 200);
        List<DevOpsTicket> list = ticketRepository.findSlaAtRisk(safeWindow, safeLimit);
        fillTags(list);
        return list;
    }

    /** 首响统计（供看板 MTTA） */
    public java.util.Map<String, Object> getFirstResponseStats() {
        return ticketRepository.countFirstResponseStats();
    }

    // ==================== B2 现场处置 ====================

    /** 处置阶段常量（对齐 D2 决策） */
    public static final String STAGE_TRIAGE = "TRIAGE";
    public static final String STAGE_MITIGATED = "MITIGATED";
    public static final String STAGE_FIXING = "FIXING";
    public static final String STAGE_VERIFYING = "VERIFYING";

    private static final java.util.Set<String> VALID_STAGES = java.util.Set.of(
            STAGE_TRIAGE, STAGE_MITIGATED, STAGE_FIXING, STAGE_VERIFYING);

    /** 动作类型常量 */
    public static final String ACTION_MITIGATE = "MITIGATE";
    public static final String ACTION_INVESTIGATE = "INVESTIGATE";
    public static final String ACTION_FIX = "FIX";
    public static final String ACTION_ROLLBACK = "ROLLBACK";
    public static final String ACTION_VERIFY = "VERIFY";

    /**
     * 记录处置动作
     * <p>
     * <b>{@code effective} 允许为 false</b>：PRD §2.1 排查占 40% 且严重依赖经验——
     * 失败尝试（「试过重启，没用」）恰恰是最有价值的知识，能避免后人重走弯路。
     * 只记成功动作等于丢弃大部分经验。
     * </p>
     *
     * @return 入库后的动作（含 id）
     */
    // 事务：处置记录 + 工单 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public TicketAction addAction(String ticketId, String actionType, String summary,
                                 String detail, String operator, Boolean effective) {
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }
        if (existing.isTerminalStatus()) {
            throw new IllegalStateException("工单已终结，无法记录处置动作: " + ticketId);
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("处置摘要不能为空");
        }
        if (summary.length() > 255) {
            throw new IllegalArgumentException("处置摘要过长（上限 255 字）");
        }

        TicketAction action = new TicketAction();
        action.setTicketId(ticketId);
        action.setActionType(actionType != null ? actionType.trim().toUpperCase() : ACTION_INVESTIGATE);
        action.setSummary(summary.trim());
        action.setDetail(detail);
        action.setOperator((operator == null || operator.isBlank()) ? "未知" : operator.trim());
        action.setEffective(effective);
        action.setStartedAt(LocalDateTime.now());

        Long id = actionRepository.insert(action);
        action.setId(id);

        // 活动流留痕（6.12 契约：后端单点写）
        String effLabel = effective == null ? "" : (effective ? "（有效）" : "（无效）");
        recordActivity(ticketId, "primary", actionLabel(action.getActionType()),
                summary.trim() + effLabel, action.getOperator(), false);

        log.info("🔧 [TicketService] 处置动作已记录 | ticketId={} | type={} | operator={}",
                ticketId, action.getActionType(), action.getOperator());
        return action;
    }

    /**
     * 查询工单的处置动作列表（时间正序）
     */
    public List<TicketAction> listActions(String ticketId) {
        return actionRepository.findByTicketId(ticketId);
    }

    /**
     * 切换处置阶段
     * <p>
     * 阶段可<b>跳跃与回退</b>（排查中直接止损、修复后验证失败退回 FIXING）——
     * 真实运维不是线性的，强制线性会让用户绕过系统。
     * </p>
     * <p>
     * 若工单仍为 PENDING，同时推进为 PROCESSING——处置阶段只在处理中才有意义。
     * </p>
     */
    // 事务：工单 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket updateStage(String ticketId, String stage, String operator) {
        String s = stage != null ? stage.trim().toUpperCase() : "";
        if (!VALID_STAGES.contains(s)) {
            throw new IllegalArgumentException("非法处置阶段: " + stage + "，合法值为 " + VALID_STAGES);
        }
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }
        if (existing.isTerminalStatus()) {
            throw new IllegalStateException("工单已终结，无法切换处置阶段: " + ticketId);
        }

        // 若仍是 PENDING，同时推进为 PROCESSING
        if (TicketEnums.Status.PENDING.equals(existing.getStatus())) {
            ticketRepository.updateStatus(ticketId, TicketEnums.Status.PROCESSING);
            markFirstResponse(ticketId, existing.getAssignee());
        }

        existing.setHandlingStage(s);

        // 标记止损时刻：进入 MITIGATED 时记一次，仅在尚未记过时写
        if (STAGE_MITIGATED.equals(s) && existing.getMitigatedAt() == null) {
            existing.setMitigatedAt(LocalDateTime.now());
        }

        ticketRepository.update(existing);

        recordActivity(ticketId, "primary", "处置阶段变更",
                stageLabel(existing.getHandlingStage()), operator != null ? operator : "当前用户", false);

        log.info("🔄 [TicketService] 处置阶段变更 | ticketId={} | stage={}", ticketId, s);
        return ticketRepository.findById(ticketId);
    }

    /**
     * 标记已止损（业务恢复）
     * <p>
     * 这通常不等同于「已解决」——业务虽恢复，根因可能尚未定位。
     * MTTM（止损耗时）= mitigated_at - create_time。
     * </p>
     */
    // 事务：工单 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket markMitigated(String ticketId, String operator) {
        return updateStage(ticketId, STAGE_MITIGATED, operator);
    }

    private String actionLabel(String type) {
        return switch (type) {
            case ACTION_MITIGATE -> "止损";
            case ACTION_INVESTIGATE -> "排查";
            case ACTION_FIX -> "修复";
            case ACTION_ROLLBACK -> "回滚";
            case ACTION_VERIFY -> "验证";
            default -> type;
        };
    }

    private String stageLabel(String stage) {
        return switch (stage) {
            case STAGE_TRIAGE -> "排查中";
            case STAGE_MITIGATED -> "已止损";
            case STAGE_FIXING -> "修复中";
            case STAGE_VERIFYING -> "验证中";
            default -> stage;
        };
    }

    // ==================== B3 根因分析 + 修复验证 ====================

    /** 根因分类词表（供聚合分析"哪类根因最多"，指导系统性改进） */
    public static final java.util.Set<String> ROOT_CAUSE_CATEGORIES = java.util.Set.of(
            "CONFIG", "CAPACITY", "CODE", "DEPENDENCY",
            "NETWORK", "DATA", "HUMAN", "EXTERNAL", "UNKNOWN"
    );

    /** 验证方式词表 */
    public static final java.util.Set<String> VERIFY_METHODS = java.util.Set.of(
            "MONITOR", "LOG", "BUSINESS", "MANUAL"
    );

    /**
     * 确认根因
     * <p>
     * 这是<b>人工确认</b>的根因，≠ AI 建议——AI 建议在 {@code sys_ticket_ai_analysis}
     * 里是参考材料，运维人员需要据此（或自行判断）确认最终根因。
     * </p>
     *
     * @param category 根因分类（CONFIG/CAPACITY/CODE/.../UNKNOWN），空则 UNKNOWN
     */
    // 事务：工单 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket confirmRootCause(String ticketId, String rootCause,
                                        String category, String operator) {
        if (rootCause == null || rootCause.isBlank()) {
            throw new IllegalArgumentException("根因不能为空");
        }
        if (rootCause.length() > 10000) {
            throw new IllegalArgumentException("根因内容过长");
        }
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }

        String cat = (category == null || category.isBlank())
                ? "UNKNOWN" : category.trim().toUpperCase();
        if (!ROOT_CAUSE_CATEGORIES.contains(cat)) {
            throw new IllegalArgumentException(
                    "非法根因分类: " + category + "，合法值为 " + ROOT_CAUSE_CATEGORIES);
        }
        String who = (operator == null || operator.isBlank()) ? "未知" : operator.trim();

        existing.setRootCause(rootCause.trim());
        existing.setRootCauseCategory(cat);
        existing.setRootCauseBy(who);
        existing.setRootCauseAt(LocalDateTime.now());

        ticketRepository.update(existing);

        recordActivity(ticketId, "success", "根因确认",
                cat + "：" + (rootCause.length() > 60 ? rootCause.substring(0, 60) + "…" : rootCause),
                who, true);

        log.info("🔍 [TicketService] 根因已确认 | ticketId={} | category={} | by={}",
                ticketId, cat, who);
        return ticketRepository.findById(ticketId);
    }

    /**
     * 提交修复验证
     * <p>
     * D3 决策：必填但允许带理由跳过。通过验证则同时转 RESOLVED——
     * 验证通过意味着问题已确认解决，状态应反映这一事实。
     * </p>
     * <p>
     * <b>MTTR 终点</b>：verified_at 即 MTTR 的截止时刻。
     * 跳过验证的工单不计入 MTTR（6.41 契约）。
     * </p>
     */
    // 事务：工单 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket submitVerification(String ticketId, String method, String conclusion,
                                          String verifier) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("验证方式不能为空");
        }
        String m = method.trim().toUpperCase();
        if (!VERIFY_METHODS.contains(m)) {
            throw new IllegalArgumentException(
                    "非法验证方式: " + method + "，合法值为 " + VERIFY_METHODS);
        }
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }
        if (existing.isTerminalStatus()) {
            throw new IllegalStateException("工单已终结，无法验证: " + ticketId);
        }

        String who = (verifier == null || verifier.isBlank()) ? "未知" : verifier.trim();

        existing.setVerifyMethod(m);
        existing.setVerifyConclusion(conclusion);
        existing.setVerifier(who);
        existing.setVerifiedAt(LocalDateTime.now());
        existing.setVerifySkipped(false);
        existing.setVerifySkipReason(null);

        ticketRepository.update(existing);

        // 验证通过 → 转已解决（状态变更是工单生命周期的关键节点）
        if (!TicketEnums.Status.RESOLVED.equals(existing.getStatus())) {
            ticketRepository.updateStatus(ticketId, TicketEnums.Status.RESOLVED);
        }

        recordActivity(ticketId, "success", "修复验证通过",
                verifyMethodLabel(m) + (conclusion != null && !conclusion.isBlank()
                        ? "：" + (conclusion.length() > 60 ? conclusion.substring(0, 60) + "…" : conclusion) : ""),
                who, true);

        log.info("✅ [TicketService] 修复验证通过 | ticketId={} | method={} | verifier={}",
                ticketId, m, who);
        return ticketRepository.findById(ticketId);
    }

    /**
     * 跳过验证
     * <p>
     * D3 决策：允许跳过但<b>强制填写理由</b>（同 6.21 purge 的 complianceReason 做法）。
     * 跳过验证的工单仍转 RESOLVED，但 {@code verify_skipped=true}，
     * MTTR 统计时排除这些工单——否则"点一下已解决"就能刷低 MTTR，考核数据失真。
     * </p>
     */
    // 事务：工单 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public DevOpsTicket skipVerification(String ticketId, String reason, String operator) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("跳过验证的理由不能为空");
        }
        if (reason.length() > 255) {
            throw new IllegalArgumentException("跳过理由过长（上限 255 字）");
        }
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }
        if (existing.isTerminalStatus()) {
            throw new IllegalStateException("工单已终结: " + ticketId);
        }

        String who = (operator == null || operator.isBlank()) ? "未知" : operator.trim();

        existing.setVerifySkipped(true);
        existing.setVerifySkipReason(reason.trim());
        existing.setVerifier(who);
        existing.setVerifiedAt(LocalDateTime.now());

        ticketRepository.update(existing);

        if (!TicketEnums.Status.RESOLVED.equals(existing.getStatus())) {
            ticketRepository.updateStatus(ticketId, TicketEnums.Status.RESOLVED);
        }

        recordActivity(ticketId, "warning", "跳过验证",
                "理由：" + reason.trim(), who, true);

        log.warn("⚠️ [TicketService] 跳过验证 | ticketId={} | reason={} | by={}",
                ticketId, reason.trim(), who);
        return ticketRepository.findById(ticketId);
    }

    /**
     * 根因分类聚合统计（指导系统性改进：哪类根因最多）
     */
    public java.util.Map<String, Object> getRootCauseStats() {
        return ticketRepository.countRootCauseStats();
    }

    /**
     * 闭环度量：MTTA / MTTM / MTTR + 各阶段完成率 + 跳过验证率
     */
    public java.util.Map<String, Object> getClosureMetrics() {
        return ticketRepository.countClosureMetrics();
    }

    private String verifyMethodLabel(String method) {
        return switch (method) {
            case "MONITOR" -> "监控确认";
            case "LOG" -> "日志确认";
            case "BUSINESS" -> "业务确认";
            case "MANUAL" -> "人工确认";
            default -> method;
        };
    }

    /**
     * 生成工单流水号
     * 格式: TKT-yyyyMMdd-序号(当日递增)
     * 使用 Redis INCR 保证并发安全和序号连续性
     */
    private String generateTicketId() {
        String datePrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String redisKey = "devops:ticket:seq:" + datePrefix;

        // Redis 自增,首次创建键时设置过期时间(48小时后自动清理)
        Long sequence = redisTemplate.opsForValue().increment(redisKey);
        if (sequence == null) {
            sequence = 1L;
        }

        // 首次设置过期时间
        if (sequence == 1) {
            redisTemplate.expire(redisKey, 48, TimeUnit.HOURS);
        }

        // 格式化为4位序号
        String sequenceStr = String.format("%04d", sequence);
        return "TKT-" + datePrefix + "-" + sequenceStr;
    }

    // ==================== 回复与活动流 ====================

    /**
     * 追加工单回复
     *
     * @param ticketId    工单号
     * @param role        角色 creator/agent/ai
     * @param author      回复人
     * @param authorColor 头像色值，可为 null
     * @param content     回复正文
     * @return 落库后的回复（含 id 与时间）
     * @throws IllegalStateException    工单不存在
     * @throws IllegalArgumentException 参数非法
     */
    // 事务：回复 + 工单 + 活动流 + 首响。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public TicketReply addReply(String ticketId, String role, String author,
                                String authorColor, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("回复内容不能为空");
        }
        if (content.length() > 5000) {
            throw new IllegalArgumentException("回复内容过长（上限 5000 字）");
        }

        DevOpsTicket ticket = ticketRepository.findById(ticketId);
        if (ticket == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }
        // 已关闭工单不允许再回复，避免绕过流程
        if ("CLOSED".equalsIgnoreCase(ticket.getStatus())) {
            throw new IllegalStateException("工单已关闭，无法回复: " + ticketId);
        }

        TicketReply reply = new TicketReply();
        reply.setTicketId(ticketId);
        reply.setRole(normalizeRole(role));
        reply.setAuthor((author == null || author.isBlank()) ? "未知用户" : author.trim());
        reply.setAuthorColor(authorColor);
        reply.setContent(content.trim());
        reply.setCreateTime(LocalDateTime.now());

        Long id = replyRepository.insert(reply);
        reply.setId(id);

        // 回复同时刷新工单更新时间，使列表排序反映最新活跃度
        ticketRepository.touchUpdateTime(ticketId);

        recordActivity(ticketId, "primary", "新增回复",
                reply.getAuthor() + " 回复了工单", reply.getAuthor(), false);

        // B1：人工回复视为首响。AI 回复不算——AI 分析在建单时自动触发（6.39），
        // 若计入则每张工单建单即「已首响」，首响 SLA 形同虚设
        if (!"ai".equalsIgnoreCase(reply.getRole())) {
            markFirstResponse(ticketId, reply.getAuthor());
        }

        return reply;
    }

    // ==================== 标签 ====================

    /**
     * 查询工单（自动装填标签）
     * <p>
     * 标签存于关联表，需二次查询。此方法是对外查询的统一入口，
     * 避免各调用方遗漏装填导致标签为空。
     * </p>
     */
    public DevOpsTicket getTicketWithTags(String ticketId) {
        DevOpsTicket t = ticketRepository.findById(ticketId);
        if (t != null) {
            t.setTags(tagRepository.findByTicketId(ticketId));
        }
        return t;
    }

    /**
     * 批量装填标签
     * <p>列表页用，一次查询解决 N+1。</p>
     */
    public void fillTags(List<DevOpsTicket> tickets) {
        if (tickets == null || tickets.isEmpty()) return;
        List<String> ids = tickets.stream().map(DevOpsTicket::getId).toList();
        var tagMap = tagRepository.findByTicketIds(ids);
        for (DevOpsTicket t : tickets) {
            t.setTags(tagMap.getOrDefault(t.getId(), List.of()));
        }
    }

    /**
     * 替换工单标签
     *
     * @param ticketId 工单号
     * @param tags     新标签列表，空表示清空
     * @return 更新后的标签（归一化后的实际结果）
     * @throws IllegalStateException 工单不存在
     */
    public List<String> replaceTags(String ticketId, List<String> tags) {
        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            throw new IllegalStateException("工单不存在: " + ticketId);
        }

        List<String> before = tagRepository.findByTicketId(ticketId);
        int requested = tags != null ? tags.size() : 0;
        tagRepository.replaceTags(ticketId, tags);
        List<String> after = tagRepository.findByTicketId(ticketId);

        // 请求了标签但一个都没存上——数据丢失，必须留痕而非静默
        if (requested > 0 && after.isEmpty()) {
            log.error("🚨 [TicketService] 标签替换失败 | ticketId={} | 提交={} 个 | 库中仍为空",
                    ticketId, requested);
        }

        // 仅在实际变化时留痕，避免无意义的活动流噪音
        if (!before.equals(after)) {
            recordActivity(ticketId, "gray", "标签变更",
                    (before.isEmpty() ? "无" : String.join("、", before))
                            + " → " + (after.isEmpty() ? "无" : String.join("、", after)),
                    "当前用户", false);
        }

        return after;
    }

    /**
     * 查询热门标签
     * <p>供前端输入时建议历史标签，减少「K8s / k8s / K8S」同义异形。</p>
     *
     * @param limit 数量上限，1~100
     */
    public Map<String, Integer> getHotTags(int limit) {
        int safe = Math.min(Math.max(1, limit), 100);
        return tagRepository.findHotTags(safe);
    }

    /**
     * 按标签筛选工单号（AND 语义：须含全部标签）
     */
    public List<String> findTicketIdsByTags(List<String> tags) {
        return tagRepository.findTicketIdsByAllTags(tags);
    }

    /**
     * 查询工单回复（时间正序）
     */
    public List<TicketReply> listReplies(String ticketId) {
        return replyRepository.findByTicketId(ticketId);
    }

    /**
     * 查询工单活动流（时间倒序，最新在前）
     */
    public List<TicketActivity> listActivities(String ticketId) {
        return activityRepository.findByTicketId(ticketId);
    }

    /**
     * 记录活动流
     * <p>
     * 旁路审计数据，写入失败仅告警不抛异常——活动流缺一条
     * 远不如主业务失败严重。
     * </p>
     */
    public void recordActivity(String ticketId, String color, String text,
                               String detail, String userName, boolean highlight) {
        activityRepository.insert(TicketActivity.of(
                ticketId, color, text, detail,
                (userName == null || userName.isBlank()) ? "系统自动" : userName,
                highlight));
    }

    /**
     * 状态对应的活动流圆点颜色
     */
    private String statusColor(String status) {
        if (status == null) return "gray";
        return switch (status.toUpperCase()) {
            case "RESOLVED" -> "success";
            case "PROCESSING" -> "primary";
            case "VOID" -> "warning";
            case "CLOSED" -> "gray";
            default -> "primary";
        };
    }

    /**
     * 状态中文标签
     * <p>活动流面向用户展示，不应出现英文枚举值。</p>
     */
    private String statusLabel(String status) {
        if (status == null) return "未知";
        return switch (status.toUpperCase()) {
            case "PENDING" -> "待处理";
            case "PROCESSING" -> "处理中";
            case "RESOLVED" -> "已解决";
            case "CLOSED" -> "已关闭";
            case "VOID" -> "已作废";
            default -> status;
        };
    }

    /**
     * 优先级中文标签
     * <p>委托枚举单源，避免与 {@link TicketEnums.Priority#label} 漂移。</p>
     */
    private String priorityLabel(String priority) {
        if (priority == null) return "未知";
        return TicketEnums.Priority.label(priority);
    }

    /**
     * 归一化回复角色
     * <p>非法值降级为 agent 而非报错，避免因前端传值问题丢失回复内容。</p>
     */
    private String normalizeRole(String role) {
        if (role == null) return "agent";
        String r = role.trim().toLowerCase();
        return switch (r) {
            case "creator", "agent", "ai" -> r;
            default -> {
                log.warn("⚠️ [TicketService] 未知回复角色，降级为 agent | role={}", role);
                yield "agent";
            }
        };
    }

    /**
     * 作废工单（Saga 补偿动作）
     * <p>
     * 语义：不做物理删除，而是状态置为 VOID 并在描述追加补偿原因。
     * 理由：① 审计要求保留痕迹；② 物理删除不可逆，与"补偿可重试"矛盾。
     * </p>
     * <p><b>幂等</b>：已是 VOID 的工单重复调用返回成功，不报错。</p>
     *
     * @param ticketId 工单号
     * @param reason   作废原因
     * @return 作废结果描述
     * @throws IllegalStateException 工单不存在时抛出（补偿失败需人工介入）
     */
    // 事务：工单 + 活动流。任一步失败必须整体回滚，
    // 否则会留下「工单已删但回复/标签仍在」这类孤儿数据。
    @Transactional(rollbackFor = Exception.class)
    public String voidTicket(String ticketId, String reason) {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("工单号不能为空，无法作废");
        }

        DevOpsTicket existing = ticketRepository.findById(ticketId);
        if (existing == null) {
            // 工单不存在：补偿无法完成，需人工确认是否本就未创建成功
            throw new IllegalStateException("工单不存在，无法作废: " + ticketId);
        }

        // 幂等：已作废则直接返回
        if ("VOID".equalsIgnoreCase(existing.getStatus())) {
            log.info("ℹ️ [TicketService] 工单已是作废态，跳过 | ticketId={}", ticketId);
            return "工单 " + ticketId + " 已处于作废状态（幂等跳过）";
        }

        int rows = ticketRepository.voidTicket(ticketId, reason);
        if (rows == 0) {
            throw new IllegalStateException("工单作废失败，受影响行数为 0: " + ticketId);
        }

        log.warn("↩️ [TicketService] 工单已作废（Saga 补偿）| ticketId={} | 原因={}", ticketId, reason);

        // 活动流：作废是重要状态变更，需高亮留痕便于审计追溯
        recordActivity(ticketId, "warning", "工单作废", reason, "系统自动", true);

        return "工单 " + ticketId + " 已作废，原因：" + reason;
    }

    /**
     * 回填工单的来源追踪 ID
     *
     * @deprecated P1-3 Single Writer 重构后已无调用方。
     * 原用途是修复流式模式下 ThreadLocal 跨线程失效导致的 source_trace_id 丢失——
     * 工具在模型 HTTP 回调线程写库时取不到 traceId，只能事后回填。
     * 现在工单由编排层写入，traceId 原生可用，该补丁不再必要。
     * <p>保留方法以备数据修复场景（如历史数据补关联）使用。</p>
     *
     * @param ticketId 工单号
     * @param traceId  会话追踪 ID
     * @return 是否成功回填
     */
    @Deprecated(since = "2026-08-09")
    public boolean backfillTraceId(String ticketId, String traceId) {
        if (ticketId == null || ticketId.isBlank() || traceId == null || traceId.isBlank()) {
            return false;
        }
        try {
            int rows = ticketRepository.backfillSourceTraceId(ticketId, traceId);
            if (rows > 0) {
                log.info("🔗 [TicketService] 已回填来源追踪 ID | ticketId={} | traceId={}", ticketId, traceId);
                return true;
            }
            log.debug("ℹ️ [TicketService] 无需回填（工单不存在或已有 traceId）| ticketId={}", ticketId);
            return false;
        } catch (Exception e) {
            // 回填失败不影响主流程，工单仍可正常使用
            log.warn("⚠️ [TicketService] 回填来源追踪 ID 失败 | ticketId={} | {}", ticketId, e.getMessage());
            return false;
        }
    }

    /**
     * 查询工单总数(供看板使用)
     */
    public long getTotalTickets() {
        return ticketRepository.countAll();
    }

    /**
     * 分页查询工单列表
     * <p>封装 Repository 调用，避免 Controller 直接依赖 Repository（六层架构约束）。</p>
     *
     * @param page  页码（已归一化 ≥ 1）
     * @param size  每页大小（已归一化 1~200）
     * @param query 查询条件
     * @return 工单列表（已装填标签由调用方在取得列表后调 fillTags）
     */
    public List<DevOpsTicket> findTickets(int page, int size, TicketQuery query) {
        return ticketRepository.findPage(page, size, query);
    }

    /**
     * 按查询条件统计工单总数
     */
    public long countTickets(TicketQuery query) {
        return ticketRepository.countByQuery(query);
    }

    /**
     * 根据 traceId 查询工单
     */
    public DevOpsTicket findByTraceId(String traceId) {
        return ticketRepository.findByTraceId(traceId);
    }

    /**
     * 工单统计（供列表页 KPI 卡片）
     * <p>
     * 含各状态计数、各优先级计数、今日新增、总数、紧急待处理数。
     * 缺失的状态/优先级补 0，避免前端 undefined。
     * </p>
     *
     * @return 统计 Map（total / todayNew / byStatus / byPriority / pending / processing / resolved / urgentPending）
     */
    public Map<String, Object> getTicketStats() {
        Map<String, Object> data = new java.util.HashMap<>();

        long total = ticketRepository.countAll();
        data.put("total", total);
        data.put("todayNew", ticketRepository.countCreatedToday());

        // 各状态计数，缺失状态补 0，避免前端 undefined
        Map<String, Long> byStatus = new java.util.HashMap<>();
        for (String s : TicketEnums.Status.ALL) {
            byStatus.put(s, 0L);
        }
        for (Object[] row : ticketRepository.countGroupByStatus()) {
            byStatus.put(String.valueOf(row[0]), (Long) row[1]);
        }
        data.put("byStatus", byStatus);

        // 前端 KPI 直接可用的扁平字段
        data.put("pending", byStatus.getOrDefault("PENDING", 0L));
        data.put("processing", byStatus.getOrDefault("PROCESSING", 0L));
        data.put("resolved", byStatus.getOrDefault("RESOLVED", 0L)
                + byStatus.getOrDefault("CLOSED", 0L));

        // 各优先级计数，缺失补 0
        Map<String, Long> byPriority = new java.util.HashMap<>();
        for (String p : TicketEnums.Priority.ALL) {
            byPriority.put(p, 0L);
        }
        for (Object[] row : ticketRepository.countGroupByPriority()) {
            byPriority.put(String.valueOf(row[0]), (Long) row[1]);
        }
        data.put("byPriority", byPriority);

        data.put("urgentPending", ticketRepository.countUrgentPending());

        return data;
    }

    /**
     * 工单趋势分析（L2，供趋势分析模式 ECharts 可视化）
     * <p>
     * 返回 {@code days} 天窗口内（含今天）的每日建单数 / 每日解决数（verified_at 口径，
     * 与闭环度量 MTTR 一致），并<b>补零填充</b>为连续 N 天——无活动的日期返回 0 而非缺失，
     * 遵循 6.41「固定周期趋势必须补零」。缺失的日期若缺省会让折线图断档，用户看不出哪几天无活动。
     * </p>
     *
     * @param days 统计窗口天数，Controller 已兜底至 [1, 90]
     * @return { days, created[], resolved[] } 三个对齐数组，长度均为 days
     */
    public Map<String, Object> getTicketTrends(int days) {
        return getTicketTrends(days, null);
    }

    /**
     * 工单趋势分析，可按服务模块下钻
     * <p>
     * {@code module} 为空时返回全局口径。下钻是趋势分析的核心价值——
     * 全局趋势只能说明「整体忙不忙」，按服务下钻才能定位「哪个服务在恶化」。
     * </p>
     *
     * @param days   统计窗口天数，Controller 已兜底至 [1, 90]
     * @param module 服务模块（K8S/MYSQL/NETWORK 等），null/空=全局
     * @return { days, created[], resolved[], module } 三个对齐数组 + 生效口径
     */
    public Map<String, Object> getTicketTrends(int days, String module) {
        java.time.LocalDate today = java.time.LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // 先把有数据的日期归入 map，便于补零时按日期查
        Map<java.time.LocalDate, Long> createdByDay = new java.util.HashMap<>();
        for (Object[] row : ticketRepository.countCreatedByDay(days, module)) {
            createdByDay.put(((java.sql.Date) row[0]).toLocalDate(), (Long) row[1]);
        }
        Map<java.time.LocalDate, Long> resolvedByDay = new java.util.HashMap<>();
        for (Object[] row : ticketRepository.countResolvedByDay(days, module)) {
            resolvedByDay.put(((java.sql.Date) row[0]).toLocalDate(), (Long) row[1]);
        }

        List<String> daysList = new ArrayList<>();
        List<Long> created = new ArrayList<>();
        List<Long> resolved = new ArrayList<>();

        // 连续 N 天：从 days-1 天前到今天，无数据的日期补 0
        for (int i = days - 1; i >= 0; i--) {
            java.time.LocalDate day = today.minusDays(i);
            daysList.add(day.format(formatter));
            created.add(createdByDay.getOrDefault(day, 0L));
            resolved.add(resolvedByDay.getOrDefault(day, 0L));
        }

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("days", daysList);
        data.put("created", created);
        data.put("resolved", resolved);
        // 回传生效口径：前端据此显示「全局」或具体服务名，避免用户误读（6.41 契约）
        data.put("module", (module == null || module.isBlank()) ? null : module);
        return data;
    }

    /**
     * 根据优先级映射 SLA 展示串
     * <p>
     * 委托 {@link TicketEnums.Sla#describe}——展示串由时限分钟数派生，
     * 保证「展示的 SLA」与「计时用的 SLA」必然一致。
     * 此前是硬编码字符串，与 deadline 计算无关联，改时限必漂移。
     * </p>
     */
    private String mapPriorityToSla(String priority) {
        return TicketEnums.Sla.describe(priority);
    }

    /**
     * 根据 module 映射 category (前端使用)
     */
    private String mapModuleToCategory(String module) {
        return switch (module.toUpperCase()) {
            case "K8S" -> "容器/K8s";
            case "MYSQL" -> "数据库";
            case "ALIYUN_SLB" -> "网络";
            case "NETWORK" -> "网络";
            case "OTHER" -> "其他";
            default -> "其他";
        };
    }
}

package com.devops.agent.domain.biz.entity;

import java.util.Map;
import java.util.Set;

/**
 * 工单枚举单源定义
 * <p>
 * 后端工单状态、优先级、模块的唯一真相源。
 * 所有字符串约定必须引用此处常量，禁止散落字面量。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-17
 */
public final class TicketEnums {

    private TicketEnums() {}

    /**
     * 工单状态
     */
    public static final class Status {
        public static final String PENDING = "PENDING";
        public static final String PROCESSING = "PROCESSING";
        public static final String RESOLVED = "RESOLVED";
        public static final String CLOSED = "CLOSED";
        public static final String VOID = "VOID";

        public static final Set<String> ALL = Set.of(PENDING, PROCESSING, RESOLVED, CLOSED, VOID);

        public static boolean isValid(String status) {
            return status != null && ALL.contains(status.trim().toUpperCase());
        }

        public static String normalize(String status) {
            if (status == null || status.isBlank()) return null;
            return status.trim().toUpperCase();
        }

        /**
         * 合法状态流转表。
         *
         * <p><b>为什么必须有</b>：此前 {@code updateStatus} 只校验「目标值是否是合法枚举」，
         * 不校验「从当前状态能否走到目标状态」。于是一张 CLOSED 的工单可以被直接改回
         * PENDING，一张 VOID（作废）的工单可以被复活——SLA 统计、首响计时、
         * 复盘归档全部随之失真，而且没有任何报错。
         *
         * <p>流转规则：
         * <ul>
         *   <li>PENDING（待处理）→ 开始处理 / 直接解决 / 作废</li>
         *   <li>PROCESSING（处理中）→ 解决 / 退回待处理（误接单）/ 作废</li>
         *   <li>RESOLVED（已解决）→ 关闭 / 重新打开（验证不通过）</li>
         *   <li>CLOSED（已关闭）→ <b>终态</b>，仅允许重开（复发）</li>
         *   <li>VOID（已作废）→ <b>终态，不可逆</b>。作废是审计事实，
         *       复活会让「这张单到底存不存在」变得不可判定</li>
         * </ul>
         */
        private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
                PENDING,    Set.of(PROCESSING, RESOLVED, VOID),
                PROCESSING, Set.of(PENDING, RESOLVED, VOID),
                RESOLVED,   Set.of(CLOSED, PROCESSING),
                CLOSED,     Set.of(PROCESSING),
                VOID,       Set.of()
        );

        /**
         * 判断状态流转是否合法。
         *
         * @param from 当前状态
         * @param to   目标状态
         * @return 合法返回 true；<b>同态视为合法</b>（幂等重试不应报错）
         */
        public static boolean canTransition(String from, String to) {
            String f = normalize(from);
            String t = normalize(to);
            if (f == null || t == null) return false;
            if (f.equals(t)) return true;   // 幂等
            return ALLOWED_TRANSITIONS.getOrDefault(f, Set.of()).contains(t);
        }

        /** 该状态可流转到的目标集合，供前端置灰非法选项 */
        public static Set<String> nextStates(String from) {
            return ALLOWED_TRANSITIONS.getOrDefault(normalize(from), Set.of());
        }

        /** 是否为终态（不可再流转，仅 VOID） */
        public static boolean isTerminal(String status) {
            return VOID.equals(normalize(status));
        }

        private Status() {}
    }

    /**
     * 工单优先级（四档 P0~P3）
     * <p>
     * <b>2026-08-18 B0 改造</b>：原为 HIGH/MEDIUM/LOW 三档，而前端有
     * urgent/high/medium/low 四档，映射时 {@code urgent→HIGH, high→HIGH}
     * 两档塌缩成一档——用户选「高」，保存后回读变「紧急」，{@code high} 档事实上不存在。
     * 且三档无法实现 PRD §2.3 要求的 P0(15min)/P1(30min)/P2(4h)/P3(24h) 分级首响 SLA。
     * </p>
     * <p>
     * 分档语义（对齐 PRD §2.3 与告警侧 P0~P4 分级）：
     * <ul>
     *   <li>P0 紧急：生产宕机 / 核心业务不可用</li>
     *   <li>P1 高：影响业务但有临时方案</li>
     *   <li>P2 中：需处理但不紧急</li>
     *   <li>P3 低：优化建议</li>
     * </ul>
     * </p>
     */
    public static final class Priority {
        public static final String P0 = "P0";
        public static final String P1 = "P1";
        public static final String P2 = "P2";
        public static final String P3 = "P3";

        public static final Set<String> ALL = Set.of(P0, P1, P2, P3);

        /** 旧三档值（存量数据与旧客户端仍可能传入），仅用于兼容映射 */
        private static final String LEGACY_HIGH = "HIGH";
        private static final String LEGACY_MEDIUM = "MEDIUM";
        private static final String LEGACY_LOW = "LOW";

        public static boolean isValid(String priority) {
            return priority != null && ALL.contains(priority.trim().toUpperCase());
        }

        /**
         * 归一化优先级
         * <p>
         * 兼容三类输入：① 新四档 P0~P3；② 旧三档 HIGH/MEDIUM/LOW（存量数据、
         * 旧客户端、AI 工具历史提示词）；③ 前端 URGENT 别名。非法值兜底 P2。
         * </p>
         * <p>
         * 旧值映射与 {@code migration_v16} 的存量迁移保持一致：
         * HIGH→P1、MEDIUM→P2、LOW→P3。注意 HIGH 映射为 <b>P1 而非 P0</b>——
         * 旧 HIGH 混装了「紧急」与「高」两种语义，无法区分，统一降为 P1 更保守：
         * 误把普通高优当成 P0 会让 15 分钟首响时限失去可信度。
         * </p>
         */
        public static String normalize(String priority) {
            if (priority == null || priority.isBlank()) return P2;
            String p = priority.trim().toUpperCase();
            return switch (p) {
                case P0, "URGENT" -> P0;
                case P1, LEGACY_HIGH -> P1;
                case P2, LEGACY_MEDIUM -> P2;
                case P3, LEGACY_LOW -> P3;
                default -> P2;
            };
        }

        /** 是否为旧三档值（供调用方记 WARN 日志，便于发现未迁移的调用点） */
        public static boolean isLegacyValue(String priority) {
            if (priority == null) return false;
            String p = priority.trim().toUpperCase();
            return LEGACY_HIGH.equals(p) || LEGACY_MEDIUM.equals(p) || LEGACY_LOW.equals(p);
        }

        /** 展示标签（中文），供活动流等面向用户的文本使用 */
        public static String label(String priority) {
            return switch (normalize(priority)) {
                case P0 -> "紧急";
                case P1 -> "高";
                case P3 -> "低";
                default -> "中";
            };
        }

        private Priority() {}
    }

    /**
     * SLA 时限表（单一来源）
     * <p>
     * 对齐 PRD §2.3：P0 15 分钟响应 / P1 30 分钟 / P2 4 小时 / P3 24 小时。
     * </p>
     * <p>
     * <b>为何时限集中在此</b>：此前 SLA 是散落在 {@code TicketService} 里的
     * 展示字符串（如「4h 响应 / 8h 解决」），既无法用于计时，也容易与前端漂移。
     * 现由本表统一定义分钟数，展示串由 {@link #describe} 派生——
     * 遵循 CLAUDE.md 6.20「同一事实只允许一处定义」。
     * </p>
     */
    public static final class Sla {

        private Sla() {}

        /** 首响时限（分钟） */
        public static int responseMinutes(String priority) {
            return switch (Priority.normalize(priority)) {
                case Priority.P0 -> 15;
                case Priority.P1 -> 30;
                case Priority.P3 -> 24 * 60;
                default -> 4 * 60;      // P2
            };
        }

        /** 解决时限（分钟） */
        public static int resolveMinutes(String priority) {
            return switch (Priority.normalize(priority)) {
                case Priority.P0 -> 4 * 60;
                case Priority.P1 -> 8 * 60;
                case Priority.P3 -> 72 * 60;
                default -> 24 * 60;     // P2
            };
        }

        /**
         * 生成展示串，如「15m 响应 / 4h 解决」
         * <p>由时限数派生，保证展示与计时口径必然一致。</p>
         */
        public static String describe(String priority) {
            return humanize(responseMinutes(priority)) + " 响应 / "
                    + humanize(resolveMinutes(priority)) + " 解决";
        }

        /** 分钟数转可读文本（60 分钟以内用 m，整小时用 h） */
        private static String humanize(int minutes) {
            if (minutes < 60) return minutes + "m";
            if (minutes % 60 == 0) return (minutes / 60) + "h";
            return (minutes / 60) + "h" + (minutes % 60) + "m";
        }
    }

    /**
     * 工单模块
     */
    public static final class Module {
        public static final String K8S = "K8S";
        public static final String ALIYUN_SLB = "ALIYUN_SLB";
        public static final String MYSQL = "MYSQL";
        public static final String NETWORK = "NETWORK";
        public static final String OTHER = "OTHER";

        public static final Set<String> ALL = Set.of(K8S, ALIYUN_SLB, MYSQL, NETWORK, OTHER);

        public static String normalize(String module) {
            if (module == null || module.isBlank()) return OTHER;
            return module.trim().toUpperCase();
        }

        private Module() {}
    }
}

package com.devops.agent.domain.biz.repository;

import java.util.List;

/**
 * 工单查询条件
 * <p>
 * 此前列表接口只支持 priority / status 两个筛选，前端把 100 条数据
 * 拉到浏览器里再本地过滤。后果是<b>第 101 条起的工单静默不可见</b>——
 * 用户搜不到就以为不存在，而分页数字又基于裁剪后的子集，同样是错的。
 * </p>
 * <p>
 * 本记录承载全部筛选维度，由 {@code DevOpsTicketRepository} 统一转为 SQL。
 * </p>
 *
 * @param keyword    关键词，匹配工单号 / 标题 / 描述（不区分大小写）
 * @param priority   优先级精确匹配
 * @param status     状态精确匹配
 * @param module     模块精确匹配
 * @param category   分类精确匹配
 * @param assignee   负责人精确匹配
 * @param createdFrom 创建时间下界（含），格式 yyyy-MM-dd
 * @param createdTo   创建时间上界（含当天，实现上按 &lt; 次日 0 点）
 * @param tags       标签，AND 语义（须同时含全部标签）
 * @param sortBy     排序字段（前端字段名，如 priority / createdAt）。
 *                   null 或不在白名单内则用默认排序 create_time DESC。
 *                   排序必须下沉到 SQL——前端表格本地排序只作用于当前页，
 *                   「按优先级排序」会漏掉页外更高优先级的工单
 * @param sortAsc    true=升序，false=降序
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
public record TicketQuery(
        String keyword,
        String priority,
        String status,
        String module,
        String category,
        String assignee,
        String createdFrom,
        String createdTo,
        List<String> tags,
        String sortBy,
        boolean sortAsc
) {

    /**
     * 兼容旧签名（无排序参数）
     * <p>
     * 保留此构造器避免改动全部既有调用方；不传排序即用默认 create_time DESC。
     * </p>
     */
    public TicketQuery(
            String keyword,
            String priority,
            String status,
            String module,
            String category,
            String assignee,
            String createdFrom,
            String createdTo,
            List<String> tags) {
        this(keyword, priority, status, module, category, assignee,
                createdFrom, createdTo, tags, null, false);
    }

    /** 空条件（查全部） */
    public static TicketQuery empty() {
        return new TicketQuery(null, null, null, null, null, null, null, null, null);
    }

    /** 是否有标签筛选（需 JOIN 标签表，单独处理） */
    public boolean hasTagFilter() {
        return tags != null && !tags.isEmpty();
    }
}

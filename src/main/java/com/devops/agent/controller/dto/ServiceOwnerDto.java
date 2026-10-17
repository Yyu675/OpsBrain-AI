package com.devops.agent.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务值班负责人路由响应 DTO（GET/PUT/DELETE /api/v1/service-owners）。
 *
 * <p>P0-2b 强契约化：替代旧有的 {@code Map<String, Object>} 弱契约。</p>
 */
public class ServiceOwnerDto {

    private ServiceOwnerDto() {
    }

    /** 一条路由历史记录（数据库列名保持 camelCase JSON 字段）。 */
    public record ServiceOwnerItem(
            Long id,
            String service,
            String owner,
            boolean enabled,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {}

    /**
     * 路由清单响应（GET /api/v1/service-owners）。
     */
    public record ServiceOwnerListResponse(
            int total,
            List<ServiceOwnerItem> items
    ) {
    }

    /**
     * 路由更新/新建响应（PUT /api/v1/service-owners）。
     */
    public record ServiceOwnerUpsertResponse(
            Long id,
            String service,
            String owner
    ) {
    }

    /**
     * 路由停用响应（DELETE /api/v1/service-owners/{service}）。
     */
    public record ServiceOwnerDisableResponse(
            String service,
            boolean disabled
    ) {
    }
}

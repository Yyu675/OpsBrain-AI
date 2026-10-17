package com.devops.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 服务值班负责人路由响应 DTO（GET/PUT/DELETE /api/v1/service-owners）。
 *
 * <p>P0-2b 强契约化：替代旧有的 {@code Map<String, Object>} 弱契约。</p>
 */
public class ServiceOwnerDto {

    private ServiceOwnerDto() {
    }

    /**
     * 路由清单响应（GET /api/v1/service-owners）。
     */
    public record ServiceOwnerListResponse(
            int total,
            List<Map<String, Object>> items
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

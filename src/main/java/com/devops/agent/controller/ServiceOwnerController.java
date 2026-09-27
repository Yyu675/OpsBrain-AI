package com.devops.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.biz.repository.ServiceOwnerRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 服务 → 值班负责人路由管理接口（2026-09-25，待分配积压治理）。
 *
 * <p>路由表是告警自动建单指派负责人的唯一事实来源。
 * 配置后告警建单直接派给值班人；未配置的服务保持「待分配」。</p>
 *
 * <ul>
 *   <li>GET    /api/v1/service-owners         —— 路由清单（含已停用，供审计查看）</li>
 *   <li>PUT    /api/v1/service-owners         —— 新增/重建路由（同服务旧路由自动停用）</li>
 *   <li>DELETE /api/v1/service-owners/{service} —— 停用路由（不物理删除）</li>
 * </ul>
 *
 * <h3>安全</h3>
 * 类级 {@code @SaCheckRole("ADMIN")}——路由决定告警工单派给谁，配错等于
 * 把生产告警送到不懂该系统的人手上，与改白名单同级。
 */
@RestController
@RequestMapping("/api/v1/service-owners")
@SaCheckRole("ADMIN")
public class ServiceOwnerController {

    private final ServiceOwnerRepository repository;

    public ServiceOwnerController(ServiceOwnerRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        var rows = repository.listAll();
        return ApiResponse.success(Map.of("total", rows.size(), "items", rows));
    }

    public record UpsertRequest(
            @NotBlank(message = "服务名不能为空") @Size(max = 128) String service,
            @NotBlank(message = "负责人不能为空") @Size(max = 64) String owner) {
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> upsert(@RequestBody @jakarta.validation.Valid UpsertRequest req) {
        Long id = repository.upsert(req.service(), req.owner());
        return ApiResponse.success(Map.of("id", id, "service", req.service().trim(), "owner", req.owner().trim()));
    }

    @DeleteMapping("/{service}")
    public ApiResponse<Map<String, Object>> disable(@PathVariable String service) {
        int n = repository.disable(service);
        return ApiResponse.success(Map.of("service", service, "disabled", n > 0));
    }
}

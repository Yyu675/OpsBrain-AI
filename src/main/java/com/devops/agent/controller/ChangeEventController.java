package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.common.guard.KnowledgeWriteGuard;
import jakarta.validation.Valid;
import com.devops.agent.domain.biz.entity.ChangeEvent;
import com.devops.agent.domain.biz.repository.ChangeEventRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S1-2 变更事件写入端点（路线图 §5.3 1-2.5：CI/CD 流水线回调接入面）。
 * <p>
 * 鉴权：与知识库写操作同构（{@link KnowledgeWriteGuard#requireEdit()}，
 * ADMIN/OPS 角色）——CI 侧用服务账号登录拿 token，不新开一套签名机制
 * （避免又造一个 PBKDF2/Webhook 验签的对照组）。
 * 幂等：UNIQUE(source, external_id) + ON CONFLICT DO NOTHING，
 * 流水线重发同一事件返回 200 + deduplicated=true（不是 4xx——
 * 重发是 CI 的正常行为，不该让流水线红）。
 * </p>
 * <p>curl 示例（见报告 104）：</p>
 * <pre>
 * curl -X POST /api/changes -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
 *   "serviceName":"order-service","changeType":"deploy","operator":"jenkins-bot",
 *   "summary":"order-service 1.4.2 → 1.4.3","changeTime":"2026-09-07T14:30:00",
 *   "source":"ci-callback","externalId":"jenkins-order-service-891"
 * }'
 * </pre>
 */
@RestController
@RequestMapping("/api/changes")
public class ChangeEventController {

    private static final Logger log = LoggerFactory.getLogger(ChangeEventController.class);

    /** summary 入库上限：与表 VARCHAR(2000) 对齐，写端截断而非 500。 */
    private static final int SUMMARY_MAX = 2000;

    private final ChangeEventRepository repository;
    private final KnowledgeWriteGuard guard;

    public ChangeEventController(ChangeEventRepository repository, KnowledgeWriteGuard guard) {
        this.repository = repository;
        this.guard = guard;
    }

    /** 请求体 record：字段名即 JSON 键名，逐字为准。 */
    public record ChangeEventRequest(
            @NotBlank(message = "serviceName 必填") @Size(max = 128) String serviceName,
            @NotBlank(message = "changeType 必填") @Size(max = 32) String changeType,
            @Size(max = 64) String operator,
            @NotBlank(message = "summary 必填") String summary,
            @NotBlank(message = "changeTime 必填（ISO-8601，如 2026-09-07T14:30:00）") String changeTime,
            @Size(max = 64) String source,
            @Size(max = 191) String externalId) {}

    @PostMapping
    public ApiResponse<Map<String, Object>> report(@Valid @RequestBody ChangeEventRequest req) {
        guard.requireEdit();

        LocalDateTime changeTime;
        try {
            changeTime = LocalDateTime.parse(req.changeTime());
        } catch (Exception e) {
            // 预期分支（客户端时间格式错→400）；debug 级留痕，400 请求不属系统故障
            log.debug("[S1-2] changeTime 解析失败 | input={} | {}", req.changeTime(), e.getMessage());
            return ApiResponse.error(400,
                    "changeTime 需为 ISO-8601 本地时间（如 2026-09-07T14:30:00），收到: " + req.changeTime());
        }
        String summary = req.summary().length() > SUMMARY_MAX
                ? req.summary().substring(0, SUMMARY_MAX) : req.summary();
        String source = (req.source() == null || req.source().isBlank()) ? "manual" : req.source();

        int inserted = repository.save(ChangeEvent.of(
                req.serviceName(), req.changeType(),
                (req.operator() == null || req.operator().isBlank()) ? "unknown" : req.operator(),
                summary, changeTime, source, req.externalId()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("deduplicated", inserted == 0);
        body.put("message", inserted == 0
                ? "同一 (source, externalId) 事件已存在，按幂等约束忽略（CI 重发属正常）"
                : "变更事件已登记");
        if (inserted == 0) {
            log.info("[S1-2] 变更事件幂等去重 | source={} externalId={}", source, req.externalId());
        }
        return ApiResponse.success(body);
    }
}

package com.devops.agent.domain.alert;

import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alertmanager 源适配器（Prometheus Alertmanager Webhook 负载 → {@link AlertSignal}）。
 *
 * <p>
 * 唯一的解析职责在「读懂 Alertmanager 的 label/annotation 语义」：
 * </p>
 * <ul>
 *   <li>{@code alertname / service / severity} 取自 labels；</li>
 *   <li>{@code module} 推断：显式 {@code module} 标签（大写化）否则 OTHER；</li>
 *   <li>{@code description} 走 DTO 的 annotation 汇聚（description > summary > 标签拼接）；</li>
 *   <li>{@code resolved} 与 {@code startsAt} 沿用 DTO 既有判定。</li>
 * </ul>
 * 这些推断规则此前散在 {@code AlertService}（{@code inferModule} 等），
 * 2026-09-24 移入本类——「读懂一种源」是适配器的职责，不是核心链的。
 */
@Component
public class AlertmanagerSourceAdapter implements AlertSourceAdapter {

    private static final String SOURCE = "prometheus";
    private static final String DEFAULT_MODULE = "OTHER";

    @Override
    public boolean supports(Object rawPayload) {
        return rawPayload instanceof AlertmanagerWebhook;
    }

    @Override
    public List<AlertSignal> normalize(Object rawPayload) {
        if (!supports(rawPayload)) {
            throw new IllegalArgumentException(
                    "AlertmanagerSourceAdapter 不支持的负载类型: "
                            + (rawPayload == null ? "null" : rawPayload.getClass().getName()));
        }
        List<AlertmanagerWebhook.Alert> alerts = ((AlertmanagerWebhook) rawPayload).getAlerts();
        List<AlertSignal> signals = new ArrayList<>(alerts.size());
        for (AlertmanagerWebhook.Alert a : alerts) {
            signals.add(toSignal(a));
        }
        return signals;
    }

    private AlertSignal toSignal(AlertmanagerWebhook.Alert a) {
        // 防御性拷贝：labels 会作为去重键输入被下游消费，不能持有上游可变的引用
        Map<String, String> labels = a.getLabels() == null
                ? Map.of() : new LinkedHashMap<>(a.getLabels());
        return new AlertSignal(
                a.getLabel("alertname"),
                a.getLabel("service"),
                a.getLabel("severity"),
                inferModule(a),
                a.getFingerprint(),
                SOURCE,
                labels,
                a.descriptionText(),
                a.getStartsAt(),
                a.isResolved());
    }

    /** 从标签推断业务模块：显式 module 标签（大写化）> OTHER。 */
    private String inferModule(AlertmanagerWebhook.Alert a) {
        String module = a.getLabel("module");
        if (module != null && !module.isBlank()) {
            return module.trim().toUpperCase();
        }
        return DEFAULT_MODULE;
    }
}
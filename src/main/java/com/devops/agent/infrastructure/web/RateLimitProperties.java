package com.devops.agent.infrastructure.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API 入口限流配置（S5-3.5，批 45）。
 *
 * <p>规则大队：每条规则 = 一组路径前缀 + 窗口配额；只管规则命中的前缀，
 * 其他路径不过滤——不把限流器变成全站税吏，贵的接口才缴税。
 *
 * <p>关键纪律：配额计数按 <b>限流对象键</b>（远端 IP，首跳 X-Forwarded-For
 * 优先）分桶。登录态用户级配额需要 Sa-Token 上下文钉序，属后续增强——
 * 先做诚实的 IP 桶，不做半熟的 user 桶（顺序一错，游客吃用户配额）。
 */
@ConfigurationProperties(prefix = "devops.security.rate-limit")
public class RateLimitProperties {

    /**
     * 总开关。禁用仅用于压测排障现场——常态不下线限流，
     * 就像限速牌不是为飙车手准备的的可选礼仪。
     */
    private boolean enabled = true;

    /** 规则大队（按序匹配，首条前缀命中即裁定）。 */
    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    /** 单条限流规则。 */
    public static class Rule {

        /** 规则名（日志与配置可读性用，不外泄给客户端）。 */
        private String name;

        /** 逗号分隔的路径前缀清单（空间用「去除 context-path 后的路径」比对）。 */
        private String paths;

        /** 单窗口放行配额。 */
        private int permits;

        /** 窗口长度（秒）。 */
        private int periodSeconds = 60;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPaths() {
            return paths;
        }

        public void setPaths(String paths) {
            this.paths = paths;
        }

        public int getPermits() {
            return permits;
        }

        public void setPermits(int permits) {
            this.permits = permits;
        }

        public int getPeriodSeconds() {
            return periodSeconds;
        }

        public void setPeriodSeconds(int periodSeconds) {
            this.periodSeconds = periodSeconds;
        }
    }
}

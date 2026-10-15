package com.devops.agent.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单/告警写端点的权限覆盖契约（T3，2026-10-12）。
 *
 * <h3>为什么要扫描而不是逐个写用例</h3>
 * <p>
 * 缺陷形态与知识库 F-5 一模一样：「20 个写端点里 19 个漏了」，且
 * <b>新增写端点漏加守卫没有任何编译/运行信号</b>——功能完全正常，只是人人可调。
 * T3 收口后必须留一把机械闸，否则下次加端点照样漏。断言方式沿用
 * {@link KnowledgeWriteGuardCoverageTest}：按文件数写端点数并逐段找守卫——
 * 文件改名会红、新增端点使计数漂移会红、忘加守卫会被点名行号。
 * </p>
 *
 * <h3>什么算「有守卫」</h3>
 * <ul>
 *   <li>{@code writeGuard.requireWrite()} / {@code writeGuard.requireAdmin()}
 *       —— T3 引入的 OpsWriteGuard；</li>
 *   <li>{@code @SaCheckRole} —— 方向 F 既有写法（工单物理删除用），保留不改。</li>
 * </ul>
 *
 * <h3>有意豁免（逐个记录理由，防「豁免」变成第二个漏洞面）</h3>
 * <ul>
 *   <li>{@code POST /tickets/ai-analysis/{id}/feedback}：阅读反馈不是内容编辑——
 *       与知识库 {@code /feedback/citations} 同语义（F-5 豁免先例），
 *       任何登录用户都可点，加守卫会把产品语义收成 ADMIN/OPS 专属。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-10-12
 */
@DisplayName("工单/告警写端点权限覆盖")
class OpsWriteGuardCoverageTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/com/devops/agent/controller");

    /** 受守卫的控制器 → 该文件里写端点（POST/PUT/DELETE/PATCH）总数 */
    private static final Map<String, Integer> OPS_CONTROLLERS = Map.of(
            // TicketController：20 写端点 = 17 requireWrite + deleteTicket 用
            // @SaCheckRole("ADMIN")（方向 F）+ deleteAttachment 用 requireAdmin
            // + ai-analysis feedback 豁免（EXEMPT_MARKERS）
            "TicketController.java", 20,
            // AlertController：acknowledge + resolve + false-positive（A1 Feedback 回流）
            //（POST /webhook 在 AlertWebhookController、/webhook/zabbix 在
            // ZabbixWebhookController——两个都是机器回调面：调用方是 Alertmanager/Zabbix，
            // 带不了 Sa-Token，鉴权走 WebhookGuard（token/HMAC/限流），
            // 前后端契约由 AlertWebhookControllerWebTest / ZabbixWebhookControllerWebTest 覆盖）
            "AlertController.java", 3,
            // TicketPostmortemController：savePostmortem / draft / action-items 建改 = 4
            // （本文件 3 个 POST/PUT/PATCH + 1 PATCH，注意 draft-postmortem 也算写面）
            "TicketPostmortemController.java", 4
    );

    /** 有意豁免的端点特征（按方法体块子串匹配，其余漏防照常红） */
    private static final List<String> EXEMPT_MARKERS = List.of(
            "/ai-analysis/{analysisId}/feedback"   // 阅读反馈人人可点（见类 javadoc）
    );

    /** 写操作注解 */
    private static final Pattern WRITE_MAPPING = Pattern.compile("@(Post|Put|Delete|Patch)Mapping");

    /** 合格守卫的两种写法 */
    private static final Pattern GUARDED = Pattern.compile(
            "writeGuard\\.require(Write|Admin)\\s*\\(\\s*\\)|@(?:[\\w.]+\\.)?SaCheckRole");

    @Test
    @DisplayName("每个工单/告警写端点都有权限守卫")
    void everyOpsWriteEndpointIsGuarded() throws IOException {
        List<String> unguarded = new ArrayList<>();
        int scanned = 0;

        for (Map.Entry<String, Integer> e : OPS_CONTROLLERS.entrySet()) {
            Path f = CONTROLLERS.resolve(e.getKey());
            assertThat(Files.exists(f))
                    .as("控制器 %s 不存在——文件被重命名时本测试必须失败，而非静默跳过", e.getKey())
                    .isTrue();

            String code = stripCommentsKeepLines(Files.readString(f, StandardCharsets.UTF_8));

            List<Integer> starts = new ArrayList<>();
            Matcher m = WRITE_MAPPING.matcher(code);
            while (m.find()) {
                starts.add(m.start());
            }

            assertThat(starts.size())
                    .as("%s 写端点数漂移：登记 %d，实扫 %d。新增端点请同步守卫与本表；删除端点请同步本表",
                            e.getKey(), e.getValue(), starts.size())
                    .isEqualTo(e.getValue());

            for (int i = 0; i < starts.size(); i++) {
                int from = starts.get(i);
                int to = (i + 1 < starts.size()) ? starts.get(i + 1) : code.length();
                String block = code.substring(from, to);
                boolean exempt = EXEMPT_MARKERS.stream().anyMatch(block::contains);
                if (!exempt && !GUARDED.matcher(block).find()) {
                    unguarded.add("%s:%d".formatted(e.getKey(), lineOf(code, from)));
                }
                scanned++;
            }
        }

        assertThat(unguarded)
                .as("以下写端点无权限守卫——OpsWriteGuard.requireWrite()/requireAdmin() 或 @SaCheckRole 二选一"
                        + "（有意豁免要在本测试 EXEMPT_MARKERS 登记理由）")
                .isEmpty();
        assertThat(scanned).isGreaterThanOrEqualTo(26);
    }

    /**
     * 剥注释但保留换行，使报出的行号与源文件一致（同 KnowledgeWriteGuardCoverageTest；
     * 93 号栽在行号错位上，且本项目 javadoc 常引用守卫注解，不剥会把说明当守卫）。
     */
    private static String stripCommentsKeepLines(String src) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("(?s)/\\*.*?\\*/").matcher(src);
        int last = 0;
        while (m.find()) {
            sb.append(src, last, m.start());
            sb.append("\n".repeat((int) m.group().chars().filter(c -> c == '\n').count()));
            last = m.end();
        }
        sb.append(src.substring(last));
        return sb.toString().replaceAll("(?m)//[^\\n]*", "");
    }

    private static int lineOf(String code, int pos) {
        return (int) code.substring(0, pos).chars().filter(c -> c == '\n').count() + 1;
    }
}

package com.devops.agent.contract;

import com.devops.agent.common.dto.ApiCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 错误码契约。
 *
 * <h3>背景</h3>
 * <p>
 * {@link com.devops.agent.common.dto.ApiResponse} 的 javadoc 一直写着
 * 「非 0 见错误码表」，但这张表从来不存在——33 处
 * {@code ApiResponse.error(40004, ...)} 全是裸写的五位数字，散在 9 个文件里。
 * 本轮抽出 {@link ApiCode} 并全量替换，本测试负责让它不再退回去。
 * </p>
 *
 * <h3>裸数字的实际代价</h3>
 * <ol>
 *   <li><b>没有唯一真相源</b>：新增错误分支时写 40001 还是 40004 只能靠翻别处代码猜。
 *       猜错了前端会走进错误的处理分支——把「参数不合法」当成「资源不存在」，
 *       页面显示空状态而不是错误提示，用户完全不知道自己填错了；</li>
 *   <li><b>改不动</b>：想知道 40103 用在哪几处只能全文搜数字，
 *       而数字会撞上端口号、超时毫秒数、测试夹具里的无关常量。</li>
 * </ol>
 *
 * @author OpsBrain AI
 * @since 2026-08-28
 */
@DisplayName("API 错误码契约")
class ApiCodeContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/devops/agent");

    /** {@code ApiResponse.error(数字字面量, ...)} —— 本轮消灭的写法 */
    private static final Pattern LITERAL_ERROR = Pattern.compile(
            "ApiResponse\\.error\\s*\\(\\s*(\\d+)");

    @Test
    @DisplayName("不得再用数字字面量调用 ApiResponse.error")
    void noLiteralErrorCodes() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scannedFiles = 0;

        for (Path f : javaFiles()) {
            // 剥注释：ApiCode 与本类的 javadoc 里都引用了
            // ApiResponse.error(40004, ...) 来说明成因，
            // 不剥会被自己的说明文字打中而永远失败
            // （84 号静默 catch 扫描器踩过这个坑）
            String code = stripCommentsKeepLines(
                    Files.readString(f, StandardCharsets.UTF_8));
            scannedFiles++;

            Matcher m = LITERAL_ERROR.matcher(code);
            while (m.find()) {
                offenders.add(rel(f) + " L" + lineOf(code, m.start())
                        + " → ApiResponse.error(" + m.group(1) + ", ...)");
            }
        }

        // 扫描器自检：目录读空或正则脱节时主断言恒真
        assertThat(scannedFiles)
                .as("应当扫到源文件。为 0 说明目录读取失败，此时主断言形同虚设")
                .isGreaterThan(100);

        assertThat(offenders)
                .as("以下调用点直接写了错误码数字。请改用 ApiCode 常量——"
                        + "裸数字没有唯一真相源，新增错误分支时只能靠翻别处代码猜该写哪个码；"
                        + "猜错了前端会走进错误的处理分支"
                        + "（如把「参数不合法」当成「资源不存在」，页面显示空状态而非错误提示）。"
                        + "若确实需要新的码，请先在 ApiCode 里定义")
                .isEmpty();
    }

    @Test
    @DisplayName("错误码取值符合 4xxxx/5xxxx 分区，且互不重复")
    void codesAreWellFormedAndUnique() {
        Map<Integer, String> seen = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        for (Field f : ApiCode.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            int v;
            try {
                v = f.getInt(null);
            } catch (IllegalAccessException e) {
                problems.add(f.getName() + " 不可读：" + e.getMessage());
                continue;
            }

            // 重复码是最危险的：两个语义共用一个数字，前端无从区分。
            // 而人工 review 很难发现——两个常量可能隔着几十行
            String prev = seen.put(v, f.getName());
            if (prev != null) {
                problems.add("码 " + v + " 被 " + prev + " 与 " + f.getName()
                        + " 同时使用——两个语义共用一个数字，前端无法区分");
            }

            if (v == 0) {
                continue;  // SUCCESS
            }
            if (v < 40000 || v >= 60000) {
                problems.add(f.getName() + " = " + v + " 不在 4xxxx/5xxxx 区间");
            }
        }

        assertThat(seen)
                .as("ApiCode 应当定义了错误码。为空说明反射读取失败，主断言形同虚设")
                .hasSizeGreaterThanOrEqualTo(7);

        assertThat(problems)
                .as("错误码分区规则：4xxxx 客户端侧、5xxxx 服务端侧，且不得重复")
                .isEmpty();
    }

    @Test
    @DisplayName("每个 ApiCode 取值都能在 BizError 权威码表里找到")
    void everyApiCodeExistsInBizError() throws IOException {
        // BizError 才是唯一权威码表：它带 HTTP 状态映射、重试语义，
        // 且前端 constants/bizCode.ts 与它一一对应、由 vitest 跨端校验。
        // ApiCode 只是给 ApiResponse.error(int,...) 用的取值别名。
        //
        // 这条断言是上一轮缺失的那道闸：当时 ApiCode.NOT_FOUND 被定义成 40004，
        // 而 40004 在 BizError 里是 STATE_CONFLICT（当前状态不允许该操作），
        // 资源不存在其实是 40400。两套码表各写各的，后端测试全绿，
        // 前端却会把「已作废的工单不能改状态」渲染成「工单不存在」的空页面。
        Path bizError = MAIN.resolve("common/error/BizError.java");
        String src = Files.readString(bizError, StandardCharsets.UTF_8);

        Map<Integer, String> authoritative = new LinkedHashMap<>();
        Matcher em = Pattern.compile("(?m)^\\s*([A-Z_]+)\\((\\d{5}),\\s*HttpStatus\\.")
                .matcher(src);
        while (em.find()) {
            authoritative.put(Integer.parseInt(em.group(2)), em.group(1));
        }

        assertThat(authoritative)
                .as("应当从 BizError 解析出错误码。为空说明枚举格式变了，"
                        + "本断言形同虚设——请同步修正这里的正则")
                .hasSizeGreaterThanOrEqualTo(15);

        List<String> orphans = new ArrayList<>();
        for (Field f : ApiCode.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            int v;
            try {
                v = f.getInt(null);
            } catch (IllegalAccessException e) {
                orphans.add(f.getName() + " 不可读");
                continue;
            }
            if (v == 0) {
                continue;  // SUCCESS 不是错误码
            }
            if (!authoritative.containsKey(v)) {
                orphans.add("ApiCode." + f.getName() + " = " + v
                        + " 在 BizError 里没有对应项");
            }
        }

        assertThat(orphans)
                .as("ApiCode 不得定义 BizError 里没有的码。孤儿码会绕过前端的 "
                        + "BIZ_ERRORS 文案表，用户只看到一句无意义的兜底提示，"
                        + "而开发侧没有任何信号。新增错误码请先加到 BizError"
                        + "（那里才有 HTTP 状态映射、重试语义与前端文案的配套）")
                .isEmpty();
    }

    @Test
    @DisplayName("常量取值与前端已硬编码的数字保持一致（跨端契约）")
    void valuesMatchFrontendContract() {
        // 前端 api/types.ts 等处同样硬编码了这些数字。本轮只做后端重命名、
        // 不动数值，正是为了不破坏这个既成契约。
        //
        // 这几条断言看起来像「重复写了一遍常量」，但它防的是一类真实误改：
        // 有人觉得「40100 和 40101 太像了，改成 40102 更清楚」——
        // 后端测试全绿，而前端 auth.ts 里判断 40100 的分支再也不会命中，
        // 登录失败会退化成通用错误提示。改数值必须前后端一起改
        assertThat(ApiCode.BAD_REQUEST).isEqualTo(40001);
        // 40400 而非 40004 —— 后者是 STATE_CONFLICT，语义完全不同
        assertThat(ApiCode.NOT_FOUND).isEqualTo(40400);
        assertThat(ApiCode.STATE_CONFLICT).isEqualTo(40004);
        assertThat(ApiCode.ENDPOINT_DEPRECATED).isEqualTo(40010);
        assertThat(ApiCode.LOGIN_FAILED).isEqualTo(40100);
        assertThat(ApiCode.UNAUTHORIZED).isEqualTo(40101);
        assertThat(ApiCode.FORBIDDEN).isEqualTo(40103);
        assertThat(ApiCode.INTERNAL_ERROR).isEqualTo(50001);
        assertThat(ApiCode.SUCCESS).isEqualTo(0);
    }

    // ==================== 辅助 ====================

    private static List<Path> javaFiles() throws IOException {
        assertThat(Files.isDirectory(MAIN))
                .as("源码目录 %s 不存在——目录被重构时本测试必须失败，而非静默跳过", MAIN)
                .isTrue();
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    // ApiCode 自身的 javadoc 举例说明了旧写法，剥注释后已无影响，
                    // 这里不排除它——排除会掩盖「有人在常量类里也写了裸调用」
                    .sorted().toList();
        }
    }

    /**
     * 剥注释但保留换行，使报出的行号与源文件一致。
     *
     * <p>直接删除块注释会让行号错位，报错指向错误的行——
     * 93 号踩过这个坑，当时差点据此误判整个扫描结果不可信。</p>
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

    private static String rel(Path p) {
        return MAIN.relativize(p).toString();
    }
}

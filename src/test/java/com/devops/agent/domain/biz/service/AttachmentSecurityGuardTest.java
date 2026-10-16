package com.devops.agent.domain.biz.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 附件安全卫士测试
 * <p>
 * 文件上传是最常见的 Web 攻击面，此处重点覆盖各类绕过手法。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
class AttachmentSecurityGuardTest {

    private AttachmentSecurityGuard guard;

    @BeforeEach
    void setUp() {
        guard = new AttachmentSecurityGuard();
        // @Value 在单测中不生效，手工注入
        ReflectionTestUtils.setField(guard, "maxFileSize", 20971520L);
        ReflectionTestUtils.setField(guard, "allowedExtensionsRaw",
                "log,txt,json,yaml,yml,xml,csv,md,conf,png,jpg,pdf,zip,gz");
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private MockMultipartFile file(String name) {
        return file(name, "test content".getBytes());
    }

    // ==================== 正常放行 ====================

    @ParameterizedTest
    @DisplayName("白名单内的扩展名应放行")
    @ValueSource(strings = {
            "app.log", "error.txt", "config.json", "deploy.yaml",
            "data.csv", "README.md", "nginx.conf", "screenshot.png",
            "report.pdf", "logs.zip", "dump.gz"
    })
    void shouldAcceptWhitelistedExtensions(String name) {
        assertDoesNotThrow(() -> guard.validate(file(name)),
                "应放行合法文件: " + name);
    }

    @Test
    @DisplayName("扩展名大小写不敏感")
    void shouldAcceptUppercaseExtension() {
        assertDoesNotThrow(() -> guard.validate(file("APP.LOG")));
        assertDoesNotThrow(() -> guard.validate(file("Report.PDF")));
    }

    @Test
    @DisplayName("含中文与空格的文件名应放行")
    void shouldAcceptChineseAndSpaceInName() {
        assertDoesNotThrow(() -> guard.validate(file("生产环境 错误日志.log")));
    }

    // ==================== 可执行文件拦截 ====================

    @ParameterizedTest
    @DisplayName("可执行类型应拒绝（白名单外）")
    @ValueSource(strings = {
            "shell.sh", "backdoor.jsp", "exploit.php", "malware.exe",
            "script.bat", "payload.ps1", "hack.py", "trojan.jar",
            "lib.dll", "run.cmd", "app.vbs"
    })
    void shouldRejectExecutables(String name) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate(file(name)),
                "应拒绝可执行文件: " + name);
        assertTrue(ex.getMessage().contains("不支持的文件类型")
                        || ex.getMessage().contains("可执行类型"),
                "错误消息应说明原因，实际: " + ex.getMessage());
    }

    // ==================== 双扩展名绕过 ====================

    @ParameterizedTest
    @DisplayName("双扩展名绕过应拒绝：末位在白名单但中间藏危险类型")
    @ValueSource(strings = {
            "shell.jsp.log",        // 经典 JSP 绕过
            "backdoor.php.txt",
            "malware.exe.json",
            "script.sh.log",
            "payload.jar.zip",
            "webshell.asp.png",
            "run.bat.csv"
    })
    void shouldRejectDoubleExtensionBypass(String name) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate(file(name)),
                "应拦截双扩展名绕过: " + name);
        assertTrue(ex.getMessage().contains("可执行类型标识"),
                "应明确指出双扩展名问题，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("合法的多点文件名不应被误杀")
    void shouldAcceptLegitimateMultiDotNames() {
        // 版本号、日期等含点的正常文件名
        assertDoesNotThrow(() -> guard.validate(file("app.2026-08-09.log")));
        assertDoesNotThrow(() -> guard.validate(file("nginx.access.log")));
        assertDoesNotThrow(() -> guard.validate(file("v1.2.3.json")));
    }

    // ==================== 路径穿越 ====================

    @ParameterizedTest
    @DisplayName("含穿越序列 ../ 或 ..\\ 的文件名应拒绝")
    @ValueSource(strings = {
            "../../../etc/passwd.log",
            "..\\..\\windows\\system32\\cfg.txt",
            "logs/../../../secret.log",
            "a/../b.log"
    })
    void shouldRejectPathTraversal(String name) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate(file(name)),
                "应拒绝路径穿越: " + name);
        assertTrue(ex.getMessage().contains("非法路径"),
                "应明确指出路径问题，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("含路径前缀的文件名应剥离路径后正常校验")
    void shouldStripPathPrefix() {
        // 旧版 IE / curl -F 会提交完整路径，剥离后是合法文件
        assertDoesNotThrow(() -> guard.validate(file("C:\\Users\\ops\\app.log")));
        assertDoesNotThrow(() -> guard.validate(file("/var/log/nginx/error.log")));
    }

    @Test
    @DisplayName("剥离路径后若为可执行类型仍应拒绝")
    void shouldRejectExecutableAfterStrippingPath() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate(file("/tmp/malware.sh")));
    }

    // ==================== 畸形输入 ====================

    @Test
    @DisplayName("空文件应拒绝")
    void shouldRejectEmptyFile() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate(file("empty.log", new byte[0])));
        assertTrue(ex.getMessage().contains("为空"));
    }

    @Test
    @DisplayName("null 文件应拒绝")
    void shouldRejectNullFile() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(null));
    }

    @Test
    @DisplayName("无扩展名应拒绝")
    void shouldRejectNoExtension() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate(file("Dockerfile")));
        assertTrue(ex.getMessage().contains("扩展名"));
    }

    @Test
    @DisplayName("隐藏文件（以点开头且无其他点）应拒绝")
    void shouldRejectHiddenFile() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(file(".htaccess")));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(file(".env")));
    }

    @Test
    @DisplayName("以点结尾应拒绝")
    void shouldRejectTrailingDot() {
        assertThrows(IllegalArgumentException.class, () -> guard.validate(file("app.log.")));
    }

    @Test
    @DisplayName("超长文件名应拒绝")
    void shouldRejectOverlongName() {
        String longName = "a".repeat(300) + ".log";
        var ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate(file(longName)));
        assertTrue(ex.getMessage().contains("过长"));
    }

    @Test
    @DisplayName("超过大小限制应拒绝并给出可读提示")
    void shouldRejectOversizeFile() {
        byte[] big = new byte[20971521];   // 20MB + 1 byte
        var ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate(file("huge.log", big)));
        assertTrue(ex.getMessage().contains("MB"),
                "提示应用 MB 而非字节数，实际: " + ex.getMessage());
    }

    // ==================== 对象键生成 ====================

    @Test
    @DisplayName("对象键应为日期分区 + UUID，不含原始文件名")
    void objectKeyShouldNotContainOriginalName() {
        String key = guard.generateObjectKey("生产环境错误.log");

        assertFalse(key.contains("生产环境错误"), "对象键不应含原始文件名");
        assertTrue(key.matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.log"),
                "对象键格式应为 yyyy/MM/dd/{uuid}.ext，实际: " + key);
    }

    @Test
    @DisplayName("对象键对路径穿越文件名也安全")
    void objectKeyShouldBeSafeForTraversalName() {
        String key = guard.generateObjectKey("../../../etc/passwd.log");

        assertFalse(key.contains(".."), "对象键不应含 ..");
        assertFalse(key.contains("etc"), "对象键不应含原路径片段");
        assertTrue(key.matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.log"));
    }

    @Test
    @DisplayName("对象键应唯一")
    void objectKeysShouldBeUnique() {
        String k1 = guard.generateObjectKey("same.log");
        String k2 = guard.generateObjectKey("same.log");
        assertNotEquals(k1, k2, "同名文件应生成不同对象键");
    }

    @Test
    @DisplayName("无扩展名时对象键回退为 .bin")
    void objectKeyShouldFallbackToBin() {
        String key = guard.generateObjectKey("noext");
        assertTrue(key.endsWith(".bin"), "实际: " + key);
    }

    // ==================== Content-Disposition 注入 ====================

    @Test
    @DisplayName("文件名清洗应阻断 HTTP 头注入：遇控制字符截断")
    void sanitizeShouldBlockHeaderInjection() {
        // 攻击者用换行符注入额外 HTTP 头
        String malicious = "file.log\r\nSet-Cookie: admin=true";
        String safe = guard.sanitizeForDisposition(malicious);

        assertFalse(safe.contains("\r"), "不应含回车");
        assertFalse(safe.contains("\n"), "不应含换行");
        assertFalse(safe.contains("Set-Cookie"),
                "注入载荷应被完整截断而非仅替换分隔符，实际: " + safe);
        assertEquals("file.log", safe, "应保留控制字符前的合法部分");
    }

    @Test
    @DisplayName("文件名全为控制字符时回退为默认值")
    void sanitizeShouldFallbackWhenAllControlChars() {
        assertEquals("attachment", guard.sanitizeForDisposition("\r\n\t"));
    }

    @Test
    @DisplayName("文件名清洗应转义引号")
    void sanitizeShouldEscapeQuotes() {
        String safe = guard.sanitizeForDisposition("evil\"name.log");
        assertFalse(safe.contains("\""), "不应含双引号，实际: " + safe);
    }

    @Test
    @DisplayName("文件名清洗应保留正常中文")
    void sanitizeShouldPreserveChinese() {
        String safe = guard.sanitizeForDisposition("生产日志.log");
        assertEquals("生产日志.log", safe);
    }

    @Test
    @DisplayName("null 文件名清洗应回退为默认值")
    void sanitizeShouldHandleNull() {
        assertEquals("attachment", guard.sanitizeForDisposition(null));
    }
}
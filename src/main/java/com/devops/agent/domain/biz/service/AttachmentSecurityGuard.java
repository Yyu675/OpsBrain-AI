package com.devops.agent.domain.biz.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 附件上传安全卫士
 * <p>
 * 文件上传是最常见的 Web 攻击面。本类集中处理以下风险：
 * <ol>
 *   <li><b>可执行文件上传</b>：用扩展名<b>白名单</b>而非黑名单。
 *       黑名单永远列不全（.exe .bat .sh .jsp .php .dll .so .jar
 *       .msi .vbs .ps1 .cgi .pl …），漏一个即成上传后门</li>
 *   <li><b>路径穿越</b>：对象键由服务端生成，绝不使用用户提交的文件名。
 *       原始文件名仅存库用于展示</li>
 *   <li><b>双扩展名绕过</b>：如 {@code shell.jsp.log}——取<b>最后一个</b>
 *       点后的扩展名校验，同时检测文件名中是否含危险中间扩展名</li>
 *   <li><b>超大文件</b>：双层限制（Tomcat multipart + 业务校验）</li>
 *   <li><b>空文件与畸形名</b>：显式拒绝</li>
 * </ol>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@Slf4j
@Component
public class AttachmentSecurityGuard {

    /**
     * 危险扩展名，出现在文件名任意位置即拒绝
     * <p>
     * 用于拦截 {@code shell.jsp.log} 这类双扩展名绕过：
     * 末位扩展名 {@code .log} 在白名单内，但中间藏着 {@code .jsp}。
     * 某些服务器配置下会按中间扩展名解析执行。
     * </p>
     */
    private static final Set<String> DANGEROUS_TOKENS = Set.of(
            "jsp", "jspx", "php", "php3", "php4", "php5", "phtml",
            "asp", "aspx", "ashx", "asmx", "cer",
            "exe", "dll", "so", "bin", "msi", "com", "scr",
            "sh", "bash", "zsh", "csh", "ksh",
            "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh",
            "jar", "war", "ear", "class",
            "py", "pyc", "rb", "pl", "cgi", "lua",
            "htaccess", "htpasswd"
    );

    /** 文件名长度上限，与 DDL 的 VARCHAR(255) 对齐 */
    private static final int MAX_NAME_LENGTH = 255;

    @Value("${devops.storage.attachment.max-file-size:20971520}")
    private long maxFileSize;

    @Value("${devops.storage.attachment.allowed-extensions:log,txt,json}")
    private String allowedExtensionsRaw;

    private Set<String> allowedExtensions;

    /**
     * 懒加载白名单集合
     */
    private Set<String> allowed() {
        if (allowedExtensions == null) {
            allowedExtensions = new HashSet<>(
                    Arrays.stream(allowedExtensionsRaw.split(","))
                            .map(String::trim)
                            .map(String::toLowerCase)
                            .filter(s -> !s.isEmpty())
                            .toList());
            log.info("🛡️ [AttachGuard] 扩展名白名单已加载: {}", allowedExtensions);
        }
        return allowedExtensions;
    }

    /**
     * 校验上传文件
     *
     * @param file 上传文件
     * @throws IllegalArgumentException 校验失败，消息可直接展示给用户
     */
    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }

        // 大小：业务层再校验一次。
        // Tomcat 的 max-file-size 会先拦，但配置可能被改大，
        // 业务层是最终防线
        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException(String.format(
                    "文件超过大小限制（%.1f MB > %.1f MB）",
                    file.getSize() / 1048576.0, maxFileSize / 1048576.0));
        }

        String rawName = file.getOriginalFilename();
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("文件名为空");
        }

        // 路径穿越检查必须在**原始文件名**上做。
        // 若先剥离路径再查，"../../../etc/passwd.log" 会变成
        // "passwd.log"，穿越特征已丢失。
        //
        // 区分两种情况：
        //   - 穿越序列 ../ 或 ..\  → 恶意信号，拒绝
        //   - 普通路径前缀 /var/log/ 或 C:\Users\ → 客户端行为，剥离后放行
        //     （旧版 IE、curl -F 会提交完整路径）
        if (rawName.contains("../") || rawName.contains("..\\")) {
            log.warn("🚫 [AttachGuard] 拒绝含路径穿越序列的文件名: {}", rawName);
            throw new IllegalArgumentException("文件名包含非法路径字符");
        }

        // 取纯文件名：剥离可能存在的路径前缀
        String baseName = extractBaseName(rawName);
        if (baseName.isBlank()) {
            throw new IllegalArgumentException("文件名非法");
        }
        if (baseName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("文件名过长（上限 " + MAX_NAME_LENGTH + " 字符）");
        }

        // 隐藏文件与无扩展名文件
        int lastDot = baseName.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == baseName.length() - 1) {
            throw new IllegalArgumentException("文件必须有扩展名");
        }

        String extension = baseName.substring(lastDot + 1).toLowerCase();

        // 白名单校验
        if (!allowed().contains(extension)) {
            log.warn("🚫 [AttachGuard] 扩展名不在白名单: {} | 文件={}", extension, baseName);
            throw new IllegalArgumentException(
                    "不支持的文件类型 ." + extension + "，允许：" + String.join("、", allowed()));
        }

        // 双扩展名绕过检测：检查除末位外的所有分段
        String[] segments = baseName.toLowerCase().split("\\.");
        for (int i = 0; i < segments.length - 1; i++) {
            if (DANGEROUS_TOKENS.contains(segments[i])) {
                log.warn("🚫 [AttachGuard] 检测到双扩展名绕过尝试: {} | 危险分段={}",
                        baseName, segments[i]);
                throw new IllegalArgumentException(
                        "文件名包含可执行类型标识「." + segments[i] + "」，已拒绝");
            }
        }
    }

    /**
     * 生成对象键
     * <p>
     * 格式：{@code yyyy/MM/dd/{uuid}.{ext}}
     * </p>
     * <p>
     * 三个设计点：
     * <ul>
     *   <li><b>UUID 而非原始文件名</b>：彻底消除路径穿越与文件名冲突</li>
     *   <li><b>日期分区</b>：便于按时间批量归档/清理，也避免单目录对象过多</li>
     *   <li><b>保留扩展名</b>：便于对象存储侧按类型统计，且下载时
     *       Content-Type 推断更准</li>
     * </ul>
     *
     * @param originalName 原始文件名，仅用于提取扩展名
     * @return 对象键
     */
    public String generateObjectKey(String originalName) {
        String baseName = extractBaseName(originalName == null ? "" : originalName);
        int lastDot = baseName.lastIndexOf('.');
        String ext = (lastDot > 0 && lastDot < baseName.length() - 1)
                ? baseName.substring(lastDot + 1).toLowerCase()
                : "bin";

        java.time.LocalDate today = java.time.LocalDate.now();
        return String.format("%d/%02d/%02d/%s.%s",
                today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                UUID.randomUUID().toString().replace("-", ""), ext);
    }

    /**
     * 清洗文件名用于下载展示
     * <p>
     * 移除控制字符与引号，防止 Content-Disposition 头注入
     * （攻击者可用换行符注入额外 HTTP 头）。
     * </p>
     */
    public String sanitizeForDisposition(String name) {
        if (name == null || name.isBlank()) return "attachment";

        // 遇首个控制字符即截断，而非替换。
        // 文件名中出现 CRLF 后的内容几乎必然是注入载荷
        // （如 "file.log\r\nSet-Cookie: admin=true"），
        // 仅替换分隔符会把载荷文本留在文件名里。
        int cut = -1;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                cut = i;
                break;
            }
        }
        String s = (cut >= 0) ? name.substring(0, cut) : name;

        // 引号与反斜杠会破坏 Content-Disposition 的 filename="..." 结构
        s = s.replaceAll("[\"\\\\]", "_").trim();

        return s.isEmpty() ? "attachment" : s;
    }

    /**
     * 提取纯文件名（剥离路径）
     * <p>同时处理 Unix 的 / 与 Windows 的 \ 分隔符。</p>
     */
    private String extractBaseName(String path) {
        String s = path.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return slash >= 0 ? s.substring(slash + 1).trim() : s;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }
}
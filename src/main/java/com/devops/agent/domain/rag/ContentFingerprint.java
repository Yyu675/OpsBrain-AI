package com.devops.agent.domain.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 内容指纹：精确去重 + 近似去重
 * <p>
 * RAG 不去重的后果比想象中严重：topK=3 若全是同一段内容的三个副本，
 * 模型实际只看到 1/3 的信息量；更隐蔽的是模型会因「多个来源都这么说」
 * 而<b>提高置信度</b>——这是幻觉的隐性来源。
 * </p>
 *
 * <h3>两层指纹</h3>
 * <ol>
 *   <li><b>SHA-256</b>（精确）：内容完全相同则跳过。零成本，用于
 *       「文档未变则不重新向量化」这一成本控制关键路径</li>
 *   <li><b>SimHash</b>（近似）：64 位指纹，汉明距离 ≤ 3 视为近似重复。
 *       捕捉「两篇手册抄了同一段官方文档」——精确哈希对此无能为力，
 *       但检索会返回两条几乎相同的内容</li>
 * </ol>
 *
 * <h3>为何不用向量做去重</h3>
 * <p>
 * 向量级去重（余弦 &gt; 0.98）能抓「改写但同义」，但必须<b>先付向量化成本</b>
 * 才能判重——即先花钱再发现是重复。SimHash 在向量化之前就能拦掉大部分，
 * 顺序上更经济。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-10
 */
@Slf4j
@Component
public class ContentFingerprint {

    /**
     * 近似重复的汉明距离阈值
     * <p>
     * <b>10 由实测标定，非经验拍定</b>。见 {@code SimhashCalibrationTest}：
     * </p>
     * <pre>
     *   应判重复：完全相同=0  标点差异=0  长文改一词=3  一处措辞=7
     *   应判不同：不同错误码=24  同主题不同根因=28  完全不同主题=25
     * </pre>
     * <p>
     * 两类之间存在 7→24 的宽间隔，阈值取 10 落在间隔中部，两侧都有余量。
     * </p>
     * <p>
     * 初版取 3 过紧：中文按 bigram 切分时，改一个双字词会影响约 3 个
     * bigram，在 64 位指纹上足以翻 7 位——「表示」改「说明」这种
     * 纯措辞差异就会被判为不同内容，跨文档抄录检测因此失效。
     * </p>
     * <p>
     * 不宜再调大：24 是实测的「不同知识」下界，逼近它会把
     * 同主题不同根因（如 CrashLoopBackOff 由异常退出 vs 由 OOM 引起）
     * 误判为重复，而这是两条独立且都有价值的运维知识。
     * </p>
     */
    public static final int SIMHASH_THRESHOLD = 10;

    /**
     * 分词：按非中日韩字符与非字母数字切分
     * <p>
     * 中文按字切分而非按词——不引入分词器依赖。对 SimHash 这种
     * 统计指纹而言，按字切分的判重效果足够，且避免了分词器
     * 版本差异导致同一文本指纹不一致的问题。
     * </p>
     */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsHan}\\p{Alnum}]+");

    // ==================== 精确指纹 ====================

    /**
     * 计算内容的 SHA-256 摘要
     * <p>
     * <b>先归一化再哈希</b>：否则改动行尾空格、Windows/Unix 换行符差异
     * 都会产生不同哈希，导致「内容实质未变却重新向量化」，白花成本。
     * </p>
     *
     * @return 64 字符小写十六进制，入参为 null 时返回 null
     */
    public String sha256(String content) {
        if (content == null) return null;
        String normalized = normalize(content);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制支持的算法，正常不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ==================== 近似指纹 ====================

    /**
     * 计算 SimHash 64 位指纹
     * <p>
     * 算法：对每个特征（token）取哈希，按位投票——该位为 1 则权重累加，
     * 为 0 则累减；最终各位取符号得到指纹。内容相似则指纹的汉明距离小。
     * </p>
     * <p>
     * 用 {@code long} 存储而非 {@code String}：数据库里 {@code BIGINT}
     * 比 {@code CHAR(16)} 省空间，且汉明距离可用位运算直接算。
     * </p>
     *
     * @return 64 位指纹；内容为空返回 0
     */
    public long simhash(String content) {
        if (content == null || content.isBlank()) return 0L;

        List<String> tokens = tokenize(normalize(content));
        if (tokens.isEmpty()) return 0L;

        // 64 个位的权重累加器
        int[] bits = new int[64];

        for (String token : tokens) {
            long h = hash64(token);
            for (int i = 0; i < 64; i++) {
                // 第 i 位为 1 则加权，为 0 则减权
                if (((h >>> i) & 1L) == 1L) {
                    bits[i]++;
                } else {
                    bits[i]--;
                }
            }
        }

        // 取符号：正数置 1，负数与 0 置 0
        long fingerprint = 0L;
        for (int i = 0; i < 64; i++) {
            if (bits[i] > 0) {
                fingerprint |= (1L << i);
            }
        }
        return fingerprint;
    }

    /**
     * 汉明距离：两个指纹不同位的个数
     * <p>用 {@code bitCount(a ^ b)} 一步算出，无需逐位比较。</p>
     */
    public int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /**
     * 判定两段内容是否近似重复
     */
    public boolean isNearDuplicate(long simhashA, long simhashB) {
        // 0 表示未计算或内容为空，不参与判重——
        // 否则两个「未计算」会被判为重复
        if (simhashA == 0L || simhashB == 0L) return false;
        return hammingDistance(simhashA, simhashB) <= SIMHASH_THRESHOLD;
    }

    // ==================== 内部实现 ====================

    /**
     * 内容归一化
     * <p>
     * 统一换行符、去行尾空白、压缩连续空行、去首尾空白。
     * 目的是让「实质相同但格式微异」的内容产出同一指纹。
     * </p>
     */
    public String normalize(String content) {
        if (content == null) return "";
        return content
                // Windows/Mac 换行统一为 \n：跨平台编辑同一文档不应改变指纹
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                // 去每行行尾空白
                .replaceAll("[ \\t]+\\n", "\n")
                // 三个以上连续换行压成两个（Markdown 里多空行无语义差别）
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String t : TOKEN_SPLIT.split(text.toLowerCase())) {
            if (t.isEmpty()) continue;
            // 中文按 2 字滑窗切分（bigram）：单字信息量太低，
            // 会让不同内容的指纹趋同
            if (isMostlyHan(t) && t.length() > 1) {
                for (int i = 0; i < t.length() - 1; i++) {
                    tokens.add(t.substring(i, i + 2));
                }
            } else {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private boolean isMostlyHan(String s) {
        int han = 0;
        for (char c : s.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) han++;
        }
        return han * 2 > s.length();
    }

    /**
     * 64 位字符串哈希（FNV-1a）
     * <p>
     * 不用 {@code String.hashCode()}：它只有 32 位，且分布性差，
     * 会让 SimHash 高位始终为 0，判重失效。
     * </p>
     */
    private long hash64(String s) {
        final long FNV_OFFSET = 0xcbf29ce484222325L;
        final long FNV_PRIME = 0x100000001b3L;
        long hash = FNV_OFFSET;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}

package com.devops.agent.domain.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 行级 LCS（Longest Common Subsequence）文档差异对比。
 * <p>
 * 纯 Java 实现，零外部依赖。按 {@code \n} 拆分行，动态规划求 LCS，
 * 回溯生成三段式结果（EQUAL / DELETE / INSERT）。
 * </p>
 *
 * <p><b>使用场景</b>：知识库文档版本历史对比（{@code GET /docs/{id}/compare}）。
 * 切片级 diff 不可行（6.21 已论证：文档中间插一句话，其后所有切片起止位置
 * 整体漂移，旧新切片无法对应），故对原文逐行做文档级 diff。</p>
 *
 * <p><b>快速短路</b>：空内容 / 相同内容直接返回，不执行 LCS 计算。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-13
 */
public class KnowledgeDocDiff {

    /**
     * 对比两个版本的行级差异。
     *
     * @param oldText 旧版本全文
     * @param newText 新版本全文
     * @return 差异段列表，按原始顺序排列
     */
    public static List<DiffSegment> diff(String oldText, String newText) {
        // 快速短路：两者相同
        if (oldText == null && newText == null) {
            return List.of();
        }
        if (oldText == null) {
            oldText = "";
        }
        if (newText == null) {
            newText = "";
        }
        if (oldText.equals(newText)) {
            String[] lines = newText.split("\n", -1);
            // 空内容
            if (lines.length == 0 || (lines.length == 1 && lines[0].isEmpty())) {
                return List.of();
            }
            return List.of(new DiffSegment(DiffSegment.Type.EQUAL, List.of(lines)));
        }

        // 快速短路：旧版本为空 → 全部 INSERT
        if (oldText.isEmpty()) {
            String[] lines = newText.split("\n", -1);
            if (lines.length == 0 || (lines.length == 1 && lines[0].isEmpty())) {
                return List.of();
            }
            return List.of(new DiffSegment(DiffSegment.Type.INSERT, List.of(lines)));
        }

        // 快速短路：新版本为空 → 全部 DELETE
        if (newText.isEmpty()) {
            String[] lines = oldText.split("\n", -1);
            return List.of(new DiffSegment(DiffSegment.Type.DELETE, List.of(lines)));
        }

        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);

        return computeLcsDiff(oldLines, newLines);
    }

    /**
     * LCS 动态规划 + 回溯生成三段式 diff。
     * <p>
     * 空间 O(m×n)，时间 O(m×n)。m, n 为行数。
     * 知识库文档行数通常在数百级别，此算法够用。
     * 若未来出现数万行文档，可改为 Myers diff 或 Hirschberg 线性空间优化。
     * </p>
     */
    private static List<DiffSegment> computeLcsDiff(String[] oldLines, String[] newLines) {
        int m = oldLines.length;
        int n = newLines.length;

        // dp[i][j] = oldLines[0..i-1] 与 newLines[0..j-1] 的 LCS 长度
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // 回溯：从 dp[m][n] 走到 dp[0][0]
        List<DiffSegment> segments = new ArrayList<>();
        int i = m, j = n;

        // 临时缓冲区：从后往前回溯，先收集到临时列表再反转
        List<DiffSegment> reversed = new ArrayList<>();

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                // EQUAL：两行相同
                appendToBuffer(reversed, DiffSegment.Type.EQUAL, oldLines[i - 1]);
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                // INSERT：新版本独有的行
                appendToBuffer(reversed, DiffSegment.Type.INSERT, newLines[j - 1]);
                j--;
            } else if (i > 0) {
                // DELETE：旧版本独有的行
                appendToBuffer(reversed, DiffSegment.Type.DELETE, oldLines[i - 1]);
                i--;
            }
        }

        // 反转回正常顺序
        Collections.reverse(reversed);
        segments.addAll(reversed);

        // 合并相邻的同类型段（避免出现 EQUAL, EQUAL, DELETE, DELETE 等碎片）
        return mergeAdjacent(segments);
    }

    /**
     * 向缓冲区追加一行。若最后一段类型相同则合并，否则新建一段。
     */
    private static void appendToBuffer(List<DiffSegment> buffer, DiffSegment.Type type, String line) {
        if (!buffer.isEmpty()) {
            DiffSegment last = buffer.getLast();
            if (last.type() == type) {
                // 合并到现有段
                List<String> newLines = new ArrayList<>(last.lines());
                newLines.add(line);
                buffer.set(buffer.size() - 1, new DiffSegment(type, newLines));
                return;
            }
        }
        // 新建段
        buffer.add(new DiffSegment(type, new ArrayList<>(List.of(line))));
    }

    /**
     * 合并相邻的同类型段。LCS 回溯可能产生碎片化的交替段，
     * 合并后 diff 展示更清晰。
     */
    private static List<DiffSegment> mergeAdjacent(List<DiffSegment> segments) {
        if (segments.isEmpty()) {
            return segments;
        }

        List<DiffSegment> result = new ArrayList<>();
        DiffSegment current = segments.getFirst();

        for (int k = 1; k < segments.size(); k++) {
            DiffSegment next = segments.get(k);
            if (current.type() == next.type()) {
                // 合并
                List<String> mergedLines = new ArrayList<>(current.lines());
                mergedLines.addAll(next.lines());
                current = new DiffSegment(current.type(), mergedLines);
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);

        return result;
    }

    // ==================== 内部类型 ====================

    /**
     * 差异段：一段连续的相同类型行。
     */
    public record DiffSegment(Type type, List<String> lines) {

        public enum Type {
            /** 两版本共有的行（未变更） */
            EQUAL,
            /** 旧版本独有行（已删除） */
            DELETE,
            /** 新版本独有行（新增） */
            INSERT
        }
    }
}
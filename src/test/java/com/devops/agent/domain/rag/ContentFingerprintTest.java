package com.devops.agent.domain.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内容指纹测试
 * <p>
 * 去重判错的代价是双向的：判重过松会丢弃有效知识（漏答），
 * 判重过严则重复内容占满 topK（模型有效信息量下降且虚增置信度）。
 * 故两个方向都要测。
 * </p>
 */
class ContentFingerprintTest {

    private final ContentFingerprint fp = new ContentFingerprint();

    // ==================== 精确指纹 ====================

    @Test
    @DisplayName("相同内容产出相同 SHA-256")
    void sameContent_sameHash() {
        String c = "## Pod CrashLoopBackOff\n\n检查 kubectl logs 输出。";
        assertEquals(fp.sha256(c), fp.sha256(c));
    }

    @Test
    @DisplayName("换行符差异不应改变指纹——否则跨平台编辑会白花向量化成本")
    void lineEndingDifference_sameHash() {
        String unix = "第一行\n第二行\n第三行";
        String windows = "第一行\r\n第二行\r\n第三行";
        String mac = "第一行\r第二行\r第三行";

        assertEquals(fp.sha256(unix), fp.sha256(windows),
                "Windows 换行符不应产生不同指纹");
        assertEquals(fp.sha256(unix), fp.sha256(mac),
                "旧 Mac 换行符不应产生不同指纹");
    }

    @Test
    @DisplayName("行尾空白与多余空行不应改变指纹")
    void trailingWhitespace_sameHash() {
        String clean = "标题\n\n正文内容";
        String messy = "标题   \n\n\n\n正文内容\t\t\n  ";

        assertEquals(fp.sha256(clean), fp.sha256(messy),
                "格式微差不应触发重新向量化");
    }

    @Test
    @DisplayName("实质内容不同则指纹不同")
    void differentContent_differentHash() {
        assertNotEquals(
                fp.sha256("Pod 处于 CrashLoopBackOff 状态"),
                fp.sha256("Pod 处于 ImagePullBackOff 状态"),
                "不同错误码是不同知识，不能判为重复");
    }

    @Test
    @DisplayName("null 与空串安全处理")
    void nullAndEmpty_safe() {
        assertNull(fp.sha256(null));
        assertNotNull(fp.sha256(""), "空串应有确定指纹而非抛异常");
    }

    // ==================== 近似指纹 ====================

    @Test
    @DisplayName("完全相同内容的 SimHash 必然相同")
    void identicalContent_sameSimhash() {
        String c = "MySQL 主从延迟排查：先看 Seconds_Behind_Master，再查大事务。";
        assertEquals(fp.simhash(c), fp.simhash(c));
    }

    @Test
    @DisplayName("轻微改写应判为近似重复——这是跨文档抄录的典型形态")
    void minorRewrite_isNearDuplicate() {
        // 模拟两篇手册抄了同一段官方文档，只改了个别措辞
        String a = "Pod 反复重启，状态显示为 CrashLoopBackOff，表示容器启动后立即崩溃。"
                 + "常见原因包括应用程序异常退出、启动命令错误、依赖服务不可用。";
        String b = "Pod 反复重启，状态显示为 CrashLoopBackOff，说明容器启动后立即崩溃。"
                 + "常见原因包括应用程序异常退出、启动命令错误、依赖服务不可用。";

        long ha = fp.simhash(a);
        long hb = fp.simhash(b);
        int dist = fp.hammingDistance(ha, hb);

        assertTrue(fp.isNearDuplicate(ha, hb),
                "仅一处措辞差异应判为近似重复，实际汉明距离=" + dist);
    }

    @Test
    @DisplayName("不同主题不应判为近似重复——否则会丢弃有效知识")
    void differentTopic_notNearDuplicate() {
        String k8s = "Pod CrashLoopBackOff 排查：检查容器日志、启动命令与依赖服务就绪状态，"
                   + "确认 livenessProbe 配置是否过于激进导致容器被反复杀死。";
        String slb = "阿里云 SLB 健康检查失败排查：确认后端 ECS 安全组放行探测端口，"
                   + "检查健康检查路径返回 200，核对超时与重试次数配置。";

        long hk = fp.simhash(k8s);
        long hs = fp.simhash(slb);
        int dist = fp.hammingDistance(hk, hs);

        assertFalse(fp.isNearDuplicate(hk, hs),
                "K8s 与 SLB 是不同知识，误判重复会丢弃有效内容，汉明距离=" + dist);
    }

    @Test
    @DisplayName("同主题但内容实质不同，不应判为重复")
    void sameTopicDifferentContent_notNearDuplicate() {
        String a = "Pod CrashLoopBackOff 的原因是应用启动时抛出未捕获异常，"
                 + "需检查 kubectl logs 的堆栈输出定位代码缺陷。";
        String b = "Pod CrashLoopBackOff 的原因是内存超限被 OOMKilled，"
                 + "需调大 resources.limits.memory 或优化应用内存占用。";

        int dist = fp.hammingDistance(fp.simhash(a), fp.simhash(b));
        assertFalse(fp.isNearDuplicate(fp.simhash(a), fp.simhash(b)),
                "同主题不同根因是两条独立知识，汉明距离=" + dist);
    }

    @Test
    @DisplayName("空内容的 simhash 为 0 且不参与判重")
    void emptyContent_notDuplicate() {
        assertEquals(0L, fp.simhash(""));
        assertEquals(0L, fp.simhash(null));
        // 两个「未计算」不应被判为互相重复
        assertFalse(fp.isNearDuplicate(0L, 0L),
                "指纹为 0 表示未计算，不能据此判重");
    }

    @Test
    @DisplayName("汉明距离计算正确")
    void hammingDistance_correct() {
        assertEquals(0, fp.hammingDistance(0b1010L, 0b1010L));
        assertEquals(1, fp.hammingDistance(0b1010L, 0b1011L));
        assertEquals(4, fp.hammingDistance(0b0000L, 0b1111L));
    }

    @Test
    @DisplayName("SimHash 应用满 64 位——若高位恒为 0 则判重失效")
    void simhash_usesFullRange() {
        // 用足够长的差异文本，确认指纹不是只集中在低位
        // （String.hashCode() 只有 32 位，会让高 32 位恒为 0）
        long h1 = fp.simhash("Kubernetes 集群节点 NotReady 状态排查手册与处置流程说明");
        long h2 = fp.simhash("阿里云负载均衡后端服务器健康检查异常的完整诊断步骤");

        // 至少有一个指纹的高 32 位非零
        boolean highBitsUsed = (h1 >>> 32) != 0 || (h2 >>> 32) != 0;
        assertTrue(highBitsUsed,
                "指纹高位全为 0 说明底层哈希只有 32 位，判重会失效");
    }

    @Test
    @DisplayName("归一化不改变实质内容")
    void normalize_preservesContent() {
        String s = "  ## 标题  \n\n\n\n正文  \n";
        String n = fp.normalize(s);

        assertTrue(n.contains("## 标题"), "标题应保留");
        assertTrue(n.contains("正文"), "正文应保留");
        assertFalse(n.endsWith("\n"), "首尾空白应去除");
        assertFalse(n.contains("\n\n\n"), "连续空行应压缩");
    }
}

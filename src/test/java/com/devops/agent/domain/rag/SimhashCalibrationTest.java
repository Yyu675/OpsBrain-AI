package com.devops.agent.domain.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SimHash 阈值标定
 * <p>
 * 目的：测出「近似重复」与「不同知识」的汉明距离实际分布，据此定阈值。
 * 不能凭经验拍——阈值定错的代价是双向的：
 * 过松丢弃有效知识（漏答），过严则重复内容占满 topK。
 * </p>
 * <p>
 * 本类只输出数据不做断言，是标定工具而非回归测试。
 * </p>
 */
class SimhashCalibrationTest {

    private final ContentFingerprint fp = new ContentFingerprint();

    private void measure(String label, String a, String b) {
        int d = fp.hammingDistance(fp.simhash(a), fp.simhash(b));
        System.out.printf("%-40s 距离=%2d%n", label, d);
    }

    @Test
    @DisplayName("标定：各类内容对的汉明距离分布")
    void calibrate() {
        System.out.println("\n===== 应判为重复（距离应小）=====");

        measure("完全相同",
                "Pod 反复重启状态为 CrashLoopBackOff，容器启动后立即崩溃。",
                "Pod 反复重启状态为 CrashLoopBackOff，容器启动后立即崩溃。");

        measure("一处措辞（表示→说明）",
                "Pod 反复重启，状态显示为 CrashLoopBackOff，表示容器启动后立即崩溃。"
                        + "常见原因包括应用程序异常退出、启动命令错误、依赖服务不可用。",
                "Pod 反复重启，状态显示为 CrashLoopBackOff，说明容器启动后立即崩溃。"
                        + "常见原因包括应用程序异常退出、启动命令错误、依赖服务不可用。");

        measure("加一句话",
                "MySQL 主从延迟排查：查看 Seconds_Behind_Master 指标，定位大事务。",
                "MySQL 主从延迟排查：查看 Seconds_Behind_Master 指标，定位大事务。"
                        + "另需确认网络抖动情况。");

        measure("标点差异",
                "检查 kubectl logs 输出,定位应用异常",
                "检查 kubectl logs 输出，定位应用异常。");

        measure("长文改一词",
                LONG_A,
                LONG_A.replace("立即崩溃", "马上崩溃"));

        System.out.println("\n===== 应判为不同（距离应大）=====");

        measure("同主题不同根因",
                "Pod CrashLoopBackOff 的原因是应用启动时抛出未捕获异常，"
                        + "需检查 kubectl logs 的堆栈输出定位代码缺陷。",
                "Pod CrashLoopBackOff 的原因是内存超限被 OOMKilled，"
                        + "需调大 resources.limits.memory 或优化应用内存占用。");

        measure("不同错误码",
                "Pod 处于 CrashLoopBackOff 状态，容器反复重启",
                "Pod 处于 ImagePullBackOff 状态，镜像拉取失败");

        measure("完全不同主题",
                "Pod CrashLoopBackOff 排查：检查容器日志、启动命令与依赖服务就绪状态，"
                        + "确认 livenessProbe 配置是否过于激进导致容器被反复杀死。",
                "阿里云 SLB 健康检查失败排查：确认后端 ECS 安全组放行探测端口，"
                        + "检查健康检查路径返回 200，核对超时与重试次数配置。");

        measure("短文本不同主题",
                "MySQL 主从延迟",
                "Redis 连接池耗尽");

        System.out.println();
    }

    private static final String LONG_A =
            "## Pod CrashLoopBackOff 问题排查\n\n"
            + "### 问题描述\n"
            + "Pod 反复重启，状态显示为 CrashLoopBackOff，表示容器启动后立即崩溃。\n\n"
            + "### 常见原因\n"
            + "1. 应用程序异常退出：代码 bug、未捕获的异常、配置错误\n"
            + "2. 启动命令错误：ENTRYPOINT 或 CMD 配置错误\n"
            + "3. 依赖服务不可用：数据库、Redis、消息队列等服务未就绪\n"
            + "4. 资源不足：内存超限被 OOMKilled\n"
            + "5. 健康检查过严：livenessProbe 初始延迟过短\n";
}

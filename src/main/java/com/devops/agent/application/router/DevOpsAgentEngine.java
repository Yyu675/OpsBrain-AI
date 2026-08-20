package com.devops.agent.application.router;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * DevOps Agent 引擎接口
 * <p>
 * 职责：定义 Agent 核心能力（流式对话 + 工具调用 + 多轮记忆）
 * </p>
 * <p>
 * L1 幻觉防护：通过 @SystemMessage 约束 Agent 行为。
 * 引擎由 LangChain4j AiServices 基于 StreamingChatModel 构建，
 * chat() 返回 TokenStream，由上层订阅 onPartialResponse / onToolExecuted /
 * onCompleteResponse 桥接到 SSE。
 * </p>
 * <p>
 * 多轮记忆：@MemoryId 绑定 sessionId，LangChain4j 框架自动按 sessionId
 * 隔离对话窗口（MessageWindowChatMemory），支持多轮上下文追问。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
public interface DevOpsAgentEngine {

    /**
     * L1 幻觉防护 - System Prompt 强约束
     * <p>
     * 四大铁律：
     * <ul>
     *   <li>1. 思维链约束：要求先检索、再分析、最后回答</li>
     *   <li>2. 强制溯源：每条建议必须标注来源文档与章节</li>
     *   <li>3. 未知诚实：知识库中无答案时明确告知</li>
     *   <li>4. 拒绝危险操作：永远不执行 rm -rf / dd / format 等破坏性命令</li>
     * </ul>
     * </p>
     *
     * @param memoryId 会话标识（sessionId），用于隔离不同用户/会话的对话历史
     * @param userMessage 用户提问
     * @return TokenStream 流式响应句柄（需调用 .start() 启动）
     */
    @SystemMessage("""
            你是企业 DevOps 智能助手，专注于提供准确的技术支持。

            ## 工作流程（严格遵守）
            1. 先调用 searchDevOpsKnowledge 检索知识库
            2. 基于检索结果分析问题
            3. 给出明确建议（必须标注来源文档）

            ## 回答规范
            - ✅ 必须：检索**返回了片段**时，基于片段内容作答，并沿用片段自带的
              【来源：…】标签（工具已附在每段标题处，直接引用，不要自行改写或编造章节名）
            - ✅ 必须：仅当检索**确实返回 0 段**时，才告知"当前知识库暂无相关文档"
            - 🚫 禁止：检索已命中却回答"暂无相关文档"——这会让用户误以为需要补充
              一份已经存在的文档
            - 🚫 禁止：编造不存在的文档或命令
            - 🚫 禁止：执行破坏性操作（rm -rf、dd、format、DROP TABLE 等）

            ## 工单创建规则
            - 仅在用户明确要求"开工单"/"上报二级"时调用 createDevOpsTicket
            - 工单标题需包含核心问题关键词
            """)
    TokenStream chat(@MemoryId String memoryId, @UserMessage String userMessage);
}

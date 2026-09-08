package com.devops.agent.domain.rag;

import java.util.List;

/**
 * 知识检索统一接口（可插拔向量检索后端，2026-08-27）。
 *
 * <h3>为什么要有这层接口</h3>
 * 此前 {@code DevOpsTools} 直接注入 {@link HybridRetrieverService} 这个
 * <b>与 pgvector 绑死的具体实现</b>（内部是 JdbcTemplate 直查
 * {@code sys_knowledge_chunk} 的 SQL）。后果是：想把向量库换成
 * Milvus / Qdrant / Elasticsearch 时，必须改动 AI 工具层——
 * 而工具层本该只关心「给我几段相关知识」，不关心它存在哪。
 *
 * <p>抽出接口后，换检索后端只需新增一个实现类 + 改一行配置，
 * RAG 上层（工具、评测、健康检查）一行不动。</p>
 *
 * <h3>接口边界是怎么划的（这比接口本身更重要）</h3>
 * 一个抽象只有在<b>它挡住的东西真的换得掉</b>时才有价值。这里刻意做了取舍：
 * <ul>
 *   <li><b>进来的是</b>「查询词 + topK + 可见范围」——三者在任何向量库上
 *       都有对应概念（query / limit / filter），不是 pgvector 专有；</li>
 *   <li><b>出去的是</b> {@link RetrievedChunk}（标题/章节/正文/得分），
 *       同样是各家共通的返回形状；</li>
 *   <li><b>没有</b>暴露 SQL、{@code JdbcTemplate}、{@code Embedding}、
 *       余弦距离算子这类 pgvector 细节——一旦泄漏出来，
 *       换实现时上层照样得改，接口就白抽了；</li>
 *   <li><b>阈值过滤（L4 熔断）留在实现内部</b>，不上提为参数。
 *       因为不同后端的打分口径不同（余弦相似度 / 内积 / BM25 混合分），
 *       让上层传一个 0.73 过去，换后端时这个数字的含义会静默改变——
 *       这类「数值还在、含义变了」的缺陷极难发现。</li>
 * </ul>
 *
 * <h3>迁移到其它项目时怎么用</h3>
 * <ul>
 *   <li><b>技术栈一致（Spring Boot + LangChain4j）</b>：直接复制
 *       {@code Retriever} + {@link RetrievedChunk} + {@link KnowledgeScope}
 *       三个文件，它们不依赖工单/告警等本项目领域概念；</li>
 *   <li><b>技术栈不一致</b>：迁移下面这三条约定即可，用任何语言重写都适用。</li>
 * </ul>
 *
 * <h3>实现约定（三条，都由本项目的真实缺陷反推）</h3>
 * <ol>
 *   <li><b>「无结果」与「服务不可用」必须可区分</b>：
 *       无结果返回空列表，链路故障（向量化失败、后端连不上）返回
 *       {@code null}。历史上二者被混为一谈，用户看到的是
 *       「知识库暂无相关文档」，于是运维去补一份<b>库里本来就有</b>的文档，
 *       而真正的存储层配置错误无人察觉；</li>
 *   <li><b>不得抛异常</b>：检索经 {@code ToolRuntimeManager} 执行，
 *       抛异常会触发重试，而每次重试都是一次付费 embedding 调用；</li>
 *   <li><b>{@link KnowledgeScope} 不可为 null，必须参与过滤</b>：
 *       权限过滤一旦静默失效，越权是无声的——没有任何日志或报错会提示。
 *       实现应当在 scope 为 null 时<b>抛错</b>（这是编码错误，
 *       与上面第 2 条的运行时故障不同），而不是默认放行全部。</li>
 * </ol>
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
public interface Retriever {

    /**
     * 检索后端标识，如 pgvector / milvus / qdrant / elasticsearch。
     *
     * <p>用途：多后端并存时按配置选用；日志与健康检查里标明「查的是哪套存储」。
     * 这一点在灰度迁移期尤其重要——两套库同时在线时，
     * 若日志不写明后端，看到结果差异也无从判断是哪边的问题。</p>
     */
    String backend();

    /**
     * 检索并保留出处。
     *
     * @param query 用户查询，null 或空白返回空列表
     * @param topK  期望返回的片段数上限
     * @param scope 可见范围，<b>不可为 null</b>（系统内部任务请显式传
     *              {@code KnowledgeScopeResolver.systemScope()}）
     * @return 带出处的片段列表；无匹配返回<b>空列表</b>；
     *         检索链路不可用返回 <b>{@code null}</b>（见约定 1）
     * @throws IllegalArgumentException scope 为 null 时抛出（编码错误）
     */
    List<RetrievedChunk> retrieveWithSource(String query, int topK, KnowledgeScope scope);

    /**
     * 只要正文的便捷形式。
     *
     * <p>默认实现基于 {@link #retrieveWithSource}，实现类通常无需覆写。
     * <b>注意它把 null 折叠成了空列表</b>——因为旧契约返回
     * {@code List<String>}，调用方不接受 null。需要区分
     * 「无文档」与「服务不可用」的调用方必须用 {@link #retrieveWithSource}。</p>
     */
    default List<String> retrieve(String query, int topK, KnowledgeScope scope) {
        List<RetrievedChunk> chunks = retrieveWithSource(query, topK, scope);
        if (chunks == null) {
            return List.of();
        }
        return chunks.stream().map(RetrievedChunk::text).toList();
    }

    /**
     * 当前可检索的片段总数。
     *
     * <p>供健康检查与看板使用，用来回答「知识库是空的，还是检索链路坏了」。
     * 统计本身失败时返回 0 而非抛异常——健康检查不该被自己弄崩。</p>
     */
    long countRetrievable();
}

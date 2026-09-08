package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.rag.KnowledgeTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签状态流转的并发写幂等契约。
 *
 * <h3>要防住的缺陷：先查后写窗口 + 丢弃 UPDATE 返回行数</h3>
 * <p>
 * {@code require(id)} 只读取当前状态，它与后续 UPDATE 之间存在窗口。
 * PostgreSQL 默认 READ COMMITTED，并发的 {@code delete}/{@code merge}
 * 可以在这个窗口里改掉标签状态。
 * </p>
 * <p>
 * 单有 {@code AND status = 'ACTIVE'} 守卫不够——<b>还必须检查行数</b>。
 * 此前 {@code rename} 的第一条 UPDATE 有守卫但返回值被丢弃，于是竞态时：
 * 第一条更新 0 行（标签本体没改名，符合预期），
 * 第二条<b>照样执行且它没有任何守卫</b>，把全库文档上的旧标签名改成了新名。
 * </p>
 * <p>
 * <b>用户可见后果</b>：标签管理页里标签还是旧名（或已消失），
 * 而文档上挂的是新名。按标签检索会一条都搜不到——
 * 文档上的 tag 值在标签表里根本不存在。静默数据损坏，无任何报错。
 * </p>
 * <p>
 * 真实 PostgreSQL（pgserver）实测确认：旧行为下第二条 UPDATE 影响 2 行，
 * 产生 2 条指向不存在标签的文档关联。
 * </p>
 *
 * <h3>断言落点</h3>
 * 落在<b>「第二条 UPDATE 有没有被执行」</b>上，而非只验抛了异常——
 * 只验异常的话，「先改完文档再抛错」的实现也会通过，
 * 而那正是要防的数据损坏（事务虽会回滚，但依赖回滚兜底
 * 与从不发生错误写入是两个安全等级，且 merge 里的顺序问题无法靠回滚解决）。
 *
 * @author OpsBrain AI
 * @since 2026-08-28
 */
@DisplayName("标签状态流转并发幂等")
class KnowledgeTagConcurrencyTest {

    /** 标签本体表的 UPDATE 特征片段 */
    private static final String TAG_TABLE_UPDATE = "UPDATE sys_knowledge_tag";
    /** 文档关联表的 UPDATE 特征片段 */
    private static final String DOC_TAG_UPDATE = "UPDATE sys_knowledge_doc_tag";

    private JdbcTemplate jdbc;
    private KnowledgeTagRepository repo;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repo = new KnowledgeTagRepository(jdbc);

        // findById 返回一个 ACTIVE 标签——模拟 require() 那一刻标签还在。
        // 竞态发生在这之后，由各用例通过 UPDATE 的返回行数来表达
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new KnowledgeTag(1L, "K8s", "容器编排", "#409eff", 2L)));
        when(jdbc.query(anyString(), any(RowMapper.class), anyLong()))
                .thenReturn(List.of(new KnowledgeTag(1L, "K8s", "容器编排", "#409eff", 2L)));
    }

    @Nested
    @DisplayName("rename：标签本体未更新时不得改写文档标签")
    class Rename {

        @Test
        @DisplayName("并发致标签本体更新 0 行 → 抛错，且文档标签一行都没动")
        void abortsBeforeTouchingDocTagsWhenTagVanished() {
            // 第一条（标签本体，带 ACTIVE 守卫）返回 0：标签已被并发删除/合并
            when(jdbc.update(contains(TAG_TABLE_UPDATE), any(Object[].class))).thenReturn(0);
            when(jdbc.update(contains(DOC_TAG_UPDATE), any(Object[].class))).thenReturn(2);

            assertThatThrownBy(() -> repo.rename(1L, "Kubernetes", "容器编排", "#409eff"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已被删除");

            // 核心断言：第二条 UPDATE 必须一次都没发生。
            // 只断言"抛了异常"抓不到「先改完文档再抛错」的实现
            verify(jdbc, never()).update(contains(DOC_TAG_UPDATE), any(Object[].class));
        }

        @Test
        @DisplayName("正常路径：标签本体更新 1 行 → 文档标签同步改写")
        void proceedsWhenTagStillActive() {
            when(jdbc.update(contains(TAG_TABLE_UPDATE), any(Object[].class))).thenReturn(1);
            when(jdbc.update(contains(DOC_TAG_UPDATE), any(Object[].class))).thenReturn(2);

            assertThatCode(() -> repo.rename(1L, "Kubernetes", "容器编排", "#409eff"))
                    .doesNotThrowAnyException();

            // 正常路径必须真的同步文档标签——否则「不改写」的实现
            // 会让上一条用例通过，却把功能整个做没了
            verify(jdbc).update(contains(DOC_TAG_UPDATE), any(Object[].class));
        }
    }

    @Nested
    @DisplayName("merge：状态闸门必须在改写文档之前")
    class Merge {

        @Test
        @DisplayName("源标签已非 ACTIVE → 抛错，且文档标签未被改写")
        void abortsBeforeRewritingDocTags() {
            // 源标签置 MERGED 这一步返回 0 行：已被另一次 merge 抢先
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

            assertThatThrownBy(() -> repo.merge(1L, 2L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("合并未执行");

            // 闸门放在最后的实现会先把文档改写一遍才发现冲突 ——
            // 那时数据已经被动过了。这条断言锁死「闸门在前」这个顺序
            verify(jdbc, never()).update(contains(DOC_TAG_UPDATE), any(Object[].class));
        }

        @Test
        @DisplayName("合并自身被直接拒绝，不产生任何写操作")
        void rejectsSelfMerge() {
            assertThatThrownBy(() -> repo.merge(1L, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能合并自身");

            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }
    }

    @Nested
    @DisplayName("delete：不得覆盖 merge 写好的状态")
    class Delete {

        @Test
        @DisplayName("走 merge 分支后不再置 DELETED —— MERGED 语义可追溯，不能被覆盖")
        void doesNotOverwriteMergedStatus() {
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

            repo.delete(1L, 2L);

            // merge 内部会执行一次 sys_knowledge_tag 的 UPDATE（置 MERGED）。
            // 若 delete 之后又无条件置 DELETED，这里就会看到第二次 ——
            // 那会把「合并走了、可追溯到目标标签」抹成「删除了」，审计信息丢失
            verify(jdbc, org.mockito.Mockito.times(1))
                    .update(contains("status = 'MERGED'"), any(Object[].class));
            verify(jdbc, never()).update(contains("status = 'DELETED'"), any(Object[].class));
        }

        @Test
        @DisplayName("纯删除路径带 ACTIVE 守卫，0 行时抛错而非静默成功")
        void failsLoudlyWhenAlreadyGone() {
            // usageCount=0 才能走到删除；上面 setUp 里给的是 2，这里单独覆盖
            when(jdbc.query(anyString(), any(RowMapper.class), anyLong()))
                    .thenReturn(List.of(new KnowledgeTag(1L, "K8s", "容器编排", "#409eff", 0L)));
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

            assertThatThrownBy(() -> repo.delete(1L, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已被删除");
        }
    }
}

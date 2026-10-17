package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.rag.KnowledgeDocVersionRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KnowledgeDocHistoryRepositoryTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listVersionsReturnsTypedRowsAndKeepsNewestFirstQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        KnowledgeDocHistoryRepository repository = new KnowledgeDocHistoryRepository(jdbc);
        KnowledgeDocVersionRow row = new KnowledgeDocVersionRow(
                91L, 7L, 3, "运维手册", "平台", "Alice", "hash-3", 128,
                "u-1", "补充回滚步骤", "UPDATE", null);
        doReturn(List.of(row)).when(jdbc).query(
                anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));

        assertThat(repository.listVersions(7L)).containsExactly(row);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),
                any(org.springframework.jdbc.core.RowMapper.class), aryEq(new Object[]{7L}));
        assertThat(sql.getValue()).contains("WHERE doc_id = ?", "ORDER BY version DESC")
                .contains("AS \"docId\"", "AS \"contentLength\"", "AS \"createTime\"");
    }
}

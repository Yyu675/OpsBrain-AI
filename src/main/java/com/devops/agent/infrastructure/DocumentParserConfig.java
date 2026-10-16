package com.devops.agent.infrastructure;

import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档解析器配置（V2 知识库文件上传）。
 * <p>
 * 选 Apache Tika：格式覆盖最全（PDF/Word/Excel/PPT/TXT/HTML），
 * 且实现 LangChain4j 原生 {@link DocumentParser} 接口，
 * 与现有切片/索引链路无缝衔接。
 * </p>
 * <p>
 * 独立成 Bean 而非在 Service 里 new：单元测试可注入 stub 解析器，
 * 不必为测试拖起真实 Tika 管线。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
@Configuration
public class DocumentParserConfig {

    @Bean
    public DocumentParser documentParser() {
        // 默认构造：AutoDetectParser 按内容嗅探真实格式（不信任扩展名）
        return new ApacheTikaDocumentParser();
    }
}

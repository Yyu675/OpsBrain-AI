package com.devops.agent.infrastructure.storage;

import io.minio.Xml;
import io.minio.errors.XmlParserException;
import io.minio.messages.LocationConstraint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CVE-2025-59952 / GHSA-h7rh-xfpj-hpcm：MinIO XML 不能替换 JVM 属性占位符。
 * 用 SDK 真实 XML 解码器，不 mock、不靠版本字符串断言；探针属性只有固定假值。
 * 升回 8.5.12 时 String/Reader 两条路径必须红，8.6.0+ 应保持字面值。
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MinioXmlSecurityTest {
    private static final String KEY = "opsbrain.minio.xml.security.probe";
    private static final String LITERAL = "${" + KEY + "}";
    private static final String SENTINEL = "opsbrain-fake-value-must-not-be-substituted";

    private static String xml(String value) {
        return "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + value + "</LocationConstraint>";
    }

    @Test
    void stringXmlDoesNotSubstituteSystemProperty() throws Exception {
        String previous = System.setProperty(KEY, SENTINEL);
        try {
            LocationConstraint parsed = Xml.unmarshal(LocationConstraint.class, xml(LITERAL));
            assertThat(parsed.location()).isEqualTo(LITERAL);
            assertThat(Xml.marshal(parsed)).contains(LITERAL).doesNotContain(SENTINEL);
        } finally {
            restore(previous);
        }
    }

    @Test
    void readerXmlDoesNotSubstituteSystemProperty() throws Exception {
        String previous = System.setProperty(KEY, SENTINEL);
        try {
            LocationConstraint parsed = Xml.unmarshal(LocationConstraint.class, new StringReader(xml(LITERAL)));
            assertThat(parsed.location()).isEqualTo(LITERAL);
        } finally {
            restore(previous);
        }
    }

    @Test
    void ordinaryS3RegionStillRoundTrips() throws Exception {
        LocationConstraint parsed = Xml.unmarshal(LocationConstraint.class, xml("us-west-2"));
        assertThat(parsed.location()).isEqualTo("us-west-2");
        assertThat(Xml.unmarshal(LocationConstraint.class, Xml.marshal(parsed)).location()).isEqualTo("us-west-2");
    }

    @Test
    void malformedXmlStillFailsClosed() {
        assertThatThrownBy(() -> Xml.unmarshal(LocationConstraint.class, "<LocationConstraint>unclosed"))
                .isInstanceOf(XmlParserException.class);
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(KEY);
        } else {
            System.setProperty(KEY, previous);
        }
    }
}

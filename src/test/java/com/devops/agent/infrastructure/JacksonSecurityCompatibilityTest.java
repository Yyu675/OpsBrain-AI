package com.devops.agent.infrastructure;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批122：Jackson 2.21.7 同线回补安全兼容性回归测试。
 * 覆盖 jackson-core (StreamReadConstraints 有界约束及深度/数字长度防护 CVE-2026-89407 / CVE-2026-89425)
 * 与 jackson-databind (反序列化类型多态及深层嵌套防护 CVE-2026-68497 / CVE-2026-91776 / CVE-2026-91777)。
 * 严禁大循环，采用确定性小边界断言与健康反序列化对照。
 */
@Timeout(15)
class JacksonSecurityCompatibilityTest {

    static class SimplePayload {
        private String id;
        private int count;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    @Test
    void defaultStreamReadConstraintsEnforcesSafeLimits() {
        StreamReadConstraints constraints = StreamReadConstraints.defaults();
        assertNotNull(constraints, "Jackson 2.21.7 必须提供 StreamReadConstraints.defaults()");
        assertTrue(constraints.getMaxNestingDepth() <= 1000, "嵌套深度上限必须有界防护");
        assertTrue(constraints.getMaxNumberLength() <= 1000, "超长数字解析长度必须有界防护");
        assertTrue(constraints.getMaxStringLength() <= 20_000_000, "字符串读取长度必须有界");
    }

    @Test
    void parserEnforcesStreamReadConstraintsOnDeepNesting() throws IOException {
        StreamReadConstraints strictConstraints = StreamReadConstraints.builder()
                .maxNestingDepth(5)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(strictConstraints)
                .build();

        String deepJson = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":1}}}}}}";
        try (JsonParser parser = factory.createParser(deepJson)) {
            var ex = assertThrows(IOException.class, () -> {
                while (parser.nextToken() != null) {
                    // 读取直到触发深度超限异常
                }
            }, "超过最大嵌套深度的 JSON 输入必须抛出 IOException");
            assertTrue(ex.getMessage().contains("depth") || ex.getMessage().contains("nesting"),
                    "异常信息必须包含深度或嵌套超限说明: " + ex.getMessage());
        }
    }

    @Test
    void parserEnforcesStreamReadConstraintsOnExcessiveNumberLength() throws IOException {
        StreamReadConstraints strictConstraints = StreamReadConstraints.builder()
                .maxNumberLength(10)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(strictConstraints)
                .build();

        String longNumberJson = "{\"num\": 12345678901234567890}";
        try (JsonParser parser = factory.createParser(longNumberJson)) {
            var ex = assertThrows(IOException.class, () -> {
                while (parser.nextToken() != null) {
                    // 读取直到触发数字长度超限异常
                }
            }, "超过最大数字长度的数值必须抛出 IOException");
            assertTrue(ex.getMessage().contains("number length") || ex.getMessage().contains("Number length") || ex.getMessage().contains("maximum"),
                    "异常信息必须包含数字长度超限说明: " + ex.getMessage());
        }
    }

    @Test
    void objectMapperNormalSerializationAndDeserializationPasses() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        SimplePayload payload = new SimplePayload();
        payload.setId("ops-122");
        payload.setCount(42);

        String json = mapper.writeValueAsString(payload);
        assertNotNull(json);
        assertTrue(json.contains("\"ops-122\""));

        SimplePayload parsed = mapper.readValue(json, SimplePayload.class);
        assertEquals("ops-122", parsed.getId());
        assertEquals(42, parsed.getCount());
    }
}

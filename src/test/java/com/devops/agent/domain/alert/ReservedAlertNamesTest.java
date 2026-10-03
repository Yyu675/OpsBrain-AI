package com.devops.agent.domain.alert;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保留告警名集合的钉子测试。
 *
 * <p>{@link ReservedAlertNames#isReserved} 的 javadoc 明写「新增保留名必须同步进
 * 本集合」——2026-10-01 前 LOGS_SILENT 就漏过一次。本测试用反射把这条纪律变成
 * 自动红绿：今后加 String 常量而忘加集合，这里直接红。</p>
 */
class ReservedAlertNamesTest {

    @Test
    void everyReservedNameConstantMustBeInIsReservedSet() throws Exception {
        for (var field : ReservedAlertNames.class.getDeclaredFields()) {
            if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String name = (String) field.get(null);
            assertTrue(ReservedAlertNames.isReserved(name),
                    "常量 " + field.getName() + "=" + name + " 未纳入 isReserved 集合（纪律见类 javadoc）");
        }
    }

    @Test
    void ordinaryAlertNameIsNotReserved() {
        assertFalse(ReservedAlertNames.isReserved("DiskPressure"));
        assertFalse(ReservedAlertNames.isReserved(null));
    }
}

package com.devops.agent.domain.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeScope} 权限判定测试。
 *
 * <p>保护的契约：<b>知识库不得越权可见</b>。修复前 `sys_knowledge_doc`
 * 连可见性字段都没有，任何登录用户都能检索全部内容，还能通过 AI 对话
 * 间接套出受限文档。这类越权是无声的——没有报错、没有日志，
 * 只能靠测试守住。</p>
 */
class KnowledgeScopeTest {

    @Test
    @DisplayName("匿名只能看 PUBLIC")
    void anonymousSeesOnlyPublic() {
        KnowledgeScope s = KnowledgeScope.anonymous();

        assertTrue(s.canSee("PUBLIC", null));
        assertFalse(s.canSee("INTERNAL", null), "未登录不应看到内部文档");
        assertFalse(s.canSee("RESTRICTED", "研发"));
    }

    @Test
    @DisplayName("登录用户可看 PUBLIC 与 INTERNAL")
    void userSeesInternal() {
        KnowledgeScope s = KnowledgeScope.user("1", "研发");

        assertTrue(s.canSee("PUBLIC", null));
        assertTrue(s.canSee("INTERNAL", null));
    }

    @Test
    @DisplayName("RESTRICTED 只对同部门可见——这是本次修复的核心越权场景")
    void restrictedIsDeptScoped() {
        KnowledgeScope rd = KnowledgeScope.user("1", "研发");
        KnowledgeScope fin = KnowledgeScope.user("2", "财务");

        assertTrue(rd.canSee("RESTRICTED", "研发"), "本部门应可见");
        assertFalse(fin.canSee("RESTRICTED", "研发"), "他部门必须不可见");
    }

    @Test
    @DisplayName("登录但无部门的用户看不到任何 RESTRICTED")
    void userWithoutDeptSeesNoRestricted() {
        KnowledgeScope s = KnowledgeScope.user("3", null);

        assertFalse(s.canSee("RESTRICTED", "研发"));
        assertFalse(s.canSee("RESTRICTED", null), "文档无部门也不应对无部门用户开放");
    }

    @Test
    @DisplayName("管理员可见全部")
    void adminSeesAll() {
        KnowledgeScope s = KnowledgeScope.admin("9", null);

        assertTrue(s.canSee("PUBLIC", null));
        assertTrue(s.canSee("INTERNAL", null));
        assertTrue(s.canSee("RESTRICTED", "任意部门"));
    }

    @Test
    @DisplayName("未知可见性档位按最严处理（fail-closed）")
    void unknownVisibilityDenied() {
        KnowledgeScope s = KnowledgeScope.user("1", "研发");

        assertFalse(s.canSee("PRIVATE", "研发"), "拼写错误的档位不得被当作可见");
        assertFalse(s.canSee("", "研发"));
    }

    @Test
    @DisplayName("visibility 为 null 视为 PUBLIC，兼容存量数据")
    void nullVisibilityTreatedAsPublic() {
        assertTrue(KnowledgeScope.user("1", "研发").canSee(null, null));
    }

    @Test
    @DisplayName("SQL 谓词与 canSee 判定一致——两者分叉会造成最难查的越权")
    void sqlPredicateAgreesWithCanSee() {
        record Row(String visibility, String dept) { }
        var rows = new Row[]{
                new Row("PUBLIC", null), new Row("INTERNAL", null),
                new Row("RESTRICTED", "研发"), new Row("RESTRICTED", "财务")
        };
        var scopes = new KnowledgeScope[]{
                KnowledgeScope.anonymous(),
                KnowledgeScope.user("1", null),
                KnowledgeScope.user("2", "研发"),
                KnowledgeScope.user("3", "财务"),
                KnowledgeScope.admin("9", null)
        };

        for (KnowledgeScope s : scopes) {
            for (Row r : rows) {
                boolean bySql = evalPredicate(s, r.visibility(), r.dept());
                assertEquals(s.canSee(r.visibility(), r.dept()), bySql,
                        () -> "判定分叉: scope=" + s.describe() + " row=" + r);
            }
        }
    }

    /** 按 toSqlPredicate 的语义在内存中求值，用于与 canSee 对照 */
    private boolean evalPredicate(KnowledgeScope s, String visibility, String docDept) {
        String p = s.toSqlPredicate();
        if ("TRUE".equals(p)) {
            return true;
        }
        if (p.contains("= 'PUBLIC'")) {
            return "PUBLIC".equals(visibility);
        }
        boolean base = "PUBLIC".equals(visibility) || "INTERNAL".equals(visibility);
        if (!p.contains("RESTRICTED")) {
            return base;
        }
        Object[] params = s.sqlParams();
        return base || ("RESTRICTED".equals(visibility)
                && params.length == 1 && params[0].equals(docDept));
    }

    @Test
    @DisplayName("部门名走绑定参数，不拼进 SQL 文本（注入面）")
    void deptIsBoundNotInlined() {
        KnowledgeScope s = KnowledgeScope.user("1", "研发' OR '1'='1");

        assertTrue(s.toSqlPredicate().contains("?"), "应使用占位符");
        assertFalse(s.toSqlPredicate().contains("1'='1"), "部门名不得出现在 SQL 文本中");
        assertArrayEquals(new Object[]{"研发' OR '1'='1"}, s.sqlParams());
    }

    @Test
    @DisplayName("无需部门参数的场景不返回多余绑定参数")
    void noParamsWhenNotNeeded() {
        assertEquals(0, KnowledgeScope.admin("9", "研发").sqlParams().length);
        assertEquals(0, KnowledgeScope.anonymous().sqlParams().length);
        assertEquals(0, KnowledgeScope.user("1", null).sqlParams().length);
        assertEquals(0, KnowledgeScope.user("1", "  ").sqlParams().length, "空白部门视同无部门");
    }

    @Test
    @DisplayName("缓存域按可见范围分区：不同部门不共享，同部门共享")
    void cacheScopePartitioning() {
        // 不同权限域必须隔离，否则语义缓存会成为越权通道
        assertNotEquals(KnowledgeScope.user("1", "研发").cacheScopeKey(),
                KnowledgeScope.user("2", "财务").cacheScopeKey());
        assertNotEquals(KnowledgeScope.anonymous().cacheScopeKey(),
                KnowledgeScope.user("1", null).cacheScopeKey());
        assertEquals("ADMIN", KnowledgeScope.admin("9", null).cacheScopeKey());

        // 同部门必须共享，否则命中率崩塌，语义缓存失去意义
        assertEquals(KnowledgeScope.user("1", "研发").cacheScopeKey(),
                KnowledgeScope.user("2", "研发").cacheScopeKey());
    }
}

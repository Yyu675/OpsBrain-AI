package com.devops.agent.infrastructure;

import com.sun.net.httpserver.HttpServer;
import opennlp.tools.dictionary.Dictionary;
import opennlp.tools.ml.model.BinaryFileDataReader;
import opennlp.tools.ml.model.GenericModelReader;
import opennlp.tools.tokenize.TokenizerFactory;
import opennlp.tools.util.StringList;
import opennlp.tools.util.ext.ExtensionLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 真SDK、内存字典/模型头与本地假数据HTTP；不加载外部模型、不尝试耗尽JVM。 */
@Timeout(15)
class OpenNlpSecurityCompatibilityTest {
    private static final AtomicInteger INITIALIZATIONS = new AtomicInteger();

    @Test
    void healthyDictionaryAndBuiltInFactoryRemainUsable() throws Exception {
        var dictionary = new Dictionary(stream(dictionary("", "OpsBrain")));
        assertTrue(dictionary.contains(new StringList("OpsBrain")));
        assertNotNull(ExtensionLoader.instantiateExtension(TokenizerFactory.class, TokenizerFactory.class.getName()));
    }

    @Test
    void dictionaryExternalEntityCannotReadHttp() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fake-dictionary-token", exchange -> {
            requests.incrementAndGet();
            byte[] body = "FAKE_DICTIONARY_TOKEN".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/fake-dictionary-token";
            try {
                var dictionary = new Dictionary(stream(dictionary(
                        "<!DOCTYPE dictionary [<!ENTITY probe SYSTEM '" + url + "'>]>", "&probe;")));
                assertFalse(dictionary.contains(new StringList("FAKE_DICTIONARY_TOKEN")));
            } catch (IOException expectedRejection) {
                // 健康对照必须成功；含外部实体字典可拒绝，但不允许任何外联。
            }
            assertEquals(0, requests.get(), "加载字典不得访问外部实体端点");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void untrustedExtensionCannotRunStaticInitializer() {
        assertThrows(RuntimeException.class, () -> ExtensionLoader.instantiateExtension(
                Object.class, UntrustedExtension.class.getName()));
        assertEquals(0, INITIALIZATIONS.get(), "必须在Class.forName初始化之前拒绝非允许包");
    }

    @Test
    void negativeModelCountsFailBeforeArrayAllocation() throws Exception {
        for (int target = 0; target < 3; target++) {
            try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
                out.writeUTF("GIS");
                out.writeInt(1);
                out.writeDouble(0.0);
                // outcomes / outcomePatterns / predicates，前置合法空计数，不申请大数组。
                for (int field = 0; field <= target; field++) {
                    out.writeInt(field == target ? -1 : 0);
                }
                var reader = new GenericModelReader(new BinaryFileDataReader(
                        new ByteArrayInputStream(bytes.toByteArray())));
                assertThrows(IllegalArgumentException.class, reader::getModel,
                        "计数字段" + target + "必须校验拒绝，而不是NegativeArraySizeException或OOM");
            }
        }
    }

    public static class UntrustedExtension {
        static {
            INITIALIZATIONS.incrementAndGet();
        }

        public UntrustedExtension() {
        }
    }

    private static String dictionary(String doctype, String token) {
        return "<?xml version='1.0'?>" + doctype
                + "<dictionary case_sensitive='true'><entry><token>" + token + "</token></entry></dictionary>";
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}

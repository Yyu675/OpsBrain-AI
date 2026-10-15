package com.devops.agent.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.utils.XMLReaderUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.xml.sax.SAXException;

import javax.xml.stream.XMLInputFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 真实解析器/生成的 PDF/本地假数据 HTTP；不读取宿主秘密，不 mock 解析器。 */
@Timeout(30)
class TikaParserSecurityCompatibilityTest {
    private static final String SAFE = "OPS_BRAIN_XFA_SAFE_TEXT";

    @Test
    void configuredLangChainParserReadsTextAndHtml() {
        var parser = new DocumentParserConfig().documentParser();
        assertTrue(parser.parse(stream("OpsBrain incident recovery instructions")).text().contains("recovery"));
        assertTrue(parser.parse(stream("<html><body><p>OpsBrain HTML recovery</p></body></html>"))
                .text().contains("HTML recovery"));
    }

    @Test
    void configuredLangChainParserReadsRealPdf() throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("OpsBrain PDF recovery instructions with sufficient textual content");
                content.endText();
            }
            pdf.save(bytes);
            assertTrue(new DocumentParserConfig().documentParser()
                    .parse(new ByteArrayInputStream(bytes.toByteArray())).text().contains("PDF recovery"));
        }
    }

    @Test
    void externalDtdsAndEntitiesAreDisabled() {
        var factory = XMLReaderUtils.getXMLInputFactory();
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.SUPPORT_DTD));
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES));
    }

    @Test
    void healthyXfaControlReallyExtractsText() throws Exception {
        var handler = new BodyContentHandler(-1);
        parseXfa(xfa("", SAFE), handler);
        assertTrue(handler.toString().contains(SAFE), "必须实际进入 XFA 文本抽取，不能只测试空 PDF");
    }

    @Test
    void xfaExternalEntityCannotReadHttp() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fake-secret", exchange -> {
            requests.incrementAndGet();
            byte[] body = "XXE_PROBE_FAKE_VALUE".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/fake-secret";
            var handler = new BodyContentHandler(-1);
            try {
                parseXfa(xfa("<!DOCTYPE xdp:xdp [<!ENTITY probe SYSTEM '" + url + "'>]>", "&probe;"), handler);
            } catch (TikaException | SAXException expectedRejection) {
                // 拒绝含外部实体的 XFA 也是合法安全结果；健康对照必须能抽取。
            }
            assertEquals(0, requests.get(), "PDF/XFA 解析不得访问外部实体端点");
            assertFalse(handler.toString().contains("XXE_PROBE_FAKE_VALUE"));
        } finally {
            server.stop(0);
        }
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String xfa(String doctype, String text) {
        return "<?xml version='1.0'?>" + doctype
                + "<xdp:xdp xmlns:xdp='http://ns.adobe.com/xdp/'>"
                + "<template xmlns='http://www.xfa.org/schema/xfa-template/3.3/'><subform><draw><value><text>"
                + text + "</text></value></draw></subform></template></xdp:xdp>";
    }

    private static void parseXfa(String xml, BodyContentHandler handler) throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            COSStream xfa = pdf.getDocument().createCOSStream();
            try (var out = xfa.createOutputStream()) {
                out.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            var form = new COSDictionary();
            form.setItem(COSName.FIELDS, new COSArray());
            form.setItem(COSName.XFA, xfa);
            pdf.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, form);
            pdf.save(bytes);
            var config = new PDFParserConfig();
            config.setIfXFAExtractOnlyXFA(true);
            config.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            var context = new ParseContext();
            context.set(PDFParserConfig.class, config);
            new PDFParser().parse(new ByteArrayInputStream(bytes.toByteArray()), handler, new Metadata(), context);
        }
    }
}

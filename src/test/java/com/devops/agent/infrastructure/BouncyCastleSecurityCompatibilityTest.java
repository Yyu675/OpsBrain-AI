package com.devops.agent.infrastructure;

import jakarta.mail.internet.MimeBodyPart;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.PublicKeyProtectionPolicy;
import org.apache.pdfbox.pdmodel.encryption.PublicKeyRecipient;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1ParsingException;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralSubtree;
import org.bouncycastle.asn1.x509.NameConstraintValidatorException;
import org.bouncycastle.asn1.x509.PKIXNameConstraintValidator;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.mail.smime.SMIMESigned;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.bouncycastle.cms.CMSAlgorithm;
import org.bouncycastle.cms.CMSEnvelopedData;
import org.bouncycastle.cms.CMSEnvelopedDataGenerator;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder;
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator;
import org.bouncycastle.crypto.engines.GOST3412_2015Engine;
import org.bouncycastle.crypto.modes.G3413CTRBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 真实BC SDK与PDFBox；仅本地生成的证书、公开测试向量和小DER数据，不联网、不制造OOM/栈溢出。 */
@Timeout(30)
class BouncyCastleSecurityCompatibilityTest {
    private static final BouncyCastleProvider BC = new BouncyCastleProvider();
    private static final char[] PASSWORD = "public-fixture-password".toCharArray();

    @Test
    void gostCounterDoesNotRepeatAfterByteWrap() {
        byte[] plain = new byte[257 * 16];
        byte[] encrypted = new byte[plain.length];
        cipher(true).processBytes(plain, 0, plain.length, encrypted, 0);
        assertFalse(Arrays.equals(Arrays.copyOfRange(encrypted, 0, 16),
                Arrays.copyOfRange(encrypted, 256 * 16, 257 * 16)), "第256块回绕不得复用首块密钥流");
        byte[] recovered = new byte[plain.length];
        cipher(false).processBytes(encrypted, 0, encrypted.length, recovered, 0);
        assertArrayEquals(plain, recovered);
    }

    @Test
    void excludedEmailCannotEscapeWithTrailingDot() throws Exception {
        var validator = excluded(GeneralName.rfc822Name, "blocked.example");
        assertDoesNotThrow(() -> validator.checkExcluded(new GeneralName(GeneralName.rfc822Name, "user@allowed.example")));
        assertThrows(NameConstraintValidatorException.class,
                () -> validator.checkExcluded(new GeneralName(GeneralName.rfc822Name, "user@blocked.example")));
        assertThrows(NameConstraintValidatorException.class,
                () -> validator.checkExcluded(new GeneralName(GeneralName.rfc822Name, "user@blocked.example.")));
    }

    @Test
    void excludedUriCannotEscapeWithTrailingDot() throws Exception {
        var validator = excluded(GeneralName.uniformResourceIdentifier, "blocked.example");
        assertDoesNotThrow(() -> validator.checkExcluded(new GeneralName(GeneralName.uniformResourceIdentifier, "https://allowed.example/path")));
        assertThrows(NameConstraintValidatorException.class,
                () -> validator.checkExcluded(new GeneralName(GeneralName.uniformResourceIdentifier, "https://blocked.example/")));
        assertThrows(NameConstraintValidatorException.class,
                () -> validator.checkExcluded(new GeneralName(GeneralName.uniformResourceIdentifier, "https://blocked.example./")));
    }

    @Test
    void directoryConstraintMustMatchInitialPrefix() throws Exception {
        var validator = new PKIXNameConstraintValidator();
        validator.intersectPermittedSubtree(new GeneralSubtree(new GeneralName(new X500Name("C=US,O=OpsBrain"))));
        assertDoesNotThrow(() -> validator.checkPermitted(new GeneralName(new X500Name("C=US,O=OpsBrain,CN=Healthy"))));
        assertThrows(NameConstraintValidatorException.class, () -> validator.checkPermitted(
                new GeneralName(new X500Name("C=FR,O=Other,C=US,O=OpsBrain,CN=Injected"))));
    }

    @Test
    void lazyAsn1RetainsDepthBudget() throws Exception {
        try (var healthy = new ASN1InputStream(nested(8), true)) {
            assertDoesNotThrow(() -> healthy.readObject().hashCode());
        }
        // 256层是上游回归所用安全小输入；旧版可完整遍历，不依赖OOM/StackOverflow判红。
        try (var input = new ASN1InputStream(nested(256), true)) {
            var parsed = input.readObject();
            var rejected = assertThrows(ASN1ParsingException.class, parsed::hashCode);
            assertNotNull(rejected.getCause());
            assertEquals("maximum nested construction level reached", rejected.getCause().getMessage());
        }
    }

    @Test
    void cmsAndPkcs12RemainInteroperable() throws Exception {
        var pair = keyPair();
        var cert = certificate(pair);
        cert.verify(pair.getPublic(), BC);
        byte[] payload = "OpsBrain CMS recovery fixture".getBytes(StandardCharsets.UTF_8);
        var generator = new CMSEnvelopedDataGenerator();
        generator.addRecipientInfoGenerator(new JceKeyTransRecipientInfoGenerator(cert).setProvider(BC));
        var envelope = generator.generate(new CMSProcessableByteArray(payload),
                new JceCMSContentEncryptorBuilder(CMSAlgorithm.AES128_CBC).setProvider(BC).build());
        var parsed = new CMSEnvelopedData(envelope.getEncoded());
        var recipient = parsed.getRecipientInfos().getRecipients().iterator().next();
        assertArrayEquals(payload, recipient.getContent(new JceKeyTransEnvelopedRecipient(pair.getPrivate()).setProvider(BC)));
        var jdkStore = KeyStore.getInstance("PKCS12");
        jdkStore.load(new ByteArrayInputStream(keyStore(pair, cert)), PASSWORD);
        assertArrayEquals(pair.getPrivate().getEncoded(), jdkStore.getKey("fixture", PASSWORD).getEncoded());
        assertArrayEquals(cert.getEncoded(), jdkStore.getCertificate("fixture").getEncoded());
    }

    @Test
    void signedMimeMessageRoundTrips() throws Exception {
        var pair = keyPair();
        var cert = certificate(pair);
        var body = new MimeBodyPart();
        body.setText("OpsBrain signed MIME recovery", StandardCharsets.UTF_8.name());
        var generator = new SMIMESignedGenerator();
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(BC)
                .build("SHA256withRSA", pair.getPrivate(), cert));
        generator.addCertificates(new JcaCertStore(List.of(cert)));
        var signed = new SMIMESigned(generator.generate(body));
        assertTrue(signed.getContent().getContent().toString().contains("signed MIME recovery"));
        var signer = signed.getSignerInfos().getSigners().iterator().next();
        assertTrue(signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider(BC).build(cert)));
    }

    @Test
    void pdfBoxPublicKeyEncryptionRoundTrips() throws Exception {
        var pair = keyPair();
        var cert = certificate(pair);
        var recipient = new PublicKeyRecipient();
        recipient.setX509(cert);
        recipient.setPermission(new AccessPermission());
        var policy = new PublicKeyProtectionPolicy();
        policy.setEncryptionKeyLength(128);
        policy.addRecipient(recipient);
        byte[] encrypted;
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("OpsBrain encrypted PDF recovery");
                content.endText();
            }
            pdf.protect(policy);
            pdf.save(bytes);
            encrypted = bytes.toByteArray();
        }
        try (var store = new ByteArrayInputStream(keyStore(pair, cert));
             var opened = Loader.loadPDF(encrypted, new String(PASSWORD), store, "fixture")) {
            assertTrue(opened.isEncrypted());
            assertTrue(new PDFTextStripper().getText(opened).contains("encrypted PDF recovery"));
        }
    }

    private static G3413CTRBlockCipher cipher(boolean encrypt) {
        var cipher = new G3413CTRBlockCipher(new GOST3412_2015Engine());
        cipher.init(encrypt, new ParametersWithIV(new KeyParameter(Hex.decode(
                "8899aabbccddeeff0011223344556677fedcba98765432100123456789abcdef")), Hex.decode("0001020304050607")));
        return cipher;
    }

    private static PKIXNameConstraintValidator excluded(int tag, String host) {
        var validator = new PKIXNameConstraintValidator();
        validator.addExcludedSubtree(new GeneralSubtree(new GeneralName(tag, host)));
        return validator;
    }

    private static byte[] nested(int depth) throws Exception {
        ASN1Encodable value = new DERSequence();
        for (int i = 0; i < depth; i++) value = new DERSequence(value);
        return value.toASN1Primitive().getEncoded("DER");
    }

    private static KeyPair keyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA", BC);
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(KeyPair pair) throws Exception {
        var name = new X500Name("CN=OpsBrain local fixture");
        var now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(3600)), name, pair.getPublic());
        return new JcaX509CertificateConverter().setProvider(BC).getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(pair.getPrivate())));
    }

    private static byte[] keyStore(KeyPair pair, X509Certificate cert) throws Exception {
        var store = KeyStore.getInstance("PKCS12", BC);
        store.load(null, PASSWORD);
        store.setKeyEntry("fixture", pair.getPrivate(), PASSWORD, new Certificate[]{cert});
        var out = new ByteArrayOutputStream();
        store.store(out, PASSWORD);
        return out.toByteArray();
    }
}

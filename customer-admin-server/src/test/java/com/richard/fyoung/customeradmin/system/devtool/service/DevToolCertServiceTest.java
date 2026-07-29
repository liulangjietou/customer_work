package com.richard.fyoung.customeradmin.system.devtool.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertInfo;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertMatchResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertParseResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolKeystoreParseResponse;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DevToolCertService} 单测（全离线）：证书/证书链/CSR 解析、SAN 提取、
 * 私钥匹配（RSA/EC 正反例）、PKCS12 密钥库枚举、非法输入 fast fail。
 * 测试材料用 BouncyCastle 现场生成，不依赖外部固定文件。
 * @author owlzhangfq@gmail.com
 */
class DevToolCertServiceTest {

    private static final BouncyCastleProvider BC = new BouncyCastleProvider();

    /** openssl 现场生成的自签 EC 证书（prime256v1，仅测试用，不含任何真实业务身份）。 */
    private static final String OPENSSL_EC_CERT_PEM = """
        -----BEGIN CERTIFICATE-----
        MIIBejCCAR+gAwIBAgIUFcpIIxy3qTLcJS6U/aAD+TestxUwCgYIKoZIzj0EAwIw
        EjEQMA4GA1UEAwwHZWMudGVzdDAeFw0yNjA3MjkwODQyMDZaFw0yNjA4MjgwODQy
        MDZaMBIxEDAOBgNVBAMMB2VjLnRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNC
        AAQ2G7NAgGxzsqB8yJKX71LZw65WTiNqUqtqQvtUMCLHdQPKv8r93X9Q0u+mAfS4
        6DaElMfGKNC+HACAz8C7JaMRo1MwUTAdBgNVHQ4EFgQUotIrb6QtJz8N30RDQNpw
        fk/QIZUwHwYDVR0jBBgwFoAUotIrb6QtJz8N30RDQNpwfk/QIZUwDwYDVR0TAQH/
        BAUwAwEB/zAKBggqhkjOPQQDAgNJADBGAiEAiXk3m+WC/z8jOoYcYvkTmVv0i0D9
        DbfD8PaiSskPitUCIQDznPdksj09BkliLIjma0Rpp+GPF50VcWniVLMUQ+L+nA==
        -----END CERTIFICATE-----
        """;

    /** 与上面证书配对的 openssl SEC1 私钥（仅测试用）。 */
    private static final String OPENSSL_EC_SEC1_KEY_PEM = """
        -----BEGIN EC PRIVATE KEY-----
        MHcCAQEEILmEvcRueU5ts448aMjU+9+A/UGiC4n7IU4y6u/DDndmoAoGCCqGSM49
        AwEHoUQDQgAENhuzQIBsc7KgfMiSl+9S2cOuVk4jalKrakL7VDAix3UDyr/K/d1/
        UNLvpgH0uOg2hJTHxijQvhwAgM/AuyWjEQ==
        -----END EC PRIVATE KEY-----
        """;

    private static KeyPair rsaKeyPair;
    private static KeyPair rsaKeyPair2;
    private static KeyPair ecKeyPair;
    private static X509Certificate rsaCert;
    private static X509Certificate ecCert;

    private final DevToolCertService service = new DevToolCertService();

    @BeforeAll
    static void generateMaterials() throws Exception {
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        rsaKeyPair = rsaGen.generateKeyPair();
        rsaKeyPair2 = rsaGen.generateKeyPair();

        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(256);
        ecKeyPair = ecGen.generateKeyPair();

        rsaCert = selfSign(rsaKeyPair, "CN=unit-test-rsa.example.com", "SHA256withRSA", true);
        ecCert = selfSign(ecKeyPair, "CN=unit-test-ec.example.com", "SHA256withECDSA", false);
    }

    /** 自签证书：SAN 带 DNS + IP，RSA 版附带 CA BasicConstraints。 */
    private static X509Certificate selfSign(KeyPair keyPair, String dn, String sigAlg, boolean ca) throws Exception {
        X500Name subject = new X500Name(dn);
        long now = System.currentTimeMillis();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now), new Date(now - 3600_000L),
            new Date(now + 365L * 24 * 3600_000L), subject, keyPair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
            new GeneralName(GeneralName.dNSName, "san.example.com"),
            new GeneralName(GeneralName.iPAddress, "127.0.0.1"),
        }));
        if (ca) {
            builder.addExtension(Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(true));
        }
        ContentSigner signer = new JcaContentSignerBuilder(sigAlg).setProvider(BC).build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().setProvider(BC).getCertificate(builder.build(signer));
    }

    /**
     * PKCS#8（-----BEGIN PRIVATE KEY-----）格式私钥 PEM。
     *
     * <p>手工拼装而不用 {@code JcaPEMWriter}：后者对 EC 私钥会经 MiscPEMGenerator 改写成
     * <b>缺算法参数</b>的 SEC1 块，那是现实中不存在的残缺形态，会把测试引到假问题上。</p>
     */
    private static String pkcs8Pem(KeyPair keyPair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    private static String toPem(Object object) throws Exception {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(object);
        }
        return writer.toString();
    }

    // ---------- 证书解析 ----------

    @Test
    void parse_shouldDescribeSingleCertificate_withSanAndFingerprints() throws Exception {
        DevToolCertParseResponse response = service.parse(toPem(rsaCert));

        assertEquals(1, response.getCertificates().size());
        DevToolCertInfo info = response.getCertificates().get(0);
        assertTrue(info.getSubject().contains("unit-test-rsa.example.com"));
        assertEquals("RSA", info.getPublicKeyAlgorithm());
        assertEquals(2048, info.getPublicKeyBits());
        assertFalse(info.isExpired());
        assertTrue(info.isCa(), "RSA 测试证书带 CA BasicConstraints");
        assertTrue(info.getSubjectAlternativeNames().stream().anyMatch(s -> s.equals("DNS:san.example.com")));
        assertTrue(info.getSubjectAlternativeNames().stream().anyMatch(s -> s.startsWith("IP:")));
        assertEquals(32, info.getSha256Fingerprint().split(":").length, "SHA-256 指纹应为 32 组冒号分隔字节");
        assertEquals(20, info.getSha1Fingerprint().split(":").length, "SHA-1 指纹应为 20 组冒号分隔字节");
        assertTrue(info.getDaysRemaining() > 300);
    }

    @Test
    void parse_shouldDescribeCertChain_inInputOrder() throws Exception {
        DevToolCertParseResponse response = service.parse(toPem(rsaCert) + "\n" + toPem(ecCert));

        assertEquals(2, response.getCertificates().size());
        assertTrue(response.getCertificates().get(0).getSubject().contains("rsa"));
        assertEquals("EC", response.getCertificates().get(1).getPublicKeyAlgorithm());
        assertEquals(256, response.getCertificates().get(1).getPublicKeyBits());
        assertFalse(response.getCertificates().get(1).isCa());
    }

    @Test
    void parse_shouldDescribeCsr_withSubjectAndKey() throws Exception {
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(
            new X500Name("CN=csr.example.com,O=UnitTest"), rsaKeyPair.getPublic())
            .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(rsaKeyPair.getPrivate()));

        DevToolCertParseResponse response = service.parse(toPem(csr));

        assertTrue(response.getCertificates().isEmpty());
        assertEquals(1, response.getCsrs().size());
        assertTrue(response.getCsrs().get(0).getSubject().contains("csr.example.com"));
        assertEquals("RSA", response.getCsrs().get(0).getPublicKeyAlgorithm());
        assertEquals(2048, response.getCsrs().get(0).getPublicKeyBits());
    }

    @Test
    void parse_shouldFastFail_whenNoRecognizableBlock() {
        BizException e = assertThrows(BizException.class, () -> service.parse("hello world"));
        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }

    // ---------- 私钥匹配 ----------

    @Test
    void match_shouldPass_forPairedRsaKey() throws Exception {
        DevToolCertMatchResponse response = service.match(toPem(rsaCert), toPem(rsaKeyPair.getPrivate()));
        assertTrue(response.isMatched());
        assertEquals("RSA", response.getPublicKeyAlgorithm());
    }

    @Test
    void match_shouldFail_forWrongRsaKey() throws Exception {
        DevToolCertMatchResponse response = service.match(toPem(rsaCert), toPem(rsaKeyPair2.getPrivate()));
        assertFalse(response.isMatched());
    }

    @Test
    void match_shouldPass_forPairedEcKey() throws Exception {
        DevToolCertMatchResponse response = service.match(toPem(ecCert), pkcs8Pem(ecKeyPair));
        assertTrue(response.isMatched());
    }

    /** openssl 导出的 SEC1（-----BEGIN EC PRIVATE KEY-----）格式也要能解，这是最常见的 EC 私钥形态。 */
    @Test
    void match_shouldPass_forOpensslSec1EcKey() {
        DevToolCertMatchResponse response = service.match(OPENSSL_EC_CERT_PEM, OPENSSL_EC_SEC1_KEY_PEM);
        assertTrue(response.isMatched(), "openssl SEC1 EC 私钥应能与其自签证书配对");
    }

    @Test
    void match_shouldReportAlgorithmMismatch_withoutProbe() throws Exception {
        DevToolCertMatchResponse response = service.match(toPem(rsaCert), pkcs8Pem(ecKeyPair));
        assertFalse(response.isMatched());
        assertTrue(response.getReason().contains("算法不一致"));
    }

    @Test
    void match_shouldFastFail_whenPrivateKeyPemInvalid() throws Exception {
        String certPem = toPem(rsaCert);
        BizException e = assertThrows(BizException.class, () -> service.match(certPem, "not a key"));
        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }

    // ---------- 密钥库 ----------

    @Test
    void parseKeystore_shouldListPkcs12Entries_withChain() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("unit-test-key", rsaKeyPair.getPrivate(), "changeit".toCharArray(),
            new X509Certificate[] {rsaCert});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        keyStore.store(out, "changeit".toCharArray());

        DevToolKeystoreParseResponse response = service.parseKeystore(out.toByteArray(), "changeit");

        assertEquals("PKCS12", response.getKeystoreType());
        assertEquals(1, response.getEntries().size());
        DevToolKeystoreParseResponse.Entry entry = response.getEntries().get(0);
        assertEquals("unit-test-key", entry.getAlias());
        assertEquals("PRIVATE_KEY", entry.getEntryType());
        assertEquals(1, entry.getChain().size());
        assertTrue(entry.getChain().get(0).getSubject().contains("unit-test-rsa.example.com"));
    }

    @Test
    void parseKeystore_shouldFastFail_onWrongPassword() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("k", rsaKeyPair.getPrivate(), "changeit".toCharArray(),
            new X509Certificate[] {rsaCert});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        keyStore.store(out, "changeit".toCharArray());
        byte[] data = out.toByteArray();

        BizException e = assertThrows(BizException.class, () -> service.parseKeystore(data, "wrong"));
        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }
}

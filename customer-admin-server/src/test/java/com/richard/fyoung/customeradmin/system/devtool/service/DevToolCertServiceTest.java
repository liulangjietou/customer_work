package com.richard.fyoung.customeradmin.system.devtool.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertInfo;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertMatchResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertParseResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolKeystoreParseResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolPrivateKeyExportResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DevToolCertService} 单测。
 *
 * <p>本类只负责 <b>VO 转换</b>与<b>异常转换</b>——解析逻辑本身已下沉到 starter 的
 * {@code CertDevToolOps}，其正确性由 {@code CertDevToolOpsTest} 覆盖（RSA/EC/CSR/SEC1 私钥/密钥库
 * 共 14 例），这里不重复验证，只确认字段没漏映射、{@link IllegalArgumentException} 被转成
 * {@link ResultCode#PARAM_INVALID} 而非裸穿到 Controller。</p>
 * @author owlzhangfq@gmail.com
 */
class DevToolCertServiceTest {

    /** openssl 自签 EC 证书（仅测试用，不含任何真实业务身份）。 */
    private static final String CERT_PEM = """
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

    /** 与上面证书配对的 SEC1 私钥（仅测试用）。 */
    private static final String KEY_PEM = """
        -----BEGIN EC PRIVATE KEY-----
        MHcCAQEEILmEvcRueU5ts448aMjU+9+A/UGiC4n7IU4y6u/DDndmoAoGCCqGSM49
        AwEHoUQDQgAENhuzQIBsc7KgfMiSl+9S2cOuVk4jalKrakL7VDAix3UDyr/K/d1/
        UNLvpgH0uOg2hJTHxijQvhwAgM/AuyWjEQ==
        -----END EC PRIVATE KEY-----
        """;

    private final DevToolCertService service = new DevToolCertService();

    @Test
    void parse_shouldMapAllCertificateFields_toVO() {
        DevToolCertParseResponse response = service.parse(CERT_PEM);

        assertEquals(1, response.getCertificates().size());
        DevToolCertInfo vo = response.getCertificates().get(0);
        assertTrue(vo.getSubject().contains("ec.test"), "subject 应映射");
        assertTrue(vo.getIssuer().contains("ec.test"), "issuer 应映射");
        assertEquals("EC", vo.getPublicKeyAlgorithm());
        assertEquals(256, vo.getPublicKeyBits());
        assertTrue(vo.isCa(), "该测试证书带 CA BasicConstraints");
        assertEquals(3, vo.getVersion());
        assertTrue(vo.getNotAfterMs() > vo.getNotBeforeMs());
        assertFalse(vo.getSerialNumberHex().isBlank());
        assertEquals(20, vo.getSha1Fingerprint().split(":").length);
        assertEquals(32, vo.getSha256Fingerprint().split(":").length);
        assertTrue(response.getCsrs().isEmpty());
    }

    @Test
    void match_shouldMapResult_toVO() {
        DevToolCertMatchResponse response = service.match(CERT_PEM, KEY_PEM);

        assertTrue(response.isMatched());
        assertEquals("EC", response.getPublicKeyAlgorithm());
        assertFalse(response.getReason().isBlank(), "结论说明应透传");
    }

    @Test
    void parse_shouldConvertIllegalArgument_toParamInvalidBizException() {
        BizException e = assertThrows(BizException.class, () -> service.parse("not a pem"));
        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }

    @Test
    void match_shouldConvertIllegalArgument_toParamInvalidBizException() {
        BizException e = assertThrows(BizException.class, () -> service.match(CERT_PEM, "garbage"));
        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }

    @Test
    void parseKeystore_shouldMapEntries_toVO() throws Exception {
        DevToolKeystoreParseResponse response = service.parseKeystore(pkcs12Bytes(), "changeit");

        assertEquals("PKCS12", response.getKeystoreType());
        assertEquals(1, response.getEntries().size());
        DevToolKeystoreParseResponse.Entry entry = response.getEntries().get(0);
        assertEquals("k", entry.getAlias());
        assertEquals("PRIVATE_KEY", entry.getEntryType());
        assertEquals(1, entry.getChain().size());
        assertTrue(entry.getChain().get(0).getSubject().contains("ec.test"));
    }

    @Test
    void parseKeystore_shouldConvertIllegalArgument_toParamInvalidBizException() throws Exception {
        byte[] data = pkcs12Bytes();
        BizException e = assertThrows(BizException.class, () -> service.parseKeystore(data, "wrong"));
        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }

    /** 用上面那张 EC 证书 + 现场生成的 RSA 私钥凑一个 PKCS12（条目结构够测 VO 映射即可）。 */
    private byte[] pkcs12Bytes() throws Exception {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(derOf(CERT_PEM)));
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("k", keyPair.getPrivate(), "changeit".toCharArray(), new X509Certificate[] {cert});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        keyStore.store(out, "changeit".toCharArray());
        return out.toByteArray();
    }

    private byte[] derOf(String pem) {
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    @Test
    void parse_shouldIncludePem_forPageSide() {
        // 页面侧固定回显 PEM（智能体侧才省略），拆证书链依赖这个字段
        String pem = service.parse(CERT_PEM).getCertificates().get(0).getPem();
        assertNotNull(pem);
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"));
    }

    @Test
    void parseKeystore_shouldIncludeChainPem() throws Exception {
        DevToolKeystoreParseResponse response = service.parseKeystore(pkcs12Bytes(), "changeit");
        assertNotNull(response.getEntries().get(0).getChain().get(0).getPem());
    }

    @Test
    void exportPrivateKey_shouldMapAliasAlgorithmAndPem() throws Exception {
        DevToolPrivateKeyExportResponse response =
            service.exportPrivateKey(pkcs12Bytes(), "changeit", "k", "");

        assertEquals("k", response.getAlias());
        assertEquals("RSA", response.getAlgorithm());
        assertTrue(response.getPrivateKeyPem().startsWith("-----BEGIN PRIVATE KEY-----"));
    }

    @Test
    void exportPrivateKey_shouldConvertIllegalArgument_toParamInvalidBizException() throws Exception {
        byte[] data = pkcs12Bytes();
        BizException e = assertThrows(BizException.class,
            () -> service.exportPrivateKey(data, "changeit", "missing-alias", ""));
        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }
}

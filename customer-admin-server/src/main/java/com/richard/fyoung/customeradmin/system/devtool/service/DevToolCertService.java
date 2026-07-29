package com.richard.fyoung.customeradmin.system.devtool.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertInfo;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertMatchResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertParseResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCsrInfo;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolKeystoreParseResponse;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开发者工具箱 · 证书解析：X.509 证书/证书链、PKCS#10 CSR、私钥-证书匹配校验、PFX/JKS 密钥库。
 *
 * <p><b>隐私边界</b>：所有输入只在本次请求的内存中解析，不落库、不写日志（error 日志只记错误码，
 * 不回显证书/私钥内容）。私钥匹配用"签名-验签探测"而非公钥参数比对——算法无关（RSA/EC/EdDSA 通吃），
 * 也天然规避了各算法公钥表示的差异。</p>
 *
 * <p>JDK 原生只认 X.509 证书与未加密 PKCS#8 私钥，CSR 与 PKCS#1/SEC1 私钥解析靠 BouncyCastle
 * （PEMParser / PKCS10CertificationRequest）；BC Provider 以实例方式传入，不注册进全局 Security。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class DevToolCertService {

    private static final Logger log = LoggerFactory.getLogger(DevToolCertService.class);

    /** PEM 块通用匹配：BEGIN/END 标签 + Base64 正文。 */
    private static final Pattern PEM_BLOCK = Pattern.compile(
        "-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]+?)-----END \\1-----");

    private static final String BLOCK_CERT = "CERTIFICATE";
    private static final String BLOCK_CSR = "CERTIFICATE REQUEST";
    private static final String BLOCK_NEW_CSR = "NEW CERTIFICATE REQUEST";

    /** 签名-验签探测的固定负载（内容无意义，只求两侧一致）。 */
    private static final byte[] PROBE_PAYLOAD = "customer-work-cert-match-probe".getBytes(StandardCharsets.UTF_8);

    private final BouncyCastleProvider bcProvider = new BouncyCastleProvider();

    // ---------- 证书 / CSR 解析 ----------

    /** 解析 PEM 文本中的全部证书与 CSR 块；一个可识别块都没有则报参数错误。 */
    public DevToolCertParseResponse parse(String pemContent) {
        List<DevToolCertInfo> certs = new ArrayList<>();
        List<DevToolCsrInfo> csrs = new ArrayList<>();
        Matcher matcher = PEM_BLOCK.matcher(pemContent);
        while (matcher.find()) {
            String label = matcher.group(1);
            byte[] der = decodeBase64Body(matcher.group(2));
            if (BLOCK_CERT.equals(label)) {
                certs.add(describeCertificate(parseX509(der)));
            } else if (BLOCK_CSR.equals(label) || BLOCK_NEW_CSR.equals(label)) {
                csrs.add(describeCsr(der));
            }
            // 其余块类型（私钥等）在解析接口里刻意忽略：私钥只该出现在匹配校验接口
        }
        if (certs.isEmpty() && csrs.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "未识别到 CERTIFICATE / CERTIFICATE REQUEST PEM 块，请粘贴 -----BEGIN CERTIFICATE----- 格式内容");
        }
        DevToolCertParseResponse response = new DevToolCertParseResponse();
        response.setCertificates(certs);
        response.setCsrs(csrs);
        return response;
    }

    // ---------- 私钥-证书匹配 ----------

    /** 私钥与证书匹配校验：私钥对固定负载签名，证书公钥验签，验签通过即配对。 */
    public DevToolCertMatchResponse match(String certPem, String privateKeyPem) {
        X509Certificate cert = firstCertificate(certPem);
        PrivateKey privateKey = parsePrivateKey(privateKeyPem);
        PublicKey publicKey = cert.getPublicKey();

        if (!privateKey.getAlgorithm().equals(publicKey.getAlgorithm())
            && !isEcAlgorithmFamily(privateKey.getAlgorithm(), publicKey.getAlgorithm())) {
            return new DevToolCertMatchResponse(false, publicKey.getAlgorithm(),
                "算法不一致：私钥为 " + privateKey.getAlgorithm() + "，证书公钥为 " + publicKey.getAlgorithm());
        }
        try {
            String sigAlg = probeSignatureAlgorithm(publicKey.getAlgorithm());
            Signature signer = Signature.getInstance(sigAlg, bcProvider);
            signer.initSign(privateKey);
            signer.update(PROBE_PAYLOAD);
            byte[] signature = signer.sign();

            Signature verifier = Signature.getInstance(sigAlg, bcProvider);
            verifier.initVerify(publicKey);
            verifier.update(PROBE_PAYLOAD);
            boolean matched = verifier.verify(signature);
            return new DevToolCertMatchResponse(matched, publicKey.getAlgorithm(),
                matched ? "私钥签名可被证书公钥验签，二者配对" : "私钥签名无法被证书公钥验签，二者不配对");
        } catch (Exception e) {
            log.error("cert match probe failed, code={}", "DEVTOOL-CERT-MATCH-FAIL", e);
            throw new BizException(ResultCode.PARAM_INVALID, "匹配探测失败：" + e.getMessage());
        }
    }

    // ---------- 密钥库 ----------

    /** 解析 PFX/JKS 密钥库：按 PKCS12 → JKS 顺序尝试，列出全部条目及其证书链。 */
    public DevToolKeystoreParseResponse parseKeystore(byte[] data, String password) {
        char[] pwd = password == null ? new char[0] : password.toCharArray();
        KeyStore keyStore = null;
        String loadedType = null;
        Exception lastError = null;
        for (String type : new String[] {"PKCS12", "JKS"}) {
            try {
                KeyStore candidate = KeyStore.getInstance(type);
                candidate.load(new ByteArrayInputStream(data), pwd);
                keyStore = candidate;
                loadedType = type;
                break;
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (keyStore == null) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "密钥库无法打开（已尝试 PKCS12/JKS）：密码错误或文件损坏"
                    + (lastError == null ? "" : "（" + lastError.getMessage() + "）"));
        }
        try {
            List<DevToolKeystoreParseResponse.Entry> entries = new ArrayList<>();
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                DevToolKeystoreParseResponse.Entry entry = new DevToolKeystoreParseResponse.Entry();
                entry.setAlias(alias);
                List<DevToolCertInfo> chain = new ArrayList<>();
                if (keyStore.isKeyEntry(alias)) {
                    entry.setEntryType("PRIVATE_KEY");
                    Certificate[] certs = keyStore.getCertificateChain(alias);
                    if (certs != null) {
                        for (Certificate cert : certs) {
                            if (cert instanceof X509Certificate x509) {
                                chain.add(describeCertificate(x509));
                            }
                        }
                    }
                } else {
                    entry.setEntryType("TRUSTED_CERT");
                    Certificate cert = keyStore.getCertificate(alias);
                    if (cert instanceof X509Certificate x509) {
                        chain.add(describeCertificate(x509));
                    }
                }
                entry.setChain(chain);
                entries.add(entry);
            }
            DevToolKeystoreParseResponse response = new DevToolKeystoreParseResponse();
            response.setKeystoreType(loadedType);
            response.setEntries(entries);
            return response;
        } catch (Exception e) {
            log.error("keystore enumerate failed, code={}", "DEVTOOL-CERT-KEYSTORE-FAIL", e);
            throw new BizException(ResultCode.PARAM_INVALID, "密钥库条目读取失败：" + e.getMessage());
        }
    }

    // ---------- 私有辅助 ----------

    private byte[] decodeBase64Body(String body) {
        try {
            return Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "PEM 正文不是合法 Base64");
        }
    }

    private X509Certificate parseX509(byte[] der) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "证书解析失败：" + e.getMessage());
        }
    }

    /** 取 PEM 文本中的第一段 CERTIFICATE 块。 */
    private X509Certificate firstCertificate(String certPem) {
        Matcher matcher = PEM_BLOCK.matcher(certPem);
        while (matcher.find()) {
            if (BLOCK_CERT.equals(matcher.group(1))) {
                return parseX509(decodeBase64Body(matcher.group(2)));
            }
        }
        throw new BizException(ResultCode.PARAM_INVALID, "未识别到 CERTIFICATE PEM 块");
    }

    /** 解析私钥 PEM：PEMParser 兼容 PKCS#8 / PKCS#1 RSA / SEC1 EC；加密私钥明确报错。 */
    private PrivateKey parsePrivateKey(String privateKeyPem) {
        try (PEMParser parser = new PEMParser(new StringReader(privateKeyPem))) {
            Object parsed = parser.readObject();
            if (parsed == null) {
                throw new BizException(ResultCode.PARAM_INVALID, "未识别到私钥 PEM 块");
            }
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(bcProvider);
            if (parsed instanceof PEMKeyPair keyPair) {
                // PKCS#1 / SEC1 块通常内嵌公钥段，走 getKeyPair 最完整；缺公钥段时（部分工具导出）
                // 回落到只解私钥结构，避免 getKeyPair 因 publicKeyInfo 为 null 抛 NPE
                return keyPair.getPublicKeyInfo() == null
                    ? converter.getPrivateKey(keyPair.getPrivateKeyInfo())
                    : converter.getKeyPair(keyPair).getPrivate();
            }
            if (parsed instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);
            }
            throw new BizException(ResultCode.PARAM_INVALID,
                "不支持的私钥格式（加密私钥请先解密）：" + parsed.getClass().getSimpleName());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "私钥解析失败：" + e.getMessage());
        }
    }

    /** EC 家族算法名的兼容判断（BC 报 ECDSA，JDK 报 EC）。 */
    private boolean isEcAlgorithmFamily(String a, String b) {
        return isEc(a) && isEc(b);
    }

    private boolean isEc(String algorithm) {
        return "EC".equals(algorithm) || "ECDSA".equals(algorithm);
    }

    /** 按公钥算法选择探测用签名算法。 */
    private String probeSignatureAlgorithm(String publicKeyAlgorithm) {
        if ("RSA".equals(publicKeyAlgorithm)) {
            return "SHA256withRSA";
        }
        if (isEc(publicKeyAlgorithm)) {
            return "SHA256withECDSA";
        }
        if ("Ed25519".equals(publicKeyAlgorithm) || "EdDSA".equals(publicKeyAlgorithm)) {
            return "Ed25519";
        }
        throw new BizException(ResultCode.PARAM_INVALID, "不支持的公钥算法：" + publicKeyAlgorithm);
    }

    /** 汇总单张证书的展示信息。 */
    private DevToolCertInfo describeCertificate(X509Certificate cert) {
        DevToolCertInfo info = new DevToolCertInfo();
        info.setSubject(cert.getSubjectX500Principal().getName());
        info.setIssuer(cert.getIssuerX500Principal().getName());
        info.setSerialNumberHex(cert.getSerialNumber().toString(16).toUpperCase());
        info.setVersion(cert.getVersion());
        info.setNotBeforeMs(cert.getNotBefore().getTime());
        info.setNotAfterMs(cert.getNotAfter().getTime());
        Instant now = Instant.now();
        info.setExpired(now.isAfter(cert.getNotAfter().toInstant()) || now.isBefore(cert.getNotBefore().toInstant()));
        info.setDaysRemaining(Duration.between(now, cert.getNotAfter().toInstant()).toDays());
        info.setSigAlgName(cert.getSigAlgName());
        info.setPublicKeyAlgorithm(cert.getPublicKey().getAlgorithm());
        info.setPublicKeyBits(publicKeyBits(cert.getPublicKey()));
        // -1 表示无 BasicConstraints 扩展（非 CA）；>=0 表示 CA 及其路径长度
        info.setCa(cert.getBasicConstraints() >= 0);
        info.setSubjectAlternativeNames(readSubjectAlternativeNames(cert));
        info.setKeyUsages(readKeyUsages(cert));
        info.setSha1Fingerprint(fingerprint(cert, "SHA-1"));
        info.setSha256Fingerprint(fingerprint(cert, "SHA-256"));
        return info;
    }

    /** 汇总 CSR 的展示信息（含请求扩展里的 SAN）。 */
    private DevToolCsrInfo describeCsr(byte[] der) {
        try {
            JcaPKCS10CertificationRequest csr = new JcaPKCS10CertificationRequest(new PKCS10CertificationRequest(der));
            csr.setProvider(bcProvider);
            DevToolCsrInfo info = new DevToolCsrInfo();
            info.setSubject(csr.getSubject().toString());
            PublicKey publicKey = csr.getPublicKey();
            info.setPublicKeyAlgorithm(publicKey.getAlgorithm());
            info.setPublicKeyBits(publicKeyBits(publicKey));
            info.setSigAlgName(new org.bouncycastle.operator.DefaultAlgorithmNameFinder()
                .getAlgorithmName(csr.getSignatureAlgorithm()));
            info.setSubjectAlternativeNames(readCsrSubjectAlternativeNames(csr));
            return info;
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "CSR 解析失败：" + e.getMessage());
        }
    }

    private int publicKeyBits(PublicKey publicKey) {
        if (publicKey instanceof RSAPublicKey rsa) {
            return rsa.getModulus().bitLength();
        }
        if (publicKey instanceof ECPublicKey ec) {
            return ec.getParams().getCurve().getField().getFieldSize();
        }
        return 0;
    }

    private List<String> readSubjectAlternativeNames(X509Certificate cert) {
        List<String> result = new ArrayList<>();
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) {
                return result;
            }
            for (List<?> san : sans) {
                // [0]=GeneralName 类型编号, [1]=值
                Integer type = (Integer) san.get(0);
                result.add(sanTypeName(type) + ":" + san.get(1));
            }
        } catch (Exception e) {
            log.error("read subject alternative names failed, code={}", "DEVTOOL-CERT-SAN-FAIL", e);
        }
        return result;
    }

    /** CSR 请求扩展（extensionRequest 属性）里的 SAN。 */
    private List<String> readCsrSubjectAlternativeNames(PKCS10CertificationRequest csr) {
        List<String> result = new ArrayList<>();
        Attribute[] attributes = csr.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest);
        for (Attribute attribute : attributes) {
            Extensions extensions = Extensions.getInstance(attribute.getAttributeValues()[0]);
            GeneralNames names = GeneralNames.fromExtensions(extensions, Extension.subjectAlternativeName);
            if (names == null) {
                continue;
            }
            for (GeneralName name : names.getNames()) {
                result.add(sanTypeName(name.getTagNo()) + ":" + name.getName());
            }
        }
        return result;
    }

    private String sanTypeName(int type) {
        return switch (type) {
            case GeneralName.dNSName -> "DNS";
            case GeneralName.iPAddress -> "IP";
            case GeneralName.rfc822Name -> "EMAIL";
            case GeneralName.uniformResourceIdentifier -> "URI";
            default -> "OTHER(" + type + ")";
        };
    }

    private List<String> readKeyUsages(X509Certificate cert) {
        // KeyUsage 位序固定（RFC 5280）
        String[] names = {"digitalSignature", "nonRepudiation", "keyEncipherment", "dataEncipherment",
            "keyAgreement", "keyCertSign", "cRLSign", "encipherOnly", "decipherOnly"};
        List<String> result = new ArrayList<>();
        boolean[] usage = cert.getKeyUsage();
        if (usage == null) {
            return result;
        }
        for (int i = 0; i < usage.length && i < names.length; i++) {
            if (usage[i]) {
                result.add(names[i]);
            }
        }
        return result;
    }

    private String fingerprint(X509Certificate cert, String algorithm) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(digest.length * 3);
            for (byte b : digest) {
                if (sb.length() > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("cert fingerprint failed, code={}, algorithm={}", "DEVTOOL-CERT-FP-FAIL", algorithm, e);
            return "";
        }
    }

}

package com.richard.fyoung.customerwork.devtool;

import lombok.Builder;
import lombok.Getter;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.DefaultAlgorithmNameFinder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

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
 * 证书解析纯函数集：X.509 证书/证书链、PKCS#10 CSR、私钥-证书匹配、PFX/JKS 密钥库。
 *
 * <p>无 Spring 依赖、无状态，参数非法入口 fast-fail（{@link DevToolArgs}），解析失败抛
 * {@link IllegalArgumentException} 且信息具体到可自我纠正——上层 {@code DevToolboxTools} 收敛成
 * error JSON 回给 LLM，admin-server 的 {@code DevToolCertService} 转成业务异常回给页面。</p>
 *
 * <p><b>隐私边界</b>：所有输入只在方法内存中解析，不落库、不写日志。本类<b>不打任何日志</b>——
 * 入参可能是私钥明文，连异常都由调用方决定怎么记（同包其它 Ops 的一贯约定）。</p>
 *
 * <p>JDK 原生只认 X.509 证书与未加密 PKCS#8 私钥，CSR 与 PKCS#1/SEC1 私钥靠 BouncyCastle；
 * Provider 以实例方式使用，<b>不注册进全局 {@code Security}</b>，避免影响宿主应用的算法选取。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class CertDevToolOps {

    /** PEM 块通用匹配：BEGIN/END 标签 + Base64 正文。 */
    private static final Pattern PEM_BLOCK = Pattern.compile(
        "-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]+?)-----END \\1-----");

    private static final String BLOCK_CERT = "CERTIFICATE";
    private static final String BLOCK_CSR = "CERTIFICATE REQUEST";
    private static final String BLOCK_NEW_CSR = "NEW CERTIFICATE REQUEST";

    /** 签名-验签探测的固定负载（内容无意义，只求两侧一致）。 */
    private static final byte[] PROBE_PAYLOAD = "customer-work-cert-match-probe".getBytes(StandardCharsets.UTF_8);

    /** PEM 正文折行宽度（openssl 惯例 64 列）。 */
    private static final int PEM_LINE_LENGTH = 64;

    private static final byte[] PEM_LINE_SEPARATOR = "\n".getBytes(StandardCharsets.US_ASCII);

    /** KeyUsage 位序固定（RFC 5280）。 */
    private static final String[] KEY_USAGE_NAMES = {"digitalSignature", "nonRepudiation", "keyEncipherment",
        "dataEncipherment", "keyAgreement", "keyCertSign", "cRLSign", "encipherOnly", "decipherOnly"};

    private final BouncyCastleProvider bcProvider = new BouncyCastleProvider();

    // =====================================================================
    // 证书 / CSR 解析
    // =====================================================================

    /**
     * 解析 PEM 文本中的全部证书与 CSR 块，<b>不回显证书 PEM</b>。
     *
     * <p>这是给 LLM 用的默认重载：输入本来就是 PEM，再原样回显纯属浪费上下文。</p>
     *
     * @param pemContent PEM 文本，可同时含多段证书与 CSR；私钥等其它块类型被忽略
     * @return 解析结果
     * @throws IllegalArgumentException 入参空白，或一个可识别块都没有
     */
    public CertParseResult parse(String pemContent) {
        return parse(pemContent, false);
    }

    /**
     * 解析 PEM 文本中的全部证书与 CSR 块（按输入顺序，证书链场景第一张通常是叶子证书）。
     *
     * @param pemContent PEM 文本，可同时含多段证书与 CSR；私钥等其它块类型被忽略
     * @param includePem 是否在结果里回显每张证书自身的 PEM。页面传 true——贴一整个 fullchain 时
     *                   要把叶子证书与中间 CA 拆开分别取用；LLM 传 false 省上下文
     * @return 解析结果
     * @throws IllegalArgumentException 入参空白，或一个可识别块都没有
     */
    public CertParseResult parse(String pemContent, boolean includePem) {
        DevToolArgs.requireNonBlank(pemContent, "pemContent");
        List<CertInfo> certs = new ArrayList<>();
        List<CsrInfo> csrs = new ArrayList<>();
        Matcher matcher = PEM_BLOCK.matcher(pemContent);
        while (matcher.find()) {
            String label = matcher.group(1);
            byte[] der = decodeBase64Body(matcher.group(2));
            if (BLOCK_CERT.equals(label)) {
                certs.add(describeCertificate(parseX509(der), includePem));
            } else if (BLOCK_CSR.equals(label) || BLOCK_NEW_CSR.equals(label)) {
                csrs.add(describeCsr(der));
            }
            // 其余块类型（私钥等）刻意忽略：私钥只该出现在 match / exportPrivateKeyPem 入口
        }
        if (certs.isEmpty() && csrs.isEmpty()) {
            throw new IllegalArgumentException(
                "未识别到 CERTIFICATE / CERTIFICATE REQUEST PEM 块，请提供 -----BEGIN CERTIFICATE----- 格式内容");
        }
        return CertParseResult.builder().certificates(certs).csrs(csrs).build();
    }

    // =====================================================================
    // 私钥-证书匹配
    // =====================================================================

    /**
     * 私钥与证书匹配校验：私钥对固定负载签名，证书公钥验签，验签通过即配对。
     *
     * <p>用签名-验签探测而非公钥参数比对：算法无关（RSA/EC/Ed25519 通吃），也规避各算法公钥表示差异。
     * 算法本就不一致时直接给结论，不做无谓探测。</p>
     *
     * @param certPem       证书 PEM（取第一段 CERTIFICATE 块）
     * @param privateKeyPem 私钥 PEM（PKCS#8 / PKCS#1 RSA / SEC1 EC，不支持加密私钥）
     * @return 匹配结论
     * @throws IllegalArgumentException 入参空白、PEM 非法、或算法不受支持
     */
    public CertMatchResult match(String certPem, String privateKeyPem) {
        DevToolArgs.requireNonBlank(certPem, "certPem");
        DevToolArgs.requireNonBlank(privateKeyPem, "privateKeyPem");
        X509Certificate cert = firstCertificate(certPem);
        PrivateKey privateKey = parsePrivateKey(privateKeyPem);
        PublicKey publicKey = cert.getPublicKey();

        if (!privateKey.getAlgorithm().equals(publicKey.getAlgorithm())
            && !(isEc(privateKey.getAlgorithm()) && isEc(publicKey.getAlgorithm()))) {
            return CertMatchResult.builder()
                .matched(false)
                .publicKeyAlgorithm(publicKey.getAlgorithm())
                .reason("算法不一致：私钥为 " + privateKey.getAlgorithm() + "，证书公钥为 " + publicKey.getAlgorithm())
                .build();
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
            return CertMatchResult.builder()
                .matched(matched)
                .publicKeyAlgorithm(publicKey.getAlgorithm())
                .reason(matched ? "私钥签名可被证书公钥验签，二者配对" : "私钥签名无法被证书公钥验签，二者不配对")
                .build();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("匹配探测失败：" + e.getMessage());
        }
    }

    // =====================================================================
    // 密钥库
    // =====================================================================

    /**
     * 解析 PFX/PKCS12 或 JKS 密钥库：按 PKCS12 → JKS 顺序尝试，列出全部条目及其证书链。
     *
     * <p>仅供上传文件的场景使用（admin 页面），<b>不暴露给 LLM</b>——智能体没有文件通道。</p>
     *
     * @param data     密钥库字节
     * @param password 库密码，null 视作空密码
     * @return 库类型与条目
     * @throws IllegalArgumentException 数据为空、密码错误或文件损坏
     */
    public KeystoreParseResult parseKeystore(byte[] data, String password) {
        LoadedKeystore loaded = loadKeystore(data, password);
        KeyStore keyStore = loaded.getKeyStore();
        try {
            List<KeystoreEntry> entries = new ArrayList<>();
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                List<CertInfo> chain = new ArrayList<>();
                String entryType;
                if (keyStore.isKeyEntry(alias)) {
                    entryType = "PRIVATE_KEY";
                    Certificate[] certs = keyStore.getCertificateChain(alias);
                    if (certs != null) {
                        for (Certificate cert : certs) {
                            if (cert instanceof X509Certificate x509) {
                                chain.add(describeCertificate(x509, true));
                            }
                        }
                    }
                } else {
                    entryType = "TRUSTED_CERT";
                    Certificate cert = keyStore.getCertificate(alias);
                    if (cert instanceof X509Certificate x509) {
                        chain.add(describeCertificate(x509, true));
                    }
                }
                entries.add(KeystoreEntry.builder().alias(alias).entryType(entryType).chain(chain).build());
            }
            return KeystoreParseResult.builder().keystoreType(loaded.getType()).entries(entries).build();
        } catch (Exception e) {
            throw new IllegalArgumentException("密钥库条目读取失败：" + e.getMessage());
        }
    }

    /** 打开密钥库：按 PKCS12 → JKS 顺序尝试，两个入口（列举条目 / 导出私钥）共用同一套加载与报错。 */
    private LoadedKeystore loadKeystore(byte[] data, String password) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("密钥库内容不能为空");
        }
        char[] pwd = nullToEmpty(password).toCharArray();
        Exception lastError = null;
        for (String type : new String[] {"PKCS12", "JKS"}) {
            try {
                KeyStore candidate = KeyStore.getInstance(type);
                candidate.load(new ByteArrayInputStream(data), pwd);
                return LoadedKeystore.builder().keyStore(candidate).type(type).build();
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw new IllegalArgumentException("密钥库无法打开（已尝试 PKCS12/JKS）：密码错误或文件损坏"
            + (lastError == null ? "" : "（" + lastError.getMessage() + "）"));
    }

    /**
     * 从密钥库导出指定条目的私钥 PEM（PKCS#8，未加密），配合 {@link #parseKeystore} 的证书 PEM
     * 即可把一个 pfx 拆成 nginx 要的 crt + key。
     *
     * <p><b>刻意做成独立方法而非 {@code parseKeystore} 的一个字段</b>：列举条目是浏览即触发的操作，
     * 私钥不该在用户只想看看有什么条目时就被吐出来。调用方应做成显式动作（点击导出）。</p>
     *
     * @param data        密钥库字节
     * @param password    库密码，null 视作空密码
     * @param alias       条目别名，须是私钥条目
     * @param keyPassword 条目私钥密码；PKCS12 通常与库密码相同，留空即回落库密码（JKS 常见两者不同）
     * @return 私钥算法与 PKCS#8 PEM
     * @throws IllegalArgumentException 别名不存在/非私钥条目/密码错误
     */
    public PrivateKeyExport exportPrivateKeyPem(byte[] data, String password, String alias, String keyPassword) {
        DevToolArgs.requireNonBlank(alias, "alias");
        KeyStore keyStore = loadKeystore(data, password).getKeyStore();
        char[] pwd = (keyPassword == null || keyPassword.isEmpty() ? nullToEmpty(password) : keyPassword)
            .toCharArray();
        try {
            if (!keyStore.containsAlias(alias)) {
                throw new IllegalArgumentException("别名不存在：" + alias);
            }
            if (!keyStore.isKeyEntry(alias)) {
                throw new IllegalArgumentException("该别名不是私钥条目（仅含证书，无私钥可导出）：" + alias);
            }
            java.security.Key key = keyStore.getKey(alias, pwd);
            if (!(key instanceof PrivateKey privateKey)) {
                throw new IllegalArgumentException("该条目不是非对称私钥：" + alias);
            }
            return PrivateKeyExport.builder()
                .algorithm(privateKey.getAlgorithm())
                .pem(encodePem("PRIVATE KEY", privateKey.getEncoded()))
                .build();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (java.security.UnrecoverableKeyException e) {
            throw new IllegalArgumentException("私钥密码错误（PKCS12 通常与库密码相同，JKS 可能不同）");
        } catch (Exception e) {
            throw new IllegalArgumentException("私钥导出失败：" + e.getMessage());
        }
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 证书 PEM 编码（DER base64 + 64 列折行，与 openssl 输出一致）。 */
    private String encodeCertPem(X509Certificate cert) {
        try {
            return encodePem(BLOCK_CERT, cert.getEncoded());
        } catch (Exception e) {
            throw new IllegalArgumentException("证书 PEM 编码失败：" + e.getMessage());
        }
    }

    /** 通用 PEM 封装。 */
    private String encodePem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(PEM_LINE_LENGTH, PEM_LINE_SEPARATOR).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private byte[] decodeBase64Body(String body) {
        try {
            return Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PEM 正文不是合法 Base64");
        }
    }

    private X509Certificate parseX509(byte[] der) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("证书解析失败：" + e.getMessage());
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
        throw new IllegalArgumentException("未识别到 CERTIFICATE PEM 块");
    }

    /** 解析私钥 PEM：PEMParser 兼容 PKCS#8 / PKCS#1 RSA / SEC1 EC；加密私钥明确报错。 */
    private PrivateKey parsePrivateKey(String privateKeyPem) {
        try (PEMParser parser = new PEMParser(new StringReader(privateKeyPem))) {
            Object parsed = parser.readObject();
            if (parsed == null) {
                throw new IllegalArgumentException("未识别到私钥 PEM 块");
            }
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(bcProvider);
            if (parsed instanceof PEMKeyPair keyPair) {
                // PKCS#1 / SEC1 块通常内嵌公钥段，走 getKeyPair 最完整；缺公钥段时（部分工具导出）
                // 回落到只解私钥结构，避免 getKeyPair 因 publicKeyInfo 为 null 抛 NPE
                return keyPair.getPublicKeyInfo() == null
                    ? converter.getPrivateKey(keyPair.getPrivateKeyInfo())
                    : converter.getKeyPair(keyPair).getPrivate();
            }
            if (parsed instanceof PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);
            }
            throw new IllegalArgumentException(
                "不支持的私钥格式（加密私钥请先解密）：" + parsed.getClass().getSimpleName());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("私钥解析失败：" + e.getMessage());
        }
    }

    /** EC 家族算法名的兼容判断（BC 报 ECDSA，JDK 报 EC）。 */
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
        throw new IllegalArgumentException("不支持的公钥算法：" + publicKeyAlgorithm);
    }

    /** 汇总单张证书的展示信息。 */
    private CertInfo describeCertificate(X509Certificate cert, boolean includePem) {
        Instant now = Instant.now();
        return CertInfo.builder()
            .pem(includePem ? encodeCertPem(cert) : null)
            .subject(cert.getSubjectX500Principal().getName())
            .issuer(cert.getIssuerX500Principal().getName())
            .serialNumberHex(cert.getSerialNumber().toString(16).toUpperCase())
            .version(cert.getVersion())
            .notBeforeMs(cert.getNotBefore().getTime())
            .notAfterMs(cert.getNotAfter().getTime())
            .expired(now.isAfter(cert.getNotAfter().toInstant()) || now.isBefore(cert.getNotBefore().toInstant()))
            .daysRemaining(Duration.between(now, cert.getNotAfter().toInstant()).toDays())
            .sigAlgName(cert.getSigAlgName())
            .publicKeyAlgorithm(cert.getPublicKey().getAlgorithm())
            .publicKeyBits(publicKeyBits(cert.getPublicKey()))
            // -1 表示无 BasicConstraints 扩展（非 CA）；>=0 表示 CA 及其路径长度
            .ca(cert.getBasicConstraints() >= 0)
            .subjectAlternativeNames(readSubjectAlternativeNames(cert))
            .keyUsages(readKeyUsages(cert))
            .sha1Fingerprint(fingerprint(cert, "SHA-1"))
            .sha256Fingerprint(fingerprint(cert, "SHA-256"))
            .build();
    }

    /** 汇总 CSR 的展示信息（含请求扩展里的 SAN）。 */
    private CsrInfo describeCsr(byte[] der) {
        try {
            JcaPKCS10CertificationRequest csr = new JcaPKCS10CertificationRequest(new PKCS10CertificationRequest(der));
            csr.setProvider(bcProvider);
            PublicKey publicKey = csr.getPublicKey();
            return CsrInfo.builder()
                .subject(csr.getSubject().toString())
                .publicKeyAlgorithm(publicKey.getAlgorithm())
                .publicKeyBits(publicKeyBits(publicKey))
                .sigAlgName(new DefaultAlgorithmNameFinder().getAlgorithmName(csr.getSignatureAlgorithm()))
                .subjectAlternativeNames(readCsrSubjectAlternativeNames(csr))
                .build();
        } catch (Exception e) {
            throw new IllegalArgumentException("CSR 解析失败：" + e.getMessage());
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
                result.add(sanTypeName((Integer) san.get(0)) + ":" + san.get(1));
            }
        } catch (Exception e) {
            // SAN 扩展畸形不该让整张证书解析失败：其余字段仍有价值，此处降级为"没有 SAN"
            return result;
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
        List<String> result = new ArrayList<>();
        boolean[] usage = cert.getKeyUsage();
        if (usage == null) {
            return result;
        }
        for (int i = 0; i < usage.length && i < KEY_USAGE_NAMES.length; i++) {
            if (usage[i]) {
                result.add(KEY_USAGE_NAMES[i]);
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
            throw new IllegalArgumentException("指纹计算失败（" + algorithm + "）：" + e.getMessage());
        }
    }

    // =====================================================================
    // 结果对象（贫血数据袋，Jackson 经 getter 序列化）
    // =====================================================================

    /** 证书/CSR 解析结果。 */
    @Getter
    @Builder
    public static final class CertParseResult {
        private final List<CertInfo> certificates;
        private final List<CsrInfo> csrs;
    }

    /** 单张 X.509 证书信息。 */
    @Getter
    @Builder
    public static final class CertInfo {
        /** 该证书自身的 PEM；仅在调用方要求回显时填充（LLM 侧为 null，省上下文）。 */
        private final String pem;
        private final String subject;
        private final String issuer;
        private final String serialNumberHex;
        private final int version;
        private final long notBeforeMs;
        private final long notAfterMs;
        private final boolean expired;
        private final long daysRemaining;
        private final String sigAlgName;
        private final String publicKeyAlgorithm;
        private final int publicKeyBits;
        private final boolean ca;
        private final List<String> subjectAlternativeNames;
        private final List<String> keyUsages;
        private final String sha1Fingerprint;
        private final String sha256Fingerprint;
    }

    /** PKCS#10 CSR 信息。 */
    @Getter
    @Builder
    public static final class CsrInfo {
        private final String subject;
        private final String publicKeyAlgorithm;
        private final int publicKeyBits;
        private final String sigAlgName;
        private final List<String> subjectAlternativeNames;
    }

    /** 私钥-证书匹配结论。 */
    @Getter
    @Builder
    public static final class CertMatchResult {
        private final boolean matched;
        private final String publicKeyAlgorithm;
        private final String reason;
    }

    /** 私钥导出结果（PKCS#8 未加密 PEM）。 */
    @Getter
    @Builder
    public static final class PrivateKeyExport {
        private final String algorithm;
        private final String pem;
    }

    /** 密钥库解析结果。 */
    @Getter
    @Builder
    public static final class KeystoreParseResult {
        private final String keystoreType;
        private final List<KeystoreEntry> entries;
    }

    /** 已打开的密钥库（内部流转，不外泄）。 */
    @Getter
    @Builder
    private static final class LoadedKeystore {
        private final KeyStore keyStore;
        private final String type;
    }

    /** 密钥库条目。 */
    @Getter
    @Builder
    public static final class KeystoreEntry {
        private final String alias;
        private final String entryType;
        private final List<CertInfo> chain;
    }
}

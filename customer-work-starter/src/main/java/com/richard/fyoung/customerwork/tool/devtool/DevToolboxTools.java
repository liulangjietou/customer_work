package com.richard.fyoung.customerwork.tool.devtool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.concurrent.Callable;

/**
 * 开发者工具箱系统工具（system tool {@code devtoolbox}）。给挂载本工具的智能体提供一组开发者常用的
 * 本地计算能力：JSON 格式化/压缩/校验、时间戳转换、Base64/URL 编解码、哈希(HMAC)、UUID 生成、
 * AES 加解密、正则测试、证书解析与私钥匹配校验，均为纯本地计算，不访问外部资源。
 *
 * <p><b>{@code cert_match} 的授权取舍</b>：该工具需要私钥明文作为参数，意味着私钥会进入模型上下文
 * 并落进对话历史。这是产品上明确接受的代价（用户拍板全开），故在工具描述里写死了使用约束
 * （仅在用户明确要求时调用、不主动索取、不复述）——给智能体挂载本工具前应知悉这一点。</p>
 *
 * <p>本类是暴露给 LLM 的工具 Schema 壳（纯 POJO，不加 {@code @Component}——Spring 装配由 admin-server 侧
 * 的 {@code DevToolboxConfig} 显式 new）。Bean 名须精确等于 tool_code "devtoolbox"，运行时
 * {@code AdminAgentInstanceFactory#buildSystemTools} 通过 {@code applicationContext.getBean("devtoolbox")}
 * 按名取 Bean 注册进该智能体的 Toolkit。</p>
 *
 * <p>约定：业务实现委托给同包内 4 个无状态纯函数 Ops；本壳只负责响应式包装、结果序列化、
 * 异常收敛与输出截断。任何异常都收敛为 {@code {"error":"..."}} 返回而非抛出，错误信息尽量具体到
 * 可自我纠正（如 JSON 校验失败带行列号）。日志只记工具名与异常，绝不打印用户输入（可能是敏感明文）。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class DevToolboxTools {

    private static final Logger log = LoggerFactory.getLogger(DevToolboxTools.class);

    /** 统一错误码。 */
    private static final String ERROR_CODE = "DEVTOOL-EXEC-FAIL";

    /** 结果字符串截断阈值（超过则截断，防止大结果撑爆 LLM 上下文）。 */
    private static final int TRUNCATE_THRESHOLD = 8000;

    /** JSON 默认缩进宽度。 */
    private static final int DEFAULT_INDENT = 2;

    /** UUID 默认生成数量。 */
    private static final int DEFAULT_UUID_COUNT = 1;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final JsonDevToolOps jsonOps = new JsonDevToolOps();
    private final TimestampDevToolOps timestampOps = new TimestampDevToolOps();
    private final CodecDevToolOps codecOps = new CodecDevToolOps();
    private final RegexDevToolOps regexOps = new RegexDevToolOps();
    private final CertDevToolOps certOps = new CertDevToolOps();

    // =====================================================================
    // JSON
    // =====================================================================

    @Tool(name = "json_format", description = "美化(格式化)JSON文本，按指定缩进重排便于阅读。"
        + "参数 json：待格式化的JSON字符串，如 {\"a\":1,\"b\":[2,3]}；indent：缩进空格数，仅支持2或4，默认2。"
        + "JSON非法时返回带行列号的error，便于定位。")
    public Mono<String> jsonFormat(
        @ToolParam(name = "json", description = "待格式化的JSON字符串") String json,
        @ToolParam(name = "indent", required = false, description = "缩进空格数，2或4，默认2") Integer indent) {
        return respond("json_format", () -> jsonOps.format(json, indent == null ? DEFAULT_INDENT : indent));
    }

    @Tool(name = "json_minify", description = "压缩JSON文本，去除所有多余空白输出最紧凑形式。"
        + "参数 json：待压缩的JSON字符串。JSON非法时返回带行列号的error。")
    public Mono<String> jsonMinify(
        @ToolParam(name = "json", description = "待压缩的JSON字符串") String json) {
        return respond("json_minify", () -> jsonOps.minify(json));
    }

    @Tool(name = "json_validate", description = "校验JSON文本是否合法。返回 valid(是否合法)、errorMessage(错误原因)、"
        + "line/column(出错行列号，合法时为-1)。用于快速判断一段文本是不是合法JSON并定位错误位置。")
    public Mono<String> jsonValidate(
        @ToolParam(name = "json", description = "待校验的JSON字符串") String json) {
        return respond("json_validate", () -> jsonOps.validate(json));
    }

    // =====================================================================
    // Timestamp
    // =====================================================================

    @Tool(name = "timestamp_convert", description = "时间戳与日期时间互转。自动判定方向："
        + "输入纯数字10位按秒、13位按毫秒转为日期时间；输入日期时间字符串"
        + "(支持 ISO8601 / yyyy-MM-dd HH:mm:ss / yyyy-MM-dd)转为秒和毫秒时间戳。"
        + "参数 value：时间戳或日期时间字符串，如 1721520000 或 2026-07-21 10:00:00；"
        + "timezone：时区ID，如 Asia/Shanghai，默认 Asia/Shanghai。"
        + "返回 datetime、timestampSeconds、timestampMillis、timezone、dayOfWeek。")
    public Mono<String> timestampConvert(
        @ToolParam(name = "value", description = "时间戳(10/13位数字)或日期时间字符串") String value,
        @ToolParam(name = "timezone", required = false, description = "时区ID，默认 Asia/Shanghai") String timezone) {
        return respond("timestamp_convert", () -> timestampOps.convert(value, timezone));
    }

    // =====================================================================
    // Codec
    // =====================================================================

    @Tool(name = "base64_encode", description = "对文本做Base64编码(UTF-8)。参数 text：待编码文本。返回 result 字段为编码结果。")
    public Mono<String> base64Encode(
        @ToolParam(name = "text", description = "待编码文本") String text) {
        return respond("base64_encode", () -> wrapResult(codecOps.base64Encode(text)));
    }

    @Tool(name = "base64_decode", description = "对Base64文本做解码(UTF-8)。参数 text：Base64字符串。"
        + "输入非法Base64时返回error。返回 result 字段为解码后的文本。")
    public Mono<String> base64Decode(
        @ToolParam(name = "text", description = "待解码的Base64字符串") String text) {
        return respond("base64_decode", () -> wrapResult(codecOps.base64Decode(text)));
    }

    @Tool(name = "url_encode", description = "对文本做URL编码(application/x-www-form-urlencoded, UTF-8)。"
        + "参数 text：待编码文本。返回 result 字段为编码结果。")
    public Mono<String> urlEncode(
        @ToolParam(name = "text", description = "待编码文本") String text) {
        return respond("url_encode", () -> wrapResult(codecOps.urlEncode(text)));
    }

    @Tool(name = "url_decode", description = "对URL编码文本做解码(UTF-8)。参数 text：URL编码字符串。返回 result 字段为解码结果。")
    public Mono<String> urlDecode(
        @ToolParam(name = "text", description = "待解码的URL编码字符串") String text) {
        return respond("url_decode", () -> wrapResult(codecOps.urlDecode(text)));
    }

    @Tool(name = "text_hash", description = "计算文本摘要，输出小写hex。参数 algorithm：算法，取值 MD5/SHA-1/SHA-256/SHA-512；"
        + "text：待摘要文本；hmacKey：可选，提供时走对应的HMAC(HmacMD5/HmacSHA1/HmacSHA256/HmacSHA512)。"
        + "返回 result 字段为hex摘要。")
    public Mono<String> textHash(
        @ToolParam(name = "algorithm", description = "算法：MD5/SHA-1/SHA-256/SHA-512") String algorithm,
        @ToolParam(name = "text", description = "待摘要文本") String text,
        @ToolParam(name = "hmacKey", required = false, description = "可选HMAC密钥，提供则走HMAC") String hmacKey) {
        return respond("text_hash", () -> wrapResult(codecOps.hash(algorithm, text, hmacKey)));
    }

    @Tool(name = "uuid_generate", description = "批量生成随机UUID(v4)。参数 count：生成数量，范围1~20，默认1。"
        + "返回 result 字段为UUID字符串数组。")
    public Mono<String> uuidGenerate(
        @ToolParam(name = "count", required = false, description = "生成数量，1~20，默认1") Integer count) {
        return respond("uuid_generate", () -> wrapResult(codecOps.uuid(count == null ? DEFAULT_UUID_COUNT : count)));
    }

    @Tool(name = "aes_encrypt", description = "AES加密，密文以Base64输出。参数 plainText：明文；key：密钥，UTF-8字节长度须为16/24/32；"
        + "mode：模式 CBC/ECB/GCM，默认CBC；iv：可选Base64编码的IV，CBC/GCM缺省时随机生成并随结果返回，ECB无需IV。"
        + "返回 mode、ciphertext(Base64)、iv(Base64，ECB时为null)。")
    public Mono<String> aesEncrypt(
        @ToolParam(name = "plainText", description = "待加密明文") String plainText,
        @ToolParam(name = "key", description = "密钥，UTF-8字节长度16/24/32") String key,
        @ToolParam(name = "mode", required = false, description = "模式 CBC/ECB/GCM，默认CBC") String mode,
        @ToolParam(name = "iv", required = false, description = "可选IV(Base64)，缺省随机生成") String iv) {
        return respond("aes_encrypt", () -> codecOps.aesEncrypt(plainText, key, mode, iv));
    }

    @Tool(name = "aes_decrypt", description = "AES解密，密文为Base64。参数 cipherText：Base64密文；key：密钥，UTF-8字节长度须为16/24/32；"
        + "mode：模式 CBC/ECB/GCM，默认CBC；iv：Base64编码的IV，CBC/GCM必填，须与加密时一致，ECB无需IV。"
        + "返回 result 字段为解密后的明文。")
    public Mono<String> aesDecrypt(
        @ToolParam(name = "cipherText", description = "待解密的Base64密文") String cipherText,
        @ToolParam(name = "key", description = "密钥，UTF-8字节长度16/24/32") String key,
        @ToolParam(name = "mode", required = false, description = "模式 CBC/ECB/GCM，默认CBC") String mode,
        @ToolParam(name = "iv", required = false, description = "IV(Base64)，CBC/GCM必填") String iv) {
        return respond("aes_decrypt", () -> wrapResult(codecOps.aesDecrypt(cipherText, key, mode, iv)));
    }

    // =====================================================================
    // Regex
    // =====================================================================

    @Tool(name = "regex_test", description = "正则匹配测试。参数 pattern：正则表达式；text：待匹配文本；"
        + "flags：可选，任意组合 i(忽略大小写)/m(多行)/s(dotall)。"
        + "返回 matchCount(匹配总数)、truncated(是否因超100条截断)、matches(每个匹配的start/end/value/groups)。")
    public Mono<String> regexTest(
        @ToolParam(name = "pattern", description = "正则表达式") String pattern,
        @ToolParam(name = "text", description = "待匹配文本") String text,
        @ToolParam(name = "flags", required = false, description = "标志位组合 i/m/s") String flags) {
        return respond("regex_test", () -> regexOps.test(pattern, text, flags));
    }

    // =====================================================================
    // 证书
    // =====================================================================

    @Tool(name = "cert_parse", description = "解析X.509证书、证书链或CSR(证书签名请求)。参数 pemContent："
        + "PEM文本，即 -----BEGIN CERTIFICATE----- 或 -----BEGIN CERTIFICATE REQUEST----- 包裹的内容，"
        + "可一次传入多段(证书链按输入顺序返回，第一张通常是叶子证书)。"
        + "返回 certificates 数组，每项含 subject(使用者)、issuer(颁发者)、serialNumberHex(序列号)、"
        + "notBeforeMs/notAfterMs(有效期毫秒时间戳)、expired(是否已过期或未生效)、daysRemaining(距过期天数，负数表示已过期)、"
        + "sigAlgName(签名算法)、publicKeyAlgorithm/publicKeyBits(公钥算法与长度)、ca(是否CA证书)、"
        + "subjectAlternativeNames(SAN，形如 DNS:a.com)、keyUsages(密钥用法)、sha1Fingerprint/sha256Fingerprint(指纹)；"
        + "以及 csrs 数组，每项含 subject、publicKeyAlgorithm/publicKeyBits、sigAlgName、subjectAlternativeNames。"
        + "判断证书是否过期看 expired 与 daysRemaining，不要自行比较时间戳。")
    public Mono<String> certParse(
        @ToolParam(name = "pemContent", description = "PEM格式的证书/证书链/CSR文本") String pemContent) {
        return respond("cert_parse", () -> certOps.parse(pemContent));
    }

    @Tool(name = "cert_match", description = "校验私钥与证书是否配对(私钥对固定负载签名、证书公钥验签)。"
        + "参数 certPem：证书PEM；privateKeyPem：私钥PEM，支持 PKCS#8(-----BEGIN PRIVATE KEY-----)、"
        + "PKCS#1(-----BEGIN RSA PRIVATE KEY-----)、SEC1(-----BEGIN EC PRIVATE KEY-----)，不支持加密私钥。"
        + "返回 matched(是否配对)、publicKeyAlgorithm(证书公钥算法)、reason(结论说明)。"
        + "【安全提醒】调用本工具意味着私钥明文会进入模型上下文并被写入对话历史，仅在用户明确要求校验配对时使用；"
        + "不要主动索取私钥，也不要在回复里复述私钥内容。")
    public Mono<String> certMatch(
        @ToolParam(name = "certPem", description = "证书PEM文本") String certPem,
        @ToolParam(name = "privateKeyPem", description = "私钥PEM文本(不支持加密私钥)") String privateKeyPem) {
        return respond("cert_match", () -> certOps.match(certPem, privateKeyPem));
    }

    // =====================================================================
    // 内部：响应式包装 + 序列化 + 异常收敛 + 截断
    // =====================================================================

    /** 纯计算走 fromCallable 即可（不阻塞、无需 boundedElastic）；异常统一收敛为 error JSON。 */
    private Mono<String> respond(String toolName, Callable<Object> resultSupplier) {
        return Mono.fromCallable(() -> {
            try {
                return serializeWithTruncation(resultSupplier.call());
            } catch (Exception e) {
                // 只记工具名与异常，禁止打印用户输入（可能含敏感明文）
                log.error("dev tool execute failed, code={}, tool={}", ERROR_CODE, toolName, e);
                return errorJson(e.getMessage());
            }
        });
    }

    /** 把标量结果统一包成 {"result": ...} 便于 LLM 稳定取值。 */
    private ObjectNode wrapResult(Object value) {
        return objectMapper.valueToTree(java.util.Collections.singletonMap("result", value));
    }

    /** 序列化结果；超过阈值截断并标注 truncated 与原始长度。 */
    private String serializeWithTruncation(Object result) throws Exception {
        String json = objectMapper.writeValueAsString(result);
        if (json.length() <= TRUNCATE_THRESHOLD) {
            return json;
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("truncated", true);
        node.put("originalLength", json.length());
        node.put("result", json.substring(0, TRUNCATE_THRESHOLD));
        return objectMapper.writeValueAsString(node);
    }

    /** 构造 error JSON。 */
    private String errorJson(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("error", message == null ? "unknown error" : message);
        return node.toString();
    }
}

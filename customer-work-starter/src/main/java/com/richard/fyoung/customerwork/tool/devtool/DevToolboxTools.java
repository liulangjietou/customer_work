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
 * 本地计算能力：JSON 格式化/压缩/校验/转义/去转义/Unicode 解码、时间戳转换、Base64/URL/Hex 编解码、
 * 哈希(HMAC)、UUID 生成、AES 加解密、正则测试、证书解析与私钥匹配校验、cron 解析、JWT 解析、
 * 文本比对、JSON/YAML/XML 互转，均为纯本地计算，不访问外部资源。
 *
 * <p><b>与管理台"开发者工具箱"页面的关系</b>：算法一律实现在同包的 Ops 里，页面与本工具共用，
 * 不做两套。唯一例外是 AES——页面版为了让密钥不出浏览器而用 crypto-js 本地算，因此
 * {@link CodecDevToolOps} 刻意做成页面版能力的严格超集，两侧参数填一致时密文互通。</p>
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
    private final CronDevToolOps cronOps = new CronDevToolOps();
    private final JwtDevToolOps jwtOps = new JwtDevToolOps();
    private final DiffDevToolOps diffOps = new DiffDevToolOps();
    private final DataFormatDevToolOps dataFormatOps = new DataFormatDevToolOps();

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

    @Tool(name = "json_escape", description = "把任意文本转义成可安全嵌入JSON的字符串字面量(结果含外层双引号)。"
        + "参数 text：原文。用于把一段内容作为值塞进JSON字段前先做转义。返回 result 字段为转义结果。")
    public Mono<String> jsonEscape(
        @ToolParam(name = "text", description = "待转义的原文") String text) {
        return respond("json_escape", () -> wrapResult(jsonOps.escape(text)));
    }

    @Tool(name = "json_unescape", description = "把转义字符串还原成原文，外层双引号可有可无。参数 text：转义串。"
        + "最常用于把日志里被转义成一整行的JSON还原成可读内容(再配合 json_format 美化)。"
        + "返回 result 字段为还原后的原文。")
    public Mono<String> jsonUnescape(
        @ToolParam(name = "text", description = "待还原的转义字符串") String text) {
        return respond("json_unescape", () -> wrapResult(jsonOps.unescape(text)));
    }

    @Tool(name = "json_unicode_decode", description = "把文本里的Unicode转义序列(形如 \\u4e2d\\u6587)解码成真实字符，"
        + "其余内容原样保留。参数 text：含该转义序列的文本。用于把日志里被转义的中文还原成可读文字。"
        + "返回 result 字段为解码结果。")
    public Mono<String> jsonUnicodeDecode(
        @ToolParam(name = "text", description = "含 Unicode 转义序列的文本") String text) {
        return respond("json_unicode_decode", () -> wrapResult(jsonOps.decodeUnicode(text)));
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

    @Tool(name = "hex_encode", description = "把文本按UTF-8取字节后转成小写十六进制。参数 text：待编码文本。"
        + "返回 result 字段为hex字符串。")
    public Mono<String> hexEncode(
        @ToolParam(name = "text", description = "待编码文本") String text) {
        return respond("hex_encode", () -> wrapResult(codecOps.hexEncode(text)));
    }

    @Tool(name = "hex_decode", description = "把十六进制字符串按UTF-8解码成文本。参数 hex：hex字符串，"
        + "大小写均可、空白会被忽略，长度须为偶数。返回 result 字段为解码后的文本。")
    public Mono<String> hexDecode(
        @ToolParam(name = "hex", description = "待解码的十六进制字符串") String hex) {
        return respond("hex_decode", () -> wrapResult(codecOps.hexDecode(hex)));
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

    @Tool(name = "aes_encrypt", description = "AES加密。参数 plainText：明文；key：密钥(按keyEncoding解码后须为16/24/32字节)；"
        + "keyEncoding：密钥编码 utf8(默认)/hex/base64；mode：模式 CBC(默认)/ECB/CTR/GCM；"
        + "padding：填充 PKCS7/NONE，留空时CBC与ECB用PKCS7、CTR与GCM无填充；"
        + "iv：初始向量，ECB不用、其余缺省时随机生成并随结果返回(CBC/CTR为16字节、GCM为12字节)；"
        + "ivEncoding：IV编码 utf8/hex/base64(默认)；outputFormat：密文输出格式 hex/base64(默认)。"
        + "返回 mode、padding、outputFormat、ciphertext、iv。"
        + "【与管理台页面互通】页面版AES固定用CBC/ECB/CTR，若要解密页面产出的密文，"
        + "务必把mode、padding、keyEncoding、ivEncoding、outputFormat都填成与页面上一致的值。")
    public Mono<String> aesEncrypt(
        @ToolParam(name = "plainText", description = "待加密明文") String plainText,
        @ToolParam(name = "key", description = "密钥，按keyEncoding解码后须为16/24/32字节") String key,
        @ToolParam(name = "keyEncoding", required = false, description = "密钥编码 utf8(默认)/hex/base64") String keyEncoding,
        @ToolParam(name = "mode", required = false, description = "模式 CBC(默认)/ECB/CTR/GCM") String mode,
        @ToolParam(name = "padding", required = false, description = "填充 PKCS7/NONE，留空按模式取默认") String padding,
        @ToolParam(name = "iv", required = false, description = "IV，缺省随机生成；ECB不用") String iv,
        @ToolParam(name = "ivEncoding", required = false, description = "IV编码 utf8/hex/base64(默认)") String ivEncoding,
        @ToolParam(name = "outputFormat", required = false, description = "密文输出格式 hex/base64(默认)") String outputFormat) {
        return respond("aes_encrypt", () -> codecOps.aesEncrypt(plainText,
            buildAesParams(key, keyEncoding, mode, padding, iv, ivEncoding, outputFormat)));
    }

    @Tool(name = "aes_decrypt", description = "AES解密。参数 cipherText：密文(格式由outputFormat指定)；"
        + "key：密钥(按keyEncoding解码后须为16/24/32字节)；keyEncoding：密钥编码 utf8(默认)/hex/base64；"
        + "mode：模式 CBC(默认)/ECB/CTR/GCM；padding：填充 PKCS7/NONE，留空时CBC与ECB用PKCS7、CTR与GCM无填充；"
        + "iv：初始向量，除ECB外必填且须与加密时一致；ivEncoding：IV编码 utf8/hex/base64(默认)；"
        + "outputFormat：密文格式 hex/base64(默认)。返回 result 字段为明文。"
        + "所有参数必须与加密时完全一致，任一不符都会解出乱码或直接报错。")
    public Mono<String> aesDecrypt(
        @ToolParam(name = "cipherText", description = "待解密密文，格式由outputFormat指定") String cipherText,
        @ToolParam(name = "key", description = "密钥，按keyEncoding解码后须为16/24/32字节") String key,
        @ToolParam(name = "keyEncoding", required = false, description = "密钥编码 utf8(默认)/hex/base64") String keyEncoding,
        @ToolParam(name = "mode", required = false, description = "模式 CBC(默认)/ECB/CTR/GCM") String mode,
        @ToolParam(name = "padding", required = false, description = "填充 PKCS7/NONE，留空按模式取默认") String padding,
        @ToolParam(name = "iv", required = false, description = "IV，除ECB外必填") String iv,
        @ToolParam(name = "ivEncoding", required = false, description = "IV编码 utf8/hex/base64(默认)") String ivEncoding,
        @ToolParam(name = "outputFormat", required = false, description = "密文格式 hex/base64(默认)") String outputFormat) {
        return respond("aes_decrypt", () -> wrapResult(codecOps.aesDecrypt(cipherText,
            buildAesParams(key, keyEncoding, mode, padding, iv, ivEncoding, outputFormat))));
    }

    /** 组装 AES 参数（加解密两侧参数完全一致，抽出来避免两处漏填某一项）。 */
    private CodecDevToolOps.AesParams buildAesParams(String key, String keyEncoding, String mode, String padding,
                                                     String iv, String ivEncoding, String outputFormat) {
        return CodecDevToolOps.AesParams.builder()
            .key(key)
            .keyEncoding(keyEncoding)
            .mode(mode)
            .padding(padding)
            .iv(iv)
            .ivEncoding(ivEncoding)
            .outputFormat(outputFormat)
            .build();
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
    // Cron / JWT / 文本比对 / 格式互转
    // =====================================================================

    @Tool(name = "cron_explain", description = "解析cron表达式：校验合法性、逐字段释义、推算后续执行时间。"
        + "参数 expression：6段cron(秒 分 时 日 月 周)，与本项目调度中心XXL-JOB一致，如 0 0 2 * * ?；"
        + "count：推算几次执行时间，1~20，默认5；timezone：时区ID，默认 Asia/Shanghai。"
        + "返回 expression、timezone、fields(每段的name/value/range/description)、nextTimes(后续执行时间列表)。"
        + "注意：5段的Unix风格cron在这里非法，需在最前面补一段秒；判断任务何时执行请直接看nextTimes，不要自行推算。")
    public Mono<String> cronExplain(
        @ToolParam(name = "expression", description = "6段cron表达式(秒 分 时 日 月 周)") String expression,
        @ToolParam(name = "count", required = false, description = "推算次数，1~20，默认5") Integer count,
        @ToolParam(name = "timezone", required = false, description = "时区ID，默认 Asia/Shanghai") String timezone) {
        return respond("cron_explain", () -> cronOps.explain(expression, count, timezone));
    }

    @Tool(name = "jwt_decode", description = "解析JWT：拆出header与payload、解读标准声明与有效期，可选校验签名。"
        + "参数 token：JWT字符串(header.payload.signature)；secret：可选，仅HS256/HS384/HS512可校验签名，不传则不校验；"
        + "secretEncoding：密钥编码 utf8(默认)/hex/base64。"
        + "返回 algorithm、type、header、payload、issuer/subject/audience/jwtId、"
        + "issuedAt/notBefore/expiresAt(已格式化为北京时间)、expired(是否过期)、notYetValid(是否未生效)、"
        + "secondsRemaining(距过期秒数)、unsigned(是否alg=none)、"
        + "signatureStatus(VALID/INVALID/NOT_CHECKED未提供密钥/UNSUPPORTED_ALG非HS*不验签)。"
        + "判断是否过期看expired字段，不要自行比较时间戳；signatureStatus为UNSUPPORTED_ALG时不能宣称签名有效。"
        + "【安全提醒】token与secret会进入模型上下文并落入对话历史，仅在用户明确要求时调用，不要主动索取密钥。")
    public Mono<String> jwtDecode(
        @ToolParam(name = "token", description = "JWT字符串") String token,
        @ToolParam(name = "secret", required = false, description = "可选，HS*签名校验密钥") String secret,
        @ToolParam(name = "secretEncoding", required = false, description = "密钥编码 utf8(默认)/hex/base64") String secretEncoding) {
        return respond("jwt_decode", () -> jwtOps.decode(token, secret, secretEncoding));
    }

    @Tool(name = "text_diff", description = "按行比对两段文本的差异(最长公共子序列算法)。"
        + "参数 oldText：原文本；newText：新文本；ignoreWhitespace：是否忽略行首尾空白，默认false；"
        + "ignoreCase：是否忽略大小写，默认false。"
        + "返回 identical(是否完全一致)、addedLines/deletedLines(增删行数)、totalLines、truncated(是否因差异过多截断)、"
        + "lines(每行的type=EQUAL/INSERT/DELETE、oldLineNo、newLineNo、content，行号从1起、该侧不存在时为-1)。"
        + "适合核对改配置前后、两个环境的JSON/YAML差异。单侧行数上限1500行。")
    public Mono<String> textDiff(
        @ToolParam(name = "oldText", description = "原文本") String oldText,
        @ToolParam(name = "newText", description = "新文本") String newText,
        @ToolParam(name = "ignoreWhitespace", required = false, description = "忽略行首尾空白，默认false") Boolean ignoreWhitespace,
        @ToolParam(name = "ignoreCase", required = false, description = "忽略大小写，默认false") Boolean ignoreCase) {
        return respond("text_diff", () -> diffOps.diff(oldText, newText, ignoreWhitespace, ignoreCase));
    }

    @Tool(name = "data_convert", description = "结构化数据格式互转：JSON、YAML、XML 三者任意方向转换。"
        + "参数 content：待转换内容；sourceFormat/targetFormat：json/yaml/xml；"
        + "rootName：转XML时的根元素名，默认root，其它目标格式忽略。返回 sourceFormat、targetFormat、result。"
        + "已知语义损耗：XML无类型系统故转出的JSON里数字与布尔均为字符串；XML同名重复子元素只保留最后一个"
        + "(数组型XML请勿依赖本工具)；YAML多文档只处理第一个；数组不能直接作为XML根。")
    public Mono<String> dataConvert(
        @ToolParam(name = "content", description = "待转换内容") String content,
        @ToolParam(name = "sourceFormat", description = "源格式 json/yaml/xml") String sourceFormat,
        @ToolParam(name = "targetFormat", description = "目标格式 json/yaml/xml") String targetFormat,
        @ToolParam(name = "rootName", required = false, description = "转XML时的根元素名，默认root") String rootName) {
        return respond("data_convert", () -> dataFormatOps.convert(content, sourceFormat, targetFormat, rootName));
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

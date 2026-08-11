package com.richard.fyoung.customerwork.data.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 纯文本解析器：所有"本身即文本"的格式直接按 UTF-8 读取，无需富文档解析，直读最快最稳。
 *
 * <p>内置清单 {@link #TEXT_EXTENSIONS} 覆盖常见文档/数据/配置/日志/主流编程语言源码；
 * 清单之外的小众文本类型不用改代码，配置 {@code customer-work.attachment.extra-text-extensions}
 * 追加即可（经构造参数传入）。html 不在此列——由 Tika 解析剥掉标签更干净。</p>
 * @author owlzhangfq@gmail.com
 */
public class TextAttachmentParser implements AttachmentParser {

    /** 内置支持的纯文本扩展名（文档/数据/配置/日志/脚本/主流语言源码）。 */
    public static final Set<String> TEXT_EXTENSIONS = Set.of(
        // 文档与数据
        "md", "txt", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "proto",
        // 配置与日志
        "properties", "ini", "conf", "cfg", "log", "env",
        // 数据库与脚本
        "sql", "sh", "bash", "zsh", "bat", "ps1",
        // 主流编程语言源码与前端
        "java", "kt", "kts", "groovy", "gradle", "scala",
        "py", "js", "ts", "jsx", "tsx", "vue", "css", "scss", "less",
        "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "swift", "lua", "r", "dart");

    /** 配置追加的扩展名（已统一小写）。 */
    private final Set<String> extraExtensions;

    public TextAttachmentParser() {
        this(Collections.emptyList());
    }

    /** @param extraTextExtensions 配置追加的文本扩展名（大小写不敏感） */
    public TextAttachmentParser(List<String> extraTextExtensions) {
        this.extraExtensions = extraTextExtensions == null ? Set.of()
            : extraTextExtensions.stream()
                .map(e -> e.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean supports(String ext, String mime) {
        return TEXT_EXTENSIONS.contains(ext) || extraExtensions.contains(ext);
    }

    @Override
    public ParsedContent parse(byte[] data, String fileName, String ext, String mime) {
        return ParsedContent.of(new String(data, StandardCharsets.UTF_8));
    }
}

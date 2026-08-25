package com.richard.fyoung.customerwork.tool.mcp;

import com.richard.fyoung.customerwork.safety.security.HttpTargetForbiddenException;
import com.richard.fyoung.customerwork.safety.security.HttpTargetGuard;
import com.richard.fyoung.customerwork.safety.security.HttpTargetPolicy;
import com.richard.fyoung.customerwork.safety.security.InternalAddressPolicy;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * MCP 唯一执行安全策略：远程传输收口 SSRF，stdio 收口可执行文件、工作目录与环境变量。
 *
 * <p>默认策略只允许公网远程 MCP，并关闭 stdio。企业私网或本机 MCP 必须显式配置 host，命中后仍拒绝
 * 链路本地、云元数据、未指定和组播地址；stdio 必须同时配置可执行文件与工作目录白名单，且最终进程由
 * {@link McpStdioProcessLauncher} 清空继承环境、固定工作目录后再启动。任何调用方都只通过
 * {@link McpClientFactory} 建客户端，避免保存校验与真实执行使用两套规则。</p>
 */
public final class McpSecurityPolicy {

    private static final int MAX_ARGUMENTS = 128;
    private static final int MAX_ARGUMENT_LENGTH = 4096;

    private final Supplier<List<String>> allowedHostsSupplier;
    private final Supplier<List<String>> allowedCommandsSupplier;
    private final Supplier<List<String>> allowedWorkingDirectoriesSupplier;
    private final Supplier<List<String>> allowedEnvironmentKeysSupplier;
    private final HttpTargetGuard targetGuard;

    public McpSecurityPolicy(Supplier<List<String>> allowedHostsSupplier,
                             Supplier<List<String>> allowedCommandsSupplier,
                             Supplier<List<String>> allowedWorkingDirectoriesSupplier,
                             Supplier<List<String>> allowedEnvironmentKeysSupplier) {
        this(allowedHostsSupplier, allowedCommandsSupplier, allowedWorkingDirectoriesSupplier,
            allowedEnvironmentKeysSupplier, java.net.InetAddress::getAllByName);
    }

    /** 可替换 DNS 解析器仅用于确定性测试。 */
    public McpSecurityPolicy(Supplier<List<String>> allowedHostsSupplier,
                             Supplier<List<String>> allowedCommandsSupplier,
                             Supplier<List<String>> allowedWorkingDirectoriesSupplier,
                             Supplier<List<String>> allowedEnvironmentKeysSupplier,
                             HttpTargetGuard.AddressResolver addressResolver) {
        this.allowedHostsSupplier = allowedHostsSupplier;
        this.allowedCommandsSupplier = allowedCommandsSupplier;
        this.allowedWorkingDirectoriesSupplier = allowedWorkingDirectoriesSupplier;
        this.allowedEnvironmentKeysSupplier = allowedEnvironmentKeysSupplier;
        this.targetGuard = new HttpTargetGuard(this::currentRemotePolicy, addressResolver);
    }

    public static McpSecurityPolicy strict() {
        return new McpSecurityPolicy(List::of, List::of, List::of, List::of);
    }

    /** 保存期与建连前共同调用；返回规范化且不携带凭据元数据的 URL。 */
    public String validateRemoteUrl(String rawUrl) {
        HttpTargetGuard.ValidatedTarget target = targetGuard.validate(rawUrl);
        URI uri = target.uri().normalize();
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new HttpTargetForbiddenException("远程 MCP URL 不允许包含 query 或 fragment");
        }
        return uri.toString();
    }

    /** MCP SDK 每次真实 HTTP 请求前复验目标，重定向或存量脏配置不能绕过策略。 */
    public void validateRequestTarget(URI uri) {
        validateRemoteUrl(uri == null ? null : uri.toString());
    }

    /**
     * 校验 stdio 执行边界并返回规范化规格。命令和工作目录都按真实路径比较，阻止符号链接逃逸。
     */
    public McpServerSpec validateStdio(McpServerSpec spec) {
        List<Path> allowedCommands = realPaths(current(allowedCommandsSupplier), true);
        List<Path> allowedRoots = realPaths(current(allowedWorkingDirectoriesSupplier), false);
        if (allowedCommands.isEmpty() || allowedRoots.isEmpty()) {
            throw new IllegalArgumentException("stdio MCP 未配置执行白名单，当前已关闭");
        }

        Path command = requireRealPath(spec.command(), true, "stdio MCP command");
        if (!allowedCommands.contains(command)) {
            throw new IllegalArgumentException("stdio MCP command 不在白名单内: " + command);
        }
        Path workingDirectory = requireRealPath(spec.workingDirectory(), false, "stdio MCP workingDirectory");
        if (allowedRoots.stream().noneMatch(workingDirectory::startsWith)) {
            throw new IllegalArgumentException("stdio MCP 工作目录不在白名单内: " + workingDirectory);
        }

        List<String> args = spec.args() == null ? List.of() : List.copyOf(spec.args());
        if (args.size() > MAX_ARGUMENTS || args.stream().anyMatch(arg -> arg == null || arg.length() > MAX_ARGUMENT_LENGTH)) {
            throw new IllegalArgumentException("stdio MCP args 超出安全限制");
        }
        Set<String> allowedEnvironmentKeys = current(allowedEnvironmentKeysSupplier).stream()
            .filter(StringUtils::hasText).map(String::trim).collect(Collectors.toUnmodifiableSet());
        Map<String, String> environment = spec.environment() == null ? Map.of() : spec.environment();
        if (!allowedEnvironmentKeys.containsAll(environment.keySet())) {
            throw new IllegalArgumentException("stdio MCP env 包含未授权变量");
        }
        Map<String, String> safeEnvironment = new LinkedHashMap<>();
        environment.forEach((key, value) -> {
            if (!StringUtils.hasText(key) || key.indexOf('=') >= 0 || value == null) {
                throw new IllegalArgumentException("stdio MCP env 格式非法");
            }
            safeEnvironment.put(key, value);
        });
        return McpServerSpec.stdio(spec.name(), command.toString(), args,
            workingDirectory.toString(), Map.copyOf(safeEnvironment));
    }

    private HttpTargetPolicy currentRemotePolicy() {
        return HttpTargetPolicy.ofResolvedAllowlist(current(allowedHostsSupplier),
            InternalAddressPolicy.DENY_INTERNAL, InternalAddressPolicy.ALLOW_INTERNAL);
    }

    private List<String> current(Supplier<List<String>> supplier) {
        List<String> values = supplier == null ? null : supplier.get();
        return CollectionUtils.isEmpty(values) ? List.of() : values;
    }

    private List<Path> realPaths(List<String> values, boolean executable) {
        return values.stream().filter(StringUtils::hasText)
            .map(value -> requireRealPath(value, executable, "MCP 安全白名单路径"))
            .collect(Collectors.toUnmodifiableList());
    }

    private Path requireRealPath(String value, boolean executable, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        try {
            Path path = Path.of(value.trim());
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException(field + " 必须使用绝对路径");
            }
            Path real = path.toRealPath();
            if (executable && (!Files.isRegularFile(real) || !Files.isExecutable(real))) {
                throw new IllegalArgumentException(field + " 必须是可执行文件");
            }
            if (!executable && !Files.isDirectory(real)) {
                throw new IllegalArgumentException(field + " 必须是目录");
            }
            return real;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " 不存在或不可访问", e);
        }
    }
}

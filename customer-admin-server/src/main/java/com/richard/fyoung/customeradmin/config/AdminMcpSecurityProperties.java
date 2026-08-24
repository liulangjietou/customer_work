package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Admin MCP 出网与 stdio 进程执行边界。 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.mcp.security")
public class AdminMcpSecurityProperties {

    /** 企业私网 MCP host 白名单；为空时只允许公网。 */
    private List<String> allowedHosts = new ArrayList<>();
    /** stdio 原始可执行文件真实路径白名单；为空即关闭 stdio。 */
    private List<String> allowedCommands = new ArrayList<>();
    /** stdio 工作目录真实路径根白名单；为空即关闭 stdio。 */
    private List<String> allowedWorkingDirectories = new ArrayList<>();
    /** 允许注入 stdio 子进程的环境变量名；其它宿主环境不会继承。 */
    private List<String> allowedEnvironmentKeys = new ArrayList<>();
}

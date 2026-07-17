package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.RoleStageEvent;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 多 Agent 协作编程（P3-1 降级版）配置：{@code admin.collaboration.*}。
 *
 * <p>降级说明：完整形态依赖 starter 侧 SubAgent/Pipeline 编排（产品/架构/开发/测试/Review 五角色可
 * 暂停恢复、可中途介入），当前未就绪。本降级版复用项目自研的顺序编排思路（参照 starter
 * {@code MultiAgentOrchestrator#sequential}：各角色输出作为下一角色输入逐步细化），在 admin-server
 * 工作区把一次需求输入串成"需求分析 → 方案设计 → 编码实现 → 自测审查"顺序流水。角色数量与提示词
 * 可配（默认 4 个），编码角色产出走既有 VibeCoding 沙箱/file_change/test_report 链路，不另起写入通道。</p>
 *
 * <p>本功能默认<b>不影响既有链路</b>：仅当 {@code /vibecoding/stream} 请求携带 {@code collaboration=true}
 * （前端"协作模式"开关，默认关）时才启用；开关关闭时行为与改造前完全一致。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.collaboration")
public class AdminCollaborationProperties {

    /** 非编码角色（分析/设计/审查）单次一次性模型调用的超时（秒）。 */
    private int roleTimeoutSeconds = 60;

    /** 角色流水定义；为空时回落到 {@link #defaultRoles()}（默认 4 角色）。 */
    private List<Role> roles = new ArrayList<>();

    /** 取生效的角色流水：配置为空则用内置默认 4 角色，保证开箱可用又不阻断自定义。 */
    public List<Role> effectiveRoles() {
        return CollectionUtils.isEmpty(roles) ? defaultRoles() : roles;
    }

    /**
     * 内置默认角色流水（3~4 个，避免过度设计）：需求分析（文本）→ 方案设计（文本）→ 编码实现（沙箱）→
     * 自测审查（对 diff 审查）。提示词均为该角色的系统指令，运行时会拼接上游角色的产出与用户原始需求。
     */
    public static List<Role> defaultRoles() {
        List<Role> defaults = new ArrayList<>();
        defaults.add(new Role("需求分析师", RoleStageEvent.TYPE_PLAN,
            "你是资深需求分析师。请把用户的原始需求拆解为清晰、可落地的需求说明：核心目标、关键功能点、"
                + "输入输出、边界与异常场景、验收要点。只输出结构化的需求说明文本，不写代码。"));
        defaults.add(new Role("架构师", RoleStageEvent.TYPE_PLAN,
            "你是资深 Java 架构师。基于上游的需求说明，给出简洁可执行的技术方案：接口/类设计、数据模型、"
                + "关键流程、涉及的文件与改动点、必要的测试策略。只输出设计方案文本，暂不写完整代码。"));
        defaults.add(new Role("开发工程师", RoleStageEvent.TYPE_CODING,
            "你是资深 Java 开发工程师。请严格按上游的需求说明与技术方案实现代码：用 write_file 工具把代码与"
                + "对应的 JUnit5/Mockito 单元测试写入会话目录，并在沙箱内编译/测试验证，失败自动修复（最多 3 轮）。"));
        defaults.add(new Role("测试与审查工程师", RoleStageEvent.TYPE_REVIEW,
            "你是资深代码审查专家。请基于本轮生成代码的 git diff 做审查，覆盖安全/健壮性/日志规范/命名可读性/"
                + "性能，逐条给出问题与改进建议，并总体评估是否满足需求。"));
        return defaults;
    }

    /**
     * 单个协作角色定义。
     * @param name   角色展示名
     * @param type   角色类型：PLAN（文本）｜CODING（沙箱编码）｜REVIEW（diff 审查），取值见 {@link RoleStageEvent}
     * @param prompt 角色系统提示词（运行时拼接上游产出与原始需求）
     */
    @Data
    public static class Role {
        private String name;
        private String type;
        private String prompt;

        public Role() {
        }

        public Role(String name, String type, String prompt) {
            this.name = name;
            this.type = type;
            this.prompt = prompt;
        }
    }
}

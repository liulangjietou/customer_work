package com.richard.fyoung.customeradmin.aiconfig.skill.storage;

import java.util.Arrays;
import java.util.Optional;

/**
 * Skill 内容存储目标：SKILL.md 正文除入库外可发布到的外部目标。
 *
 * <p>{@link #LOCAL} 本地 workspace（默认且始终可用）、{@link #NACOS} 配置中心、{@link #SFTP} 远端目录。
 * 每个 skill 独立多选，落库为逗号分隔的 {@link #getCode() code}。</p>
 * @author owlzhangfq@gmail.com
 */
public enum SkillStorageTarget {

    LOCAL("local"),
    NACOS("nacos"),
    SFTP("sftp");

    private final String code;

    SkillStorageTarget(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 按 code 解析（大小写不敏感）；非法值返回空，由调用方决定是否 fast fail。 */
    public static Optional<SkillStorageTarget> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String trimmed = code.trim();
        return Arrays.stream(values())
            .filter(t -> t.code.equalsIgnoreCase(trimmed))
            .findFirst();
    }
}

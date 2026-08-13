package com.richard.fyoung.customerwork.data.skill.storage;

import java.util.Arrays;
import java.util.Optional;

/**
 * Skill 内容存储目标：SKILL.md 正文除入库外可发布到的外部目标。
 *
 * <p>{@link #MINIO} 对象存储（默认且始终可用）、{@link #NACOS} 配置中心、{@link #SFTP} 远端目录。
 * 每个 skill 独立多选，落库为逗号分隔的 {@link #getCode() code}。</p>
 * @author owlzhangfq@gmail.com
 */
public enum SkillStorageTarget {

    MINIO("minio"),
    NACOS("nacos"),
    SFTP("sftp");

    /** 旧的本地 workspace 目标 code：已下线，存量数据按 {@link #MINIO} 解析。 */
    private static final String LEGACY_LOCAL_CODE = "local";

    private final String code;

    SkillStorageTarget(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 按 code 解析（大小写不敏感）；非法值返回空，由调用方决定是否 fast fail。
     *
     * <p>存量 {@code ai_skill.storage_targets} 里写的是已下线的 {@code local}（本地 workspace 目标），
     * 这里把它映射到 {@link #MINIO}——否则老数据一读就 fast fail，编辑不了也删不掉。</p>
     */
    public static Optional<SkillStorageTarget> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String trimmed = code.trim();
        if (LEGACY_LOCAL_CODE.equalsIgnoreCase(trimmed)) {
            return Optional.of(MINIO);
        }
        return Arrays.stream(values())
            .filter(t -> t.code.equalsIgnoreCase(trimmed))
            .findFirst();
    }
}

package com.richard.fyoung.customerwork.data.skill.storage;

import java.util.List;

/**
 * Skill 内容发布 SPI：把 SKILL.md 正文发布到某个存储目标，供 Skill 保存/删除时调用。
 *
 * <p>每个目标一个实现（{@link #target()} 标识），由宿主模块按配置条件装配（starter 只提供实现与
 * {@link SkillContentPublishers} 静态工厂，不注册 Bean）；调用方注入全部实现并按 skill 勾选的目标分发。
 * 参考 starter 的 Store SPI 范式（接口 + 条件装配），但这里是"写出"而非"读回"，运行时消费仍从数据库读，
 * 不经这些目标。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SkillContentPublisher {

    /** 本实现负责的存储目标。 */
    SkillStorageTarget target();

    /**
     * 发布（新建/覆盖）指定 skill 的 SKILL.md 正文；失败抛异常，由上层转业务异常并回滚事务。
     * @param skillCode 技能编码（用作目录/dataId 维度）
     * @param content   SKILL.md 正文
     */
    void publish(String skillCode, String content);

    /**
     * 发布该 skill 的附属文件（zip 里除 SKILL.md 外的 references/scripts 等）；失败抛异常，
     * 由上层转业务异常并回滚事务。默认空实现——不适合承载任意文件的目标（如 Nacos 配置中心
     * 面向文本配置）保持只发 SKILL.md 的行为即可。
     * @param skillCode 技能编码
     * @param files     附属文件列表（可能为空）
     */
    default void publishFiles(String skillCode, List<SkillFileContent> files) {
    }

    /**
     * 移除指定 skill 在本目标上的产物（删除 / 取消勾选时调用）。尽力而为，失败由调用方按场景处理。
     * @param skillCode 技能编码
     */
    void remove(String skillCode);
}

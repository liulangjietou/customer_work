package com.richard.fyoung.customeradmin.aiconfig.skill.storage;

/**
 * Skill 内容发布 SPI：把 SKILL.md 正文发布到某个存储目标，供 Skill 保存/删除时调用。
 *
 * <p>每个目标一个实现（{@link #target()} 标识），由 {@code SkillStorageConfig} 按配置条件注册；
 * {@code SkillService} 注入全部实现并按 skill 勾选的目标分发。参考 starter 的 Store SPI 范式
 * （接口 + 条件装配），但这里是"写出"而非"读回"，运行时消费仍从数据库读，不经这些目标。</p>
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
     * 移除指定 skill 在本目标上的产物（删除 / 取消勾选时调用）。尽力而为，失败由调用方按场景处理。
     * @param skillCode 技能编码
     */
    void remove(String skillCode);
}

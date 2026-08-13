package com.richard.fyoung.customerwork.capability.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 提示词版本持久化对象（贫血数据袋）：与 {@code cw_prompt_version} 表一一映射。
 *
 * <p>领域快照见 {@link com.richard.fyoung.customerwork.capability.prompt.PromptVersion}。
 * 主键即内容指纹——同一版内容天然只该有一行。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_prompt_version")
public class PromptVersionDO {

    /** 内容指纹（SHA-256 前 16 位），应用赋值。 */
    @TableId(value = "fingerprint", type = IdType.INPUT)
    private String fingerprint;

    private String content;
    private Integer length;

    /** 首次观测到该版本的时间戳（毫秒）——即"这版什么时候上线的"。 */
    private Long capturedAtMs;
}

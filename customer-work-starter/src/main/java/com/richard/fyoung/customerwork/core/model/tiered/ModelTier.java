package com.richard.fyoung.customerwork.core.model.tiered;

/**
 * 模型档位。
 *
 * <p>只分两档而不是三四档：档位越多，"这个请求该走哪档"的判断就越容易错，
 * 而判错的代价（答得差）远大于省下的那点钱。两档已经能吃掉大部分成本红利。</p>
 * @author owlzhangfq@gmail.com
 */
public enum ModelTier {

    /** 经济档：便宜、快，用于简短的单轮问答。 */
    ECONOMY,

    /** 标准档：主模型，能力强。<b>默认档</b>——判不准时一律走这里。 */
    STANDARD
}

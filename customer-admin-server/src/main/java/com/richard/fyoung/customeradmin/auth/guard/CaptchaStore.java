package com.richard.fyoung.customeradmin.auth.guard;

/**
 * 图形验证码答案的短期存储 SPI。
 *
 * <p>与项目内其它 Store 一样走"接口 + 进程内默认 + 分布式实现 + 按可用性选择"的模式。
 * 验证码必须<b>一次性消费</b>：{@link #consume} 取出后立即失效，否则同一张图可以
 * 反复提交，验证码就退化成一次性的形式检查。</p>
 * @author owlzhangfq@gmail.com
 */
public interface CaptchaStore {

    /** 写入一条验证码答案，超过 {@code ttlSeconds} 自动失效。 */
    void save(String captchaId, String answer, int ttlSeconds);

    /**
     * 取出并立即删除答案。
     *
     * @return 答案；不存在或已过期返回 {@code null}
     */
    String consume(String captchaId);
}

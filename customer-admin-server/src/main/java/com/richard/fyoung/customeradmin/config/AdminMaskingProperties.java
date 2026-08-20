package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.infra.config.properties.HooksProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 后台链路的出站脱敏参数：{@code admin.masking.*}。
 *
 * <p><b>为什么需要这个类</b>：脱敏中间件是 starter 的 {@code @Component}，而本模块用
 * {@code spring.autoconfigure.exclude} 关掉了 starter 的自动装配——于是后台对话链路长期
 * 既没有脱敏中间件，也没有一个可以打开它的配置项。运维在客服端验证过脱敏生效后，
 * 会理所当然地以为全局都保护上了。</p>
 *
 * <p>直接继承 starter 的配置段而不是重新声明一遍字段：脱敏规则（手机号/身份证/银行卡/邮箱
 * 各自的开关与替换串）两边必须是同一套语义，重写一份就多了一处会漂移的定义。</p>
 *
 * <p>默认关闭，与客服端 {@code customer-work.hooks.masking.enabled} 的缺省一致——
 * 脱敏会改写发给用户的正文，是否开启取决于业务对可读性与合规的权衡，不适合替使用方决定。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "admin.masking")
public class AdminMaskingProperties extends HooksProperties.Masking {
}

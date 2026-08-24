package com.richard.fyoung.customerchannel.access.wechat;

import java.time.Duration;

/**
 * 微信回调回放保护：nonce 防请求重放，messageKey 防平台换 nonce 重试时重复执行业务。
 *
 * <p>实现必须保证同一个 key 的占位是原子的；返回 {@code false} 表示有效期内已存在。</p>
 * @author owlzhangfq@gmail.com
 */
public interface WeChatReplayGuard {

    boolean claimNonce(String appId, String nonce, Duration ttl);

    boolean claimMessage(String appId, String messageKey, Duration ttl);
}

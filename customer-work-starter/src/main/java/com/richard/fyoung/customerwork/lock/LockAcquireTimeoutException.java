package com.richard.fyoung.customerwork.lock;

/**
 * 在指定等待时间内未能获取到分布式锁：调用方据此判断"资源正被并发占用"，
 * 转换为业务侧"请稍后重试"提示，而不是当作系统故障处理。
 * @author owlzhangfq@gmail.com
 */
public class LockAcquireTimeoutException extends RuntimeException {

    public LockAcquireTimeoutException(String lockKey) {
        super("acquire distributed lock timeout, lockKey=" + lockKey);
    }
}

package com.richard.fyoung.customeradmin.menu.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 菜单版本号：进程内 {@link AtomicLong}，智能体增删改/启停后自增，配合前端轮询
 * {@code GET /api/menu/version} 感知菜单变化（需求文档"菜单刷新≤1s"，当前操作用户前端主动刷新即时生效，
 * 本机制兜底"其它在线用户下次轮询感知"）。
 *
 * <p>预留：若未来多实例部署，换 Redis 实现（如 {@code INCR}），接口签名不变。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class MenuVersionHolder {

    private final AtomicLong version = new AtomicLong(0);

    public long bump() {
        return version.incrementAndGet();
    }

    public long current() {
        return version.get();
    }
}

package com.richard.fyoung.customerwork.core.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多租户长期记忆单测（对应深度解析 3.4）：记忆沉淀、相关召回、以及最关键的租户隔离。
 * @author owlzhangfq@gmail.com
 */
class LongTermMemoryTest {

    private LongTermMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryLongTermMemoryStore();
    }

    private Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(text).build()).build();
    }

    private InMemoryLongTermMemory memoryFor(String tenant) {
        // 事实日志关闭，单测只聚焦语义召回与租户隔离（FactLog 另有专测）
        FactLog factLog = new FileFactLog(false, Path.of("target/test-facts"));
        return new InMemoryLongTermMemory(store, factLog, tenant, 5);
    }

    @Test
    void recordThenRetrieve_shouldRecallRelevantFact() {
        InMemoryLongTermMemory ltm = memoryFor("tenantA");
        ltm.record(List.of(userMsg("我的常用收货地址是杭州西湖区"))).block();

        StepVerifier.create(ltm.retrieve(userMsg("收货地址是哪里")))
            .assertNext(recalled -> assertTrue(recalled.contains("杭州西湖区"),
                "应召回相关历史记忆，实际: " + recalled))
            .verifyComplete();
    }

    @Test
    void record_shouldOnlyKeepUserMessages() {
        InMemoryLongTermMemory ltm = memoryFor("tenantA");
        Msg assistant = Msg.builder().role(MsgRole.ASSISTANT).name("assistant")
            .content(TextBlock.builder().text("这是助手的回复，不应入库").build()).build();

        ltm.record(List.of(userMsg("用户事实"), assistant)).block();

        assertEquals(1, store.size("tenantA"), "只应沉淀用户消息，助手消息不入库");
    }

    @Test
    void retrieve_shouldIsolateBetweenTenants() {
        memoryFor("tenantA").record(List.of(userMsg("租户A的机密订单信息"))).block();

        // 租户B 检索同样的查询，绝不能看到租户A 的记忆
        StepVerifier.create(memoryFor("tenantB").retrieve(userMsg("机密订单信息")))
            .assertNext(recalled -> assertTrue(recalled.isEmpty(),
                "租户隔离失败：B 看到了 A 的记忆: " + recalled))
            .verifyComplete();
    }

    @Test
    void retrieve_shouldReturnEmpty_whenNoRelevantMemory() {
        StepVerifier.create(memoryFor("tenantA").retrieve(userMsg("任意查询")))
            .assertNext(recalled -> assertTrue(recalled.isEmpty()))
            .verifyComplete();
    }

    @Test
    void store_shouldDeduplicateAndClear() {
        store.add("t", "重复事实");
        store.add("t", "重复事实");
        assertEquals(1, store.size("t"), "完全相同的事实应去重");

        store.clear("t");
        assertEquals(0, store.size("t"));
    }
}

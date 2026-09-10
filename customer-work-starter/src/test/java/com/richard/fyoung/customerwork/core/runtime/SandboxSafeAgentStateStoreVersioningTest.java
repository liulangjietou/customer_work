package com.richard.fyoung.customerwork.core.runtime;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.VersionedState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装饰器必须转发乐观并发三件套（AgentScope 2.0.3）。
 *
 * <h3>守的是什么</h3>
 * <p>2.0.3 给 {@link AgentStateStore} 加了 {@code supportsVersioning} / {@code getVersioned} /
 * {@code saveIfVersion}，三者<b>都带 default 实现</b>：默认报"不支持版本化"，并把
 * {@code saveIfVersion} 降级成普通 {@code save}。于是 {@link SandboxSafeAgentStateStore}
 * 这类装饰器<b>不转发也能编译通过</b>，只是把被装饰 store 的版本化能力静默吃掉——
 * 底层 MySQL store 明明 {@code supportsVersioning()=true}，套上壳之后框架就退回无版本写入。
 * 升级当天全量测试全绿，没有任何信号。</p>
 *
 * <p>本项目对"能力在中途被吞掉且不报错"这个形状已经栽过多次（脱敏只接一条链路、
 * 语义缓存只接非流式）。装饰器是它最隐蔽的一种载体：接口每加一个 default 方法，
 * 所有装饰器就多欠一处转发。</p>
 *
 * <p>断言方式是<b>真实行为</b>而不是"方法被调用过"：拿真的 {@link InMemoryAgentStateStore}
 * 当被装饰对象，验证 CAS 语义（版本对得上才写、对不上返回 {@code UNVERSIONED} 且不覆盖）
 * 穿过装饰器之后依然成立。转发漏一个方法，这里立刻红。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class SandboxSafeAgentStateStoreVersioningTest {

    /** 装饰器存在的理由本身：Harness 会拼出带斜杠的 sessionId，而 MySQL store 拒绝它。 */
    private static final String SANDBOX_SESSION_ID = "sandbox/agent/conv-1";

    private static final String USER = "u-1";
    private static final String STATE_KEY = "memory";

    @Test
    @DisplayName("supportsVersioning 必须取被装饰 store 的判定，而不是接口默认的 false")
    void supportsVersioningIsDelegated() {
        AgentStateStore delegate = new InMemoryAgentStateStore();
        assertTrue(delegate.supportsVersioning(), "前提失效：被装饰的 store 本身应支持版本化");

        AgentStateStore decorated = new SandboxSafeAgentStateStore(delegate);

        assertTrue(decorated.supportsVersioning(),
            "装饰器报了不支持版本化——它没有转发 supportsVersioning()，"
                + "框架会因此对这条链路退回无版本写入，且不报任何错");
    }

    @Test
    @DisplayName("getVersioned 穿过装饰器后仍能取到版本号，且沙箱 sessionId 被一致地转义")
    void getVersionedIsDelegatedWithSanitizedSessionId() {
        AgentStateStore delegate = new InMemoryAgentStateStore();
        AgentStateStore decorated = new SandboxSafeAgentStateStore(delegate);

        VersionedState<AgentState> empty =
            decorated.getVersioned(USER, SANDBOX_SESSION_ID, STATE_KEY, AgentState.class);
        assertFalse(empty.isPresent(), "空槽位不应有值");
        assertEquals(0L, empty.version(), "空槽位的版本号应是 0（CAS 的起始期望值）");

        long written = decorated.saveIfVersion(
            USER, SANDBOX_SESSION_ID, STATE_KEY, AgentState.builder().build(), empty.version());
        assertEquals(1L, written, "首次 CAS 写入应返回新版本 1");

        VersionedState<AgentState> loaded =
            decorated.getVersioned(USER, SANDBOX_SESSION_ID, STATE_KEY, AgentState.class);
        assertTrue(loaded.isPresent(), "写进去的状态应当读得回来");
        assertEquals(1L, loaded.version(), "读回的版本号应与写入返回值一致");
    }

    @Test
    @DisplayName("版本不匹配时不得写入——CAS 语义必须穿过装饰器")
    void staleWriteIsRejectedThroughDecorator() {
        AgentStateStore delegate = new InMemoryAgentStateStore();
        AgentStateStore decorated = new SandboxSafeAgentStateStore(delegate);
        decorated.saveIfVersion(USER, SANDBOX_SESSION_ID, STATE_KEY, AgentState.builder().build(), 0L);

        long rejected = decorated.saveIfVersion(
            USER, SANDBOX_SESSION_ID, STATE_KEY, AgentState.builder().build(), 999L);

        assertEquals(AgentStateStore.UNVERSIONED, rejected,
            "过期版本的写入应返回 UNVERSIONED；返回了别的值说明 saveIfVersion 走的是接口默认实现"
                + "（那个实现无条件 save 后返回 -1，看似一样，但它已经把数据覆盖掉了）");

        VersionedState<AgentState> after =
            decorated.getVersioned(USER, SANDBOX_SESSION_ID, STATE_KEY, AgentState.class);
        assertEquals(1L, after.version(),
            "被拒绝的写入不得改变版本号——版本被推进说明数据实际上已被覆盖，CAS 形同虚设");
    }
}

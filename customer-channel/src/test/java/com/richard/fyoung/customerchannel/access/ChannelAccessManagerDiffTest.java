package com.richard.fyoung.customerchannel.access;

import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnector;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnectorFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelAccessManager} diff 逻辑测试：新增→start、version 变化→restart、消失→stop、
 * 同版本→noop、admin 不可达→保留不停、单台启动失败→下轮重试。
 *
 * <p>直接驱动包级 {@code refresh()}，不启用调度线程；用假 connector 计数 start/stop。</p>
 * @author owlzhangfq@gmail.com
 */
class ChannelAccessManagerDiffTest {

    private static final String TYPE = ChannelAccessConstants.CHANNEL_TYPE_DINGTALK;

    private ChannelRobot robot(long id, long version) {
        return new ChannelRobot(id, TYPE, "bot" + id, "ak" + id, "as" + id, "rc" + id, "agent" + id, version);
    }

    @Test
    void shouldStartOnNew_andNoopOnSameVersion() {
        AdminOpenApiClient client = mock(AdminOpenApiClient.class);
        FakeFactory factory = new FakeFactory(TYPE, false);
        when(client.listRobots(TYPE))
            .thenReturn(List.of(robot(1, 100L)))
            .thenReturn(List.of(robot(1, 100L)));
        ChannelAccessManager manager = new ChannelAccessManager(List.of(factory), client, props());

        manager.refresh();
        assertEquals(1, factory.created.size(), "首轮应创建一个连接器");
        assertEquals(1, factory.created.get(0).starts, "首轮应 start 一次");

        manager.refresh();
        assertEquals(1, factory.created.size(), "同版本第二轮不应新建连接器");
        assertEquals(0, factory.created.get(0).stops, "同版本不应 stop");
    }

    @Test
    void shouldRestartOnVersionChange() {
        AdminOpenApiClient client = mock(AdminOpenApiClient.class);
        FakeFactory factory = new FakeFactory(TYPE, false);
        when(client.listRobots(TYPE))
            .thenReturn(List.of(robot(1, 100L)))
            .thenReturn(List.of(robot(1, 200L)));
        ChannelAccessManager manager = new ChannelAccessManager(List.of(factory), client, props());

        manager.refresh();
        manager.refresh();

        assertEquals(2, factory.created.size(), "version 变化应新建连接器");
        assertEquals(1, factory.created.get(0).stops, "旧连接器应被 stop");
        assertEquals(1, factory.created.get(1).starts, "新连接器应 start");
    }

    @Test
    void shouldStopWhenRobotDisappears() {
        AdminOpenApiClient client = mock(AdminOpenApiClient.class);
        FakeFactory factory = new FakeFactory(TYPE, false);
        when(client.listRobots(TYPE))
            .thenReturn(List.of(robot(1, 100L)))
            .thenReturn(Collections.emptyList());
        ChannelAccessManager manager = new ChannelAccessManager(List.of(factory), client, props());

        manager.refresh();
        manager.refresh();

        assertEquals(1, factory.created.get(0).stops, "机器人消失应被 stop");
        assertFalse(factory.created.get(0).isRunning());
    }

    @Test
    void shouldKeepConnectionsWhenAdminUnreachable() {
        AdminOpenApiClient client = mock(AdminOpenApiClient.class);
        FakeFactory factory = new FakeFactory(TYPE, false);
        when(client.listRobots(TYPE))
            .thenReturn(List.of(robot(1, 100L)))
            .thenThrow(new RuntimeException("admin down"));
        ChannelAccessManager manager = new ChannelAccessManager(List.of(factory), client, props());

        manager.refresh();  // 起连接
        manager.refresh();  // admin 抛错

        assertEquals(0, factory.created.get(0).stops, "admin 不可达时不应停掉现有连接");
        assertTrue(factory.created.get(0).isRunning());
    }

    @Test
    void shouldRetryNextRoundWhenStartFails() {
        AdminOpenApiClient client = mock(AdminOpenApiClient.class);
        // 第一次 start 抛错，后续成功
        FakeFactory factory = new FakeFactory(TYPE, true);
        when(client.listRobots(TYPE))
            .thenReturn(List.of(robot(1, 100L)))
            .thenReturn(List.of(robot(1, 100L)));
        ChannelAccessManager manager = new ChannelAccessManager(List.of(factory), client, props());

        manager.refresh();  // 首轮启动失败，不入 running
        assertEquals(1, factory.created.size());

        manager.refresh();  // 下轮重试（因未入 running，视为新增）
        assertEquals(2, factory.created.size(), "启动失败的机器人下轮应重试");
        assertTrue(factory.created.get(1).isRunning());
    }

    private ChannelAccessProperties props() {
        return new ChannelAccessProperties();
    }

    // ===== 假实现 =====

    private static final class FakeConnector implements ImChannelConnector {
        private final String type;
        private final boolean failFirstStart;
        private int starts;
        private int stops;
        private boolean running;

        FakeConnector(String type, boolean failFirstStart) {
            this.type = type;
            this.failFirstStart = failFirstStart;
        }

        @Override
        public String channelType() {
            return type;
        }

        @Override
        public void start() {
            starts++;
            if (failFirstStart) {
                throw new IllegalStateException("boom on start");
            }
            running = true;
        }

        @Override
        public void stop() {
            stops++;
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private static final class FakeFactory implements ImChannelConnectorFactory {
        private final String type;
        /** 仅令首个创建的连接器首次 start 抛错，用于重试测试。 */
        private final boolean failFirstCreatedStart;
        private final List<FakeConnector> created = new ArrayList<>();

        FakeFactory(String type, boolean failFirstCreatedStart) {
            this.type = type;
            this.failFirstCreatedStart = failFirstCreatedStart;
        }

        @Override
        public String channelType() {
            return type;
        }

        @Override
        public ImChannelConnector create(ChannelRobot robot) {
            boolean fail = failFirstCreatedStart && created.isEmpty();
            FakeConnector c = new FakeConnector(type, fail);
            created.add(c);
            return c;
        }
    }
}

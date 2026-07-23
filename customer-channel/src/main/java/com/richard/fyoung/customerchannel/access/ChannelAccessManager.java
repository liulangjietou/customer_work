package com.richard.fyoung.customerchannel.access;

import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnector;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnectorFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 渠道接入管理器 —— 定时拉取 admin 机器人列表，与运行中连接做 diff 并维护连接生命周期。
 *
 * <p>diff 规则：新增→start；version 变化→restart；消失/停用→stop。admin 不可达时仅记 error 日志、
 * 保留现有连接、下轮重试；单台机器人启动失败被兜底，不影响其它机器人与宿主应用。
 * {@link PreDestroy} 时优雅停止全部连接。</p>
 * @author owlzhangfq@gmail.com
 */
public class ChannelAccessManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelAccessManager.class);

    private final Map<String, ImChannelConnectorFactory> factoriesByType;
    private final AdminOpenApiClient adminClient;
    private final ChannelAccessProperties properties;

    /** key = channelType:robotId → 运行中连接。 */
    private final ConcurrentMap<String, Running> running = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    public ChannelAccessManager(List<ImChannelConnectorFactory> factories,
                                AdminOpenApiClient adminClient,
                                ChannelAccessProperties properties) {
        this.adminClient = adminClient;
        this.properties = properties;
        this.factoriesByType = CollectionUtils.isEmpty(factories)
            ? Map.of()
            : factories.stream().collect(Collectors.toMap(
                ImChannelConnectorFactory::channelType, f -> f, (a, b) -> a));
    }

    @PostConstruct
    public void init() {
        if (CollectionUtils.isEmpty(factoriesByType)) {
            log.info("channel access manager started with no connector factory, nothing to schedule");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "channel-access-refresh");
            t.setDaemon(true);
            return t;
        });
        int interval = properties.getRefreshIntervalSeconds();
        scheduler.scheduleWithFixedDelay(this::refreshQuietly, 0, interval, TimeUnit.SECONDS);
        log.info("channel access manager started, channelTypes={}, refreshIntervalSeconds={}",
            factoriesByType.keySet(), interval);
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            log.error("channel access refresh failed, code={}", ChannelAccessConstants.CODE_REFRESH_FAIL, e);
        }
    }

    /**
     * 执行一轮 diff 同步（包级可见，便于单测直接驱动，不依赖调度线程）。
     */
    void refresh() {
        Set<String> seen = new HashSet<>();
        Set<String> fetchedTypes = new HashSet<>();

        for (String channelType : factoriesByType.keySet()) {
            List<ChannelRobot> robots;
            try {
                robots = adminClient.listRobots(channelType);
            } catch (Exception e) {
                // admin 不可达：不把该类型标记为已抓取，从而不误停其运行中连接，下轮重试
                log.error("channel robots fetch failed, code={}, channelType={}",
                    ChannelAccessConstants.CODE_ROBOTS_FETCH_FAIL, channelType, e);
                continue;
            }
            fetchedTypes.add(channelType);
            if (CollectionUtils.isEmpty(robots)) {
                continue;
            }
            for (ChannelRobot robot : robots) {
                String key = key(channelType, robot.getId());
                seen.add(key);
                Running cur = running.get(key);
                if (cur == null) {
                    startRobot(key, robot);
                } else if (!Objects.equals(cur.version, robot.getVersion())) {
                    restartRobot(key, cur, robot);
                }
            }
        }

        // 停用/消失：仅处理本轮成功抓取到的渠道类型，避免抓取失败时误停
        running.forEach((key, run) -> {
            if (fetchedTypes.contains(run.channelType) && !seen.contains(key)) {
                stopRobot(key, run);
            }
        });
    }

    private void startRobot(String key, ChannelRobot robot) {
        ImChannelConnectorFactory factory = factoriesByType.get(robot.getChannelType());
        if (factory == null) {
            return;
        }
        try {
            ImChannelConnector connector = factory.create(robot);
            connector.start();
            running.put(key, new Running(robot.getChannelType(), robot.getVersion(), connector));
            log.info("channel robot started, channelType={}, robotId={}, appKey={}",
                robot.getChannelType(), robot.getId(), robot.getAppKey());
        } catch (Exception e) {
            // 单台失败不入 running，下轮自动重试；不影响其它机器人与应用
            log.error("channel robot start failed, code={}, channelType={}, appKey={}",
                ChannelAccessConstants.CODE_START_FAIL, robot.getChannelType(), robot.getAppKey(), e);
        }
    }

    private void restartRobot(String key, Running cur, ChannelRobot robot) {
        stopConnectorQuietly(cur, robot.getAppKey());
        running.remove(key);
        startRobot(key, robot);
    }

    private void stopRobot(String key, Running run) {
        stopConnectorQuietly(run, null);
        running.remove(key);
        log.info("channel robot stopped, channelType={}, key={}", run.channelType, key);
    }

    private void stopConnectorQuietly(Running run, String appKey) {
        try {
            run.connector.stop();
        } catch (Exception e) {
            log.error("channel robot stop failed, code={}, channelType={}, appKey={}",
                ChannelAccessConstants.CODE_STOP_FAIL, run.channelType, appKey, e);
        }
    }

    private String key(String channelType, Long robotId) {
        return channelType + ":" + robotId;
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        running.forEach((key, run) -> stopConnectorQuietly(run, null));
        running.clear();
        log.info("channel access manager stopped");
    }

    /** 运行中连接的记账。 */
    private static final class Running {
        private final String channelType;
        private final Long version;
        private final ImChannelConnector connector;

        private Running(String channelType, Long version, ImChannelConnector connector) {
            this.channelType = channelType;
            this.version = version;
            this.connector = connector;
        }
    }
}

package com.richard.fyoung.customeradmin;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 完整 Spring 上下文启动测试（对接本机 MySQL：localhost:3306，root/root，
 * dev profile 自动跑 Flyway 迁移建出 customer_admin 库全部 14 张表 + 种子数据）。
 *
 * <p>MySQL 不可达时自动跳过（与仓库既有 JDBC 门控测试同一约定），保证 {@code mvn test}
 * 在无 MySQL 的环境仍然通过；MySQL 在线的机器上验证 Sa-Token/MyBatis-Plus/Flyway/
 * 全部 Controller Bean 装配无冲突。</p>
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest
@ActiveProfiles("dev")
class CustomerAdminServerApplicationTests {

    @BeforeAll
    static void checkMysqlReachable() {
        assumeTrue(reachable("localhost", 3306), "MySQL 不可达（localhost:3306），跳过该测试");
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void contextLoads() {
        // 仅验证容器能完整启动：Sa-Token 拦截器、MyBatis-Plus Mapper、Flyway 迁移、
        // 全部 Controller/Service Bean 装配均无冲突。
    }
}

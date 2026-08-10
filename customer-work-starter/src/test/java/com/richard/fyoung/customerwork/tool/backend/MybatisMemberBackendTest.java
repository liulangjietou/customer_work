package com.richard.fyoung.customerwork.tool.backend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.tool.backend.entity.MemberAccountLogDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.MemberAccountLogMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.MemberMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis 会员后端测试（对接本机 MySQL；不可达自动跳过）：种子会员积分/等级文案断言、账户问题落库。
 *
 * <p>种子 U-demo-1 与 {@link MockMemberBackend} 数据一致；resolveAccountIssue 用例在 finally 删除自建日志行。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisMemberBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private MemberAccountLogMapper memberAccountLogMapper;
    private MybatisMemberBackend backend;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-member-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        MemberMapper memberMapper = MybatisTestSupport.mapper(dataSource, MemberMapper.class);
        memberAccountLogMapper = MybatisTestSupport.mapper(dataSource, MemberAccountLogMapper.class);
        backend = new MybatisMemberBackend(memberMapper, memberAccountLogMapper);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
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
    void queryPoints_seededMember_shouldMatchMockText() {
        String result = backend.queryPoints("U-demo-1").block();
        assertTrue(result.contains("当前积分 1280 分"), "种子积分应为 1280");
        assertTrue(result.contains("可抵扣 12.80 元"), "1280 积分抵 12.80 元");
        assertTrue(result.contains("本月底将有 200 分到期"), "到期积分 200");
    }

    @Test
    void queryMemberLevel_seededMember_shouldMatchMockText() {
        String result = backend.queryMemberLevel("U-demo-1").block();
        assertTrue(result.contains("黄金会员"), "种子等级黄金会员");
        assertTrue(result.contains("再消费 500.00 元可升级铂金"));
    }

    @Test
    void queryPoints_unknownMember_shouldReturnNotFound() {
        String result = backend.queryPoints("U-not-exist").block();
        assertTrue(result.contains("未查询到会员"));
    }

    @Test
    void resolveAccountIssue_shouldKeepGuideAndPersistLog() {
        long before = countAccountLog();
        try {
            String result = backend.resolveAccountIssue("无法登录").block();
            assertTrue(result.contains("验证码登录"), "应保留固定处置话术");
            assertTrue(result.contains("触发风控"));
            assertTrue(countAccountLog() > before, "应落一条处理日志");
        } finally {
            deleteAccountLog("无法登录");
        }
    }

    private long countAccountLog() {
        return memberAccountLogMapper.selectCount(null);
    }

    private void deleteAccountLog(String issue) {
        memberAccountLogMapper.delete(new LambdaQueryWrapper<MemberAccountLogDO>().eq(MemberAccountLogDO::getIssue, issue));
    }
}

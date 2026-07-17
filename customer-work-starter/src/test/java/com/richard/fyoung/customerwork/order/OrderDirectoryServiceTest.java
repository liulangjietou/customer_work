package com.richard.fyoung.customerwork.order;

import com.richard.fyoung.customerwork.common.PageResult;
import com.richard.fyoung.customerwork.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.user.entity.UserDO;
import com.richard.fyoung.customerwork.user.mapper.UserMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link OrderDirectoryService} 门控测试（对接本机 MySQL；不可达自动跳过）。
 *
 * <p>用例自建临时用户 + 两张临时订单（待发货 / 已发货），断言后在 tearDown 全部删除，不污染种子数据。
 * 覆盖：用户名 JOIN 分页、订单号精确查询、详情、改址、取消的三态（OK / NOT_FOUND / STATE_CONFLICT）。</p>
 * @author owlzhangfq@gmail.com
 */
class OrderDirectoryServiceTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private static final String USER_ID = "U-odt-1";
    private static final String USERNAME = "odt_tester";
    private static final String ORDER_PENDING = "ODT-PENDING-1";   // 待发货：可取消
    private static final String ORDER_SHIPPED = "ODT-SHIPPED-1";   // 已发货：不可取消

    private HikariDataSource dataSource;
    private OrderMapper orderMapper;
    private UserMapper userMapper;
    private OrderDirectoryService service;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-order-directory-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        orderMapper = MybatisTestSupport.mapper(dataSource, OrderMapper.class);
        userMapper = MybatisTestSupport.mapper(dataSource, UserMapper.class);
        service = new OrderDirectoryService(providerOf(orderMapper));
        cleanup();
        seedUser();
        seedOrder(ORDER_PENDING, "待发货", null);
        seedOrder(ORDER_SHIPPED, "已发货", "[已揽收]→[派送中]");
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            cleanup();
            dataSource.close();
        }
    }

    @Test
    void page_byUsername_shouldJoinUserAndReturnRows() {
        PageResult<OrderDirectoryRow> result = service.page(
            new OrderDirectoryQuery(null, null, null, USERNAME, 1, 20));
        assertEquals(2, result.total(), "两张临时订单均属该用户");
        assertTrue(result.items().stream().allMatch(r -> USERNAME.equals(r.getUsername())), "用户名应被 JOIN 带出");
    }

    @Test
    void page_byOrderIdExact_shouldReturnSingle() {
        PageResult<OrderDirectoryRow> result = service.page(
            new OrderDirectoryQuery(null, ORDER_PENDING, null, null, 1, 20));
        assertEquals(1, result.total());
        assertEquals(ORDER_PENDING, result.items().get(0).getOrderId());
    }

    @Test
    void findDetail_shouldReturnRowWithLogisticsAndUsername() {
        Optional<OrderDirectoryRow> detail = service.findDetail(ORDER_SHIPPED);
        assertTrue(detail.isPresent());
        assertEquals(USERNAME, detail.get().getUsername());
        assertEquals("[已揽收]→[派送中]", detail.get().getLogisticsTrace());
    }

    @Test
    void findDetail_unknown_shouldReturnEmpty() {
        assertTrue(service.findDetail("ODT-NO-SUCH").isEmpty());
    }

    @Test
    void modifyAddress_shouldPersistAndReturnOk() {
        OrderMutationResult result = service.modifyAddress(ORDER_PENDING, "新地址-测试路 1 号");
        assertEquals(OrderMutationResult.OK, result);
        assertEquals("新地址-测试路 1 号", orderMapper.selectById(ORDER_PENDING).getReceiverAddr());
    }

    @Test
    void modifyAddress_unknown_shouldReturnNotFound() {
        assertEquals(OrderMutationResult.NOT_FOUND, service.modifyAddress("ODT-NO-SUCH", "x"));
    }

    @Test
    void cancel_pendingOrder_shouldReturnOkAndSetCancelled() {
        assertEquals(OrderMutationResult.OK, service.cancel(ORDER_PENDING, "用户申请"));
        assertEquals("已取消", orderMapper.selectById(ORDER_PENDING).getStatus());
    }

    @Test
    void cancel_shippedOrder_shouldReturnStateConflict() {
        assertEquals(OrderMutationResult.STATE_CONFLICT, service.cancel(ORDER_SHIPPED, "太晚了"));
        assertEquals("已发货", orderMapper.selectById(ORDER_SHIPPED).getStatus(), "冲突不应改状态");
    }

    @Test
    void cancel_unknown_shouldReturnNotFound() {
        assertEquals(OrderMutationResult.NOT_FOUND, service.cancel("ODT-NO-SUCH", "x"));
    }

    @Test
    void isEnabled_withMapper_shouldBeTrue() {
        assertTrue(service.isEnabled());
        assertFalse(new OrderDirectoryService(providerOf(null)).isEnabled(), "无 Mapper 时应降级为未启用");
    }

    // ---- 夹具 ----

    private void seedUser() {
        UserDO user = new UserDO();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setPasswordHash("x");
        user.setNickname("订单测试用户");
        user.setStatus("ACTIVE");
        user.setCreatedAtMs(System.currentTimeMillis());
        userMapper.insert(user);
    }

    private void seedOrder(String orderId, String status, String logistics) {
        OrderDO order = new OrderDO();
        order.setOrderId(orderId);
        order.setUserId(USER_ID);
        order.setProductId("P001");
        order.setProductName("测试商品");
        order.setAmount(new BigDecimal("123.45"));
        order.setStatus(status);
        order.setReceiverAddr("原地址-测试市");
        order.setLogisticsTrace(logistics);
        order.setCreatedAtMs(System.currentTimeMillis());
        orderMapper.insert(order);
    }

    private void cleanup() {
        orderMapper.deleteById(ORDER_PENDING);
        orderMapper.deleteById(ORDER_SHIPPED);
        userMapper.deleteById(USER_ID);
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 极简 ObjectProvider 桩：脱离 Spring 容器时把已构建的 Mapper（或 null）交给被测服务。 */
    private static ObjectProvider<OrderMapper> providerOf(OrderMapper mapper) {
        return new ObjectProvider<>() {
            @Override
            public OrderMapper getObject(Object... args) {
                return mapper;
            }

            @Override
            public OrderMapper getObject() {
                return mapper;
            }

            @Override
            public OrderMapper getIfAvailable() {
                return mapper;
            }

            @Override
            public OrderMapper getIfUnique() {
                return mapper;
            }
        };
    }
}

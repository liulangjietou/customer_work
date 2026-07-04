package com.richard.fyoung.customerwork.approval;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 审批存储装配选择单测（离线，无需 MySQL）：默认 memory 模式选中 {@link InMemoryApprovalStore}。
 *
 * <p>{@code store-mode=jdbc} 分支的装配验证见 {@link JdbcApprovalStoreTest}
 * （需真实 MySQL，本类不覆盖——JDBC 连接池首次取连接失败会抛出非受检异常，不适合无 DB 环境构造）。</p>
 * @author owlzhangfq@gmail.com
 */
class ApprovalConfigTest {

    @Test
    void approvalStore_shouldSelectInMemory_byDefault() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        ApprovalStore store = new ApprovalConfig().approvalStore(props);
        assertInstanceOf(InMemoryApprovalStore.class, store);
    }

    @Test
    void approvalStore_shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanApproval().setStoreMode("memory");
        ApprovalStore store = new ApprovalConfig().approvalStore(props);
        assertInstanceOf(InMemoryApprovalStore.class, store);
    }
}

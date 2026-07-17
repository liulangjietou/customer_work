package com.richard.fyoung.customerwork.config;

import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.ComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MemberBackend;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockMemberBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import com.richard.fyoung.customerwork.tool.backend.MockProductBackend;
import com.richard.fyoung.customerwork.tool.backend.MybatisAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MybatisComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.MybatisKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MybatisMemberBackend;
import com.richard.fyoung.customerwork.tool.backend.MybatisOrderBackend;
import com.richard.fyoung.customerwork.tool.backend.MybatisProductBackend;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import com.richard.fyoung.customerwork.tool.backend.ProductBackend;
import com.richard.fyoung.customerwork.tool.backend.mapper.ComplaintMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.MemberAccountLogMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.MemberMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.ProductMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务工具后端的装配（扩展点核心，支持 jdbc / mock 两种模式）。
 *
 * <p><b>jdbc 模式</b>（{@code customer-work.tool-backend.mode=jdbc}）：先注册基于 MyBatis-Plus 的
 * {@code Mybatis*Backend}（订单/商品/售后/会员/投诉/知识库六域落 {@code cw_*} 演示表），Mapper 由
 * {@link CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 提供。<b>mock 模式</b>（默认）：jdbc 方法条件
 * 不满足，进程内 {@code Mock*Backend} 生效，宿主无需 MySQL 即可启动。</p>
 *
 * <p>两组 Bean 都以 {@code @ConditionalOnMissingBean(接口)} 收尾：jdbc 模式下 Mybatis 后端先注册、Mock 因
 * 已存在同类型 Bean 让位；下游工程仍可声明同类型 {@code @Bean}（调真实业务系统）覆盖二者——实现
 * "开箱即用 + 零改框架即可接入自有业务"。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class ToolBackendConfig {

    private static final String MODE_KEY = "customer-work.tool-backend.mode";
    private static final String MODE_JDBC = "jdbc";

    // ==================== jdbc 模式：MyBatis-Plus 后端（tool-backend.mode=jdbc 时装配） ====================

    @Bean
    @ConditionalOnProperty(name = MODE_KEY, havingValue = MODE_JDBC)
    @ConditionalOnMissingBean(OrderBackend.class)
    public OrderBackend jdbcOrderBackend(OrderMapper orderMapper) {
        return new MybatisOrderBackend(orderMapper);
    }

    @Bean
    @ConditionalOnProperty(name = MODE_KEY, havingValue = MODE_JDBC)
    @ConditionalOnMissingBean(ProductBackend.class)
    public ProductBackend jdbcProductBackend(ProductMapper productMapper) {
        return new MybatisProductBackend(productMapper);
    }

    @Bean
    @ConditionalOnProperty(name = MODE_KEY, havingValue = MODE_JDBC)
    @ConditionalOnMissingBean(AfterSalesBackend.class)
    public AfterSalesBackend jdbcAfterSalesBackend(RefundMapper refundMapper,
                                                   InvoiceRequestMapper invoiceRequestMapper,
                                                   OrderMapper orderMapper) {
        return new MybatisAfterSalesBackend(refundMapper, invoiceRequestMapper, orderMapper);
    }

    @Bean
    @ConditionalOnProperty(name = MODE_KEY, havingValue = MODE_JDBC)
    @ConditionalOnMissingBean(MemberBackend.class)
    public MemberBackend jdbcMemberBackend(MemberMapper memberMapper,
                                           MemberAccountLogMapper memberAccountLogMapper) {
        return new MybatisMemberBackend(memberMapper, memberAccountLogMapper);
    }

    @Bean
    @ConditionalOnProperty(name = MODE_KEY, havingValue = MODE_JDBC)
    @ConditionalOnMissingBean(ComplaintBackend.class)
    public ComplaintBackend jdbcComplaintBackend(ComplaintMapper complaintMapper) {
        return new MybatisComplaintBackend(complaintMapper);
    }

    @Bean
    @ConditionalOnProperty(name = MODE_KEY, havingValue = MODE_JDBC)
    @ConditionalOnMissingBean(KnowledgeBackend.class)
    public KnowledgeBackend jdbcKnowledgeBackend(KnowledgeMapper knowledgeMapper) {
        return new MybatisKnowledgeBackend(knowledgeMapper);
    }

    // ==================== mock 模式：进程内示例后端（默认，OnMissingBean 让位于 jdbc/下游） ====================

    @Bean
    @ConditionalOnMissingBean(OrderBackend.class)
    public OrderBackend orderBackend() {
        return new MockOrderBackend();
    }

    @Bean
    @ConditionalOnMissingBean(ProductBackend.class)
    public ProductBackend productBackend() {
        return new MockProductBackend();
    }

    @Bean
    @ConditionalOnMissingBean(AfterSalesBackend.class)
    public AfterSalesBackend afterSalesBackend() {
        return new MockAfterSalesBackend();
    }

    @Bean
    @ConditionalOnMissingBean(MemberBackend.class)
    public MemberBackend memberBackend() {
        return new MockMemberBackend();
    }

    @Bean
    @ConditionalOnMissingBean(ComplaintBackend.class)
    public ComplaintBackend complaintBackend() {
        return new MockComplaintBackend();
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeBackend.class)
    public KnowledgeBackend knowledgeBackend() {
        return new MockKnowledgeBackend();
    }
}

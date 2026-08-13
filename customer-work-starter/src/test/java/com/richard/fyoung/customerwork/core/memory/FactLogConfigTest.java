package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.FactLogMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事实日志装配选择单测（离线，无需 MySQL）：Mapper 可用时落库，缺席时空实现。
 *
 * <p>缺席分支的重点是"<b>不落盘</b>也不抛异常"——本项目不再提供文件形态的事实日志，
 * 但缺它也不该让容器起不来。{@code jdbc} 分支的真实读写验证见 {@link MybatisFactLogTest}（需真实 MySQL）。</p>
 * @author owlzhangfq@gmail.com
 */
class FactLogConfigTest {

    @Test
    void shouldSelectMybatis_whenMapperAvailable() {
        CustomerWorkProperties props = new CustomerWorkProperties();

        FactLog factLog = new FactLogConfig().factLog(props, provider(mock(FactLogMapper.class)));

        assertInstanceOf(MybatisFactLog.class, factLog);
    }

    @Test
    void shouldFallBackToNoOp_whenMapperMissing() {
        CustomerWorkProperties props = new CustomerWorkProperties();

        FactLog factLog = new FactLogConfig().factLog(props, provider(null));

        assertInstanceOf(NoOpFactLog.class, factLog,
            "Mapper 缺席时应装空实现：既不能抛异常拖垮容器，也不能偷偷写本地盘");
    }

    /** 装配分支只调 getIfAvailable，其余方法不打桩。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<FactLogMapper> provider(FactLogMapper mapper) {
        ObjectProvider<FactLogMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        return provider;
    }
}

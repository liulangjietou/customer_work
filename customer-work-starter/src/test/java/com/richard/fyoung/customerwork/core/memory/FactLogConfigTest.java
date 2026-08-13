package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.FactLogMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事实日志装配选择单测（离线，无需 MySQL）。
 *
 * <p>覆盖三条分支：显式 {@code file} 选落盘实现；默认（jdbc）在 Mapper 可用时选 MyBatis 实现；
 * 默认但 Mapper 缺席时<b>降级</b>回落盘——事实日志宁可写本地盘也不该整条丢掉，更不该因此让容器起不来。</p>
 *
 * <p>{@code jdbc} 分支的真实读写验证见 {@link MybatisFactLogTest}（需真实 MySQL）。</p>
 * @author owlzhangfq@gmail.com
 */
class FactLogConfigTest {

    @Test
    void shouldSelectFile_whenStoreModeExplicitlyFile(@TempDir Path dir) {
        CustomerWorkProperties props = propsWithDirectory(dir);
        props.getFactLog().setStoreMode("file");

        FactLog factLog = new FactLogConfig().factLog(props, provider(null));

        assertInstanceOf(FileFactLog.class, factLog);
    }

    @Test
    void shouldSelectMybatis_byDefault_whenMapperAvailable(@TempDir Path dir) {
        CustomerWorkProperties props = propsWithDirectory(dir);

        FactLog factLog = new FactLogConfig().factLog(props, provider(mock(FactLogMapper.class)));

        assertInstanceOf(MybatisFactLog.class, factLog);
    }

    @Test
    void shouldDegradeToFile_whenJdbcButMapperMissing(@TempDir Path dir) {
        CustomerWorkProperties props = propsWithDirectory(dir);
        props.getFactLog().setStoreMode("jdbc");

        FactLog factLog = new FactLogConfig().factLog(props, provider(null));

        assertInstanceOf(FileFactLog.class, factLog,
            "jdbc 但 Mapper 缺席时必须降级落盘，不能抛异常拖垮容器启动");
    }

    private static CustomerWorkProperties propsWithDirectory(Path dir) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getFactLog().setDirectory(dir.toString());
        return props;
    }

    /** 装配分支只调 getIfAvailable，其余方法不打桩。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<FactLogMapper> provider(FactLogMapper mapper) {
        ObjectProvider<FactLogMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        return provider;
    }
}

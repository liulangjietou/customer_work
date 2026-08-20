package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDatasource;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDatasourceMapper;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部数据源连接池管理：按 {@code datasource_id} 懒加载 HikariDataSource 并缓存复用。
 *
 * <p>目标多为生产库，池参数保守：{@code maximumPoolSize=5}、{@code minimumIdle=0}（空闲即回收）、
 * {@code connectionTimeout=5s}、{@code readOnly=true}（连接级只读，与 {@link SqlValidator} 文本层
 * 校验构成只读双保险）。数据源被编辑/删除/禁用时由 Service 调用 {@link #evict(Long)} 关闭旧池，
 * 保证下次取用重建为最新连接信息。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SqlDatasourceConnectionManager {


    private static final Logger log = LoggerFactory.getLogger(SqlDatasourceConnectionManager.class);

    private static final int MAX_POOL_SIZE = 5;
    private static final int MIN_IDLE = 0;
    private static final long CONNECTION_TIMEOUT_MS = 5000;
    private final ConcurrentHashMap<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    private final SqlDatasourceMapper datasourceMapper;
    private final AesGcmCryptoUtil cryptoUtil;

    public SqlDatasourceConnectionManager(SqlDatasourceMapper datasourceMapper, AesGcmCryptoUtil cryptoUtil) {
        this.datasourceMapper = datasourceMapper;
        this.cryptoUtil = cryptoUtil;
    }

    /** 取指定数据源的命名参数模板（内部懒建/复用连接池）；数据源不存在或禁用抛业务异常。 */
    public NamedParameterJdbcTemplate getTemplate(Long datasourceId) {
        HikariDataSource dataSource = pools.computeIfAbsent(datasourceId, this::buildPool);
        return new NamedParameterJdbcTemplate(dataSource);
    }

    /** 关闭并移除指定数据源的连接池（编辑/删除/禁用时调用，幂等）。 */
    public void evict(Long datasourceId) {
        HikariDataSource dataSource = pools.remove(datasourceId);
        if (dataSource != null) {
            dataSource.close();
            log.info("sql datasource pool evicted, datasourceId={}", datasourceId);
        }
    }

    private HikariDataSource buildPool(Long datasourceId) {
        SqlDatasource datasource = datasourceMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new BizException(ResultCode.SQL_DATASOURCE_UNAVAILABLE, "数据源不存在: " + datasourceId);
        }
        if (datasource.getEnabled() == null || datasource.getEnabled() != StatusFlags.ENABLED) {
            throw new BizException(ResultCode.SQL_DATASOURCE_UNAVAILABLE, "数据源已禁用: " + datasource.getName());
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("sql-cfg-" + datasource.getName() + "-" + datasourceId);
        dataSource.setJdbcUrl(datasource.getJdbcUrl());
        dataSource.setUsername(datasource.getUsername());
        dataSource.setPassword(cryptoUtil.decrypt(datasource.getPassword()));
        dataSource.setMaximumPoolSize(MAX_POOL_SIZE);
        dataSource.setMinimumIdle(MIN_IDLE);
        dataSource.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        dataSource.setReadOnly(true);
        log.info("sql datasource pool created, datasourceId={}, name={}", datasourceId, datasource.getName());
        return dataSource;
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}

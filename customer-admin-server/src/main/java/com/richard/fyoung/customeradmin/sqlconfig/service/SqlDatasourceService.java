package com.richard.fyoung.customeradmin.sqlconfig.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDatasourceSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDatasourceVO;
import com.richard.fyoung.customeradmin.sqlconfig.engine.SqlDatasourceConnectionManager;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDatasource;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDefine;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDatasourceMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * SQL 配置数据源管理：CRUD + 密码加密存储/留空不改/脱敏回显 + 连通性测试 + 连接池失效。
 *
 * <p>密码处理沿用 {@code ModelConfigService} 的 AppKey 手法（AES/GCM 密文入库，VO 回显
 * {@code mask(decrypt(...))}）。数据源编辑/删除/禁用后使 {@link SqlDatasourceConnectionManager}
 * 的缓存池失效，避免继续用旧连接信息。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class SqlDatasourceService {

    private static final Logger log = LoggerFactory.getLogger(SqlDatasourceService.class);

    /** 连通性测试专用线程池：与 Tomcat 请求线程隔离（同 ModelConfigService 手法）。 */
    private static final ExecutorService TEST_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "sql-datasource-test-worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final long TEST_FUTURE_TIMEOUT_SECONDS = 8;
    private static final int TEST_LOGIN_TIMEOUT_SECONDS = 5;
    private static final int ENABLED = 1;

    private final SqlDatasourceMapper datasourceMapper;
    private final SqlDefineMapper defineMapper;
    private final AesGcmCryptoUtil cryptoUtil;
    private final SqlDatasourceConnectionManager connectionManager;

    public SqlDatasourceService(SqlDatasourceMapper datasourceMapper, SqlDefineMapper defineMapper,
                                AesGcmCryptoUtil cryptoUtil, SqlDatasourceConnectionManager connectionManager) {
        this.datasourceMapper = datasourceMapper;
        this.defineMapper = defineMapper;
        this.cryptoUtil = cryptoUtil;
        this.connectionManager = connectionManager;
    }

    public PageResult<SqlDatasourceVO> page(PageQuery query) {
        LambdaQueryWrapper<SqlDatasource> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(SqlDatasource::getName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(SqlDatasource::getEnabled, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), SqlDatasource::getCreateTime);

        IPage<SqlDatasource> page = datasourceMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toVo));
    }

    /** 仅启用数据源（供 SQL 定义表单下拉选择）。 */
    public List<SqlDatasourceVO> listEnabled() {
        return datasourceMapper.selectList(new LambdaQueryWrapper<SqlDatasource>()
                .eq(SqlDatasource::getEnabled, ENABLED)
                .orderByDesc(SqlDatasource::getCreateTime))
            .stream().map(this::toVo).collect(Collectors.toList());
    }

    public SqlDatasourceVO get(Long id) {
        return toVo(requireDatasource(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(SqlDatasourceSaveRequest request) {
        if (!StringUtils.hasText(request.password())) {
            throw new BizException(ResultCode.PARAM_MISSING, "新建数据源必须提供 password");
        }
        requireNameAvailable(request.name(), null);
        SqlDatasource datasource = new SqlDatasource();
        fillFromRequest(datasource, request);
        datasource.setPassword(cryptoUtil.encrypt(request.password()));
        datasourceMapper.insert(datasource);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SqlDatasourceSaveRequest request) {
        SqlDatasource datasource = requireDatasource(id);
        requireNameAvailable(request.name(), id);
        fillFromRequest(datasource, request);
        if (StringUtils.hasText(request.password())) {
            datasource.setPassword(cryptoUtil.encrypt(request.password()));
        }
        datasourceMapper.updateById(datasource);
        // 连接信息可能变化，作废旧连接池，下次取用重建
        connectionManager.evict(id);
    }

    public void delete(Long id) {
        requireDatasource(id);
        if (defineMapper.exists(new LambdaQueryWrapper<SqlDefine>().eq(SqlDefine::getDatasourceId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该数据源正被 SQL 定义引用，无法删除");
        }
        datasourceMapper.deleteById(id);
        connectionManager.evict(id);
    }

    /**
     * 连通性测试：解密密码后派发到独立线程池直连执行 SELECT 1，硬性超时兜底，不占用请求线程。
     * 只读探测不新增权限点（复用 view，与项目惯例一致），也不改动配置。
     */
    public CompletableFuture<Result<Void>> testConnection(Long id) {
        SqlDatasource datasource = requireDatasource(id);
        String jdbcUrl = datasource.getJdbcUrl();
        String username = datasource.getUsername();
        String password = cryptoUtil.decrypt(datasource.getPassword());

        return CompletableFuture
            .supplyAsync(() -> probe(jdbcUrl, username, password), TEST_EXECUTOR)
            .orTimeout(TEST_FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                log.error("sql datasource test error, code={}, datasourceId={}", "SQLCFG-DS-TEST-FAIL", id, ex);
                return Result.failure(ResultCode.SQL_DATASOURCE_UNAVAILABLE, "连通性测试超时或执行异常");
            });
    }

    private Result<Void> probe(String jdbcUrl, String username, String password) {
        DriverManager.setLoginTimeout(TEST_LOGIN_TIMEOUT_SECONDS);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(TEST_LOGIN_TIMEOUT_SECONDS);
            statement.execute("SELECT 1");
            return Result.success();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Result.failure(ResultCode.SQL_DATASOURCE_UNAVAILABLE, message);
        }
    }

    private void requireNameAvailable(String name, Long excludeId) {
        LambdaQueryWrapper<SqlDatasource> wrapper = new LambdaQueryWrapper<SqlDatasource>()
            .eq(SqlDatasource::getName, name);
        if (excludeId != null) {
            wrapper.ne(SqlDatasource::getId, excludeId);
        }
        if (datasourceMapper.exists(wrapper)) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "数据源名称已存在: " + name);
        }
    }

    private void fillFromRequest(SqlDatasource datasource, SqlDatasourceSaveRequest request) {
        datasource.setName(request.name());
        datasource.setJdbcUrl(request.jdbcUrl());
        datasource.setUsername(request.username());
        datasource.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : ENABLED);
        datasource.setRemark(request.remark());
    }

    private SqlDatasourceVO toVo(SqlDatasource datasource) {
        SqlDatasourceVO vo = new SqlDatasourceVO();
        vo.setId(datasource.getId());
        vo.setName(datasource.getName());
        vo.setJdbcUrl(datasource.getJdbcUrl());
        vo.setUsername(datasource.getUsername());
        vo.setPasswordMasked(AesGcmCryptoUtil.mask(cryptoUtil.decrypt(datasource.getPassword())));
        vo.setEnabled(datasource.getEnabled() != null && datasource.getEnabled() == ENABLED);
        vo.setRemark(datasource.getRemark());
        vo.setCreateTime(datasource.getCreateTime());
        vo.setUpdateTime(datasource.getUpdateTime());
        return vo;
    }

    private SqlDatasource requireDatasource(Long id) {
        SqlDatasource datasource = datasourceMapper.selectById(id);
        if (datasource == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "数据源不存在: " + id);
        }
        return datasource;
    }
}

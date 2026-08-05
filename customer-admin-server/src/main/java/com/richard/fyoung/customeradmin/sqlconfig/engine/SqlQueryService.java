package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSqlConfigProperties;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryMetaVO;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryParamMetaVO;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryResultVO;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDefine;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDefineParam;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlFieldTransform;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineParamMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlFieldTransformMapper;
import com.richard.fyoung.customerwork.sqlkit.DefaultValueResolver;
import com.richard.fyoung.customerwork.sqlkit.ParamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通用 SQL 查询执行引擎：按 defineKey 执行已配置的只读 SQL，返回动态列结果。
 *
 * <p>执行流程：查启用的 define → 查参数元数据 → 参数处理（必填校验/类型转换/默认值表达式解析/
 * 分页参数换算 offset）→ 取只读连接模板并设查询超时与最大行数兜底 → 有 count_sql 先查总数 →
 * query_sql 从 {@code ResultSetMetaData} 按列序取列名（getColumnLabel 拿 AS 别名）→ 应用列转换器
 * → 组装结果（含耗时）。执行异常记错误码日志后转 {@link BizException}，不把堆栈泄露给前端。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class SqlQueryService {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ENABLED = 1;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;
    // 即席查询结果的时间列格式化：MySQL DATETIME 经 JDBC 取出是 LocalDateTime，
    // Jackson 默认按 ISO-8601 序列化会带 'T'（2026-07-20T10:00:05），统一成无 T 的常用格式
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SqlDefineMapper defineMapper;
    private final SqlDefineParamMapper paramMapper;
    private final SqlFieldTransformMapper transformMapper;
    private final SqlDatasourceConnectionManager connectionManager;
    private final FieldTransformer fieldTransformer;
    private final AdminSqlConfigProperties properties;

    public SqlQueryService(SqlDefineMapper defineMapper, SqlDefineParamMapper paramMapper,
                           SqlFieldTransformMapper transformMapper, SqlDatasourceConnectionManager connectionManager,
                           FieldTransformer fieldTransformer, AdminSqlConfigProperties properties) {
        this.defineMapper = defineMapper;
        this.paramMapper = paramMapper;
        this.transformMapper = transformMapper;
        this.connectionManager = connectionManager;
        this.fieldTransformer = fieldTransformer;
        this.properties = properties;
    }

    /** 通用查询页元数据：描述 + 是否自动执行 + 是否有总数 SQL + 参数表单元数据（默认值/下拉已解析）。 */
    public SqlQueryMetaVO meta(String defineKey) {
        SqlDefine define = requireEnabledDefine(defineKey);
        List<SqlDefineParam> params = listParams(define.getId());
        SqlQueryMetaVO vo = new SqlQueryMetaVO();
        vo.setDefineKey(define.getDefineKey());
        vo.setSqlDescribe(define.getSqlDescribe());
        vo.setAutoLoad(isFlag(define.getAutoLoad()));
        vo.setHasCountSql(StringUtils.hasText(define.getCountSql()));
        vo.setParams(params.stream().map(this::toParamMeta).collect(Collectors.toList()));
        return vo;
    }

    /** 通用查询（分页）。 */
    public SqlQueryResultVO execute(String defineKey, Map<String, Object> params) {
        return doExecute(defineKey, params, false);
    }

    /** 导出（把分页页码/页大小强制换算为 offset=0 / pageSize=maxRows，导出上限即 maxRows）。 */
    public SqlQueryResultVO executeForExport(String defineKey, Map<String, Object> params) {
        return doExecute(defineKey, params, true);
    }

    /**
     * 即席（adhoc）只读查询：对指定数据源直接执行调用方传入的任意 SQL（SQL 客户端用）。
     *
     * <p>与 {@link #execute} 不同，SQL 文本来自调用方而非预配置 {@code SqlDefine}，故先经
     * {@link SqlValidator#validateReadOnly} 文本层拦截（仅 SELECT/WITH、拒绝多语句），再走连接级
     * {@code readOnly=true} 的只读模板双保险；{@code maxRows} 兜底截断，{@code total} 记实际返回行数。</p>
     */
    public SqlQueryResultVO executeAdhoc(Long datasourceId, String rawSql) {
        long start = System.currentTimeMillis();
        SqlValidator.validateReadOnly(rawSql);
        NamedParameterJdbcTemplate template = connectionManager.getTemplate(datasourceId);
        JdbcTemplate jdbcTemplate = (JdbcTemplate) template.getJdbcOperations();
        jdbcTemplate.setQueryTimeout(properties.getQueryTimeoutSeconds());
        jdbcTemplate.setMaxRows(properties.getMaxRows());
        try {
            SqlQueryResultVO result = jdbcTemplate.query(rawSql, (ResultSetExtractor<SqlQueryResultVO>) this::extract);
            normalizeTemporalValues(result);
            result.setTotal(result.getRows() == null ? 0 : result.getRows().size());
            result.setUseMillis(System.currentTimeMillis() - start);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("adhoc sql execute failed, code={}, datasourceId={}", "SQLCFG-ADHOC-FAIL", datasourceId, e);
            throw new BizException(ResultCode.SQL_QUERY_EXECUTE_FAILED, shortMessage(e));
        }
    }

    private SqlQueryResultVO doExecute(String defineKey, Map<String, Object> input, boolean export) {
        long start = System.currentTimeMillis();
        SqlDefine define = requireEnabledDefine(defineKey);
        List<SqlDefineParam> params = listParams(define.getId());
        MapSqlParameterSource paramSource = bindParams(params, input, export);

        NamedParameterJdbcTemplate template = connectionManager.getTemplate(define.getDatasourceId());
        JdbcTemplate jdbcTemplate = (JdbcTemplate) template.getJdbcOperations();
        jdbcTemplate.setQueryTimeout(properties.getQueryTimeoutSeconds());
        jdbcTemplate.setMaxRows(properties.getMaxRows());

        try {
            long total = -1;
            if (StringUtils.hasText(define.getCountSql())) {
                Long count = template.queryForObject(define.getCountSql(), paramSource, Long.class);
                total = count == null ? 0 : count;
            }
            SqlQueryResultVO result = template.query(define.getQuerySql(), paramSource,
                (ResultSetExtractor<SqlQueryResultVO>) this::extract);
            applyTransforms(define.getId(), result);
            // 兜底格式化放在列转换器之后：配了 DATE_FORMAT 的列已被转成用户格式（String，不再处理），
            // 没配转换器、仍是 LocalDateTime 的时间列在此去掉 ISO 的 T
            normalizeTemporalValues(result);
            result.setTotal(total);
            result.setUseMillis(System.currentTimeMillis() - start);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("sql execute failed, code={}, defineKey={}", "SQLCFG-EXECUTE-FAIL", defineKey, e);
            throw new BizException(ResultCode.SQL_QUERY_EXECUTE_FAILED, shortMessage(e));
        }
    }

    /**
     * 列出数据源下所有数据库（SQL 客户端左侧库树用）。元数据浏览、高频，不走 adhoc 审计。
     * 用 information_schema 只读查询，走连接级 readOnly 模板。
     */
    public List<String> listDatabases(Long datasourceId) {
        JdbcTemplate jt = metadataJdbcTemplate(datasourceId);
        return jt.queryForList(
            "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name", String.class);
    }

    /** 列出指定库下所有表（点库懒加载）。database 走参数绑定，无拼接注入风险。 */
    public List<String> listTables(Long datasourceId, String database) {
        if (!StringUtils.hasText(database)) {
            throw new BizException(ResultCode.PARAM_MISSING, "database 不能为空");
        }
        JdbcTemplate jt = metadataJdbcTemplate(datasourceId);
        return jt.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
            String.class, database);
    }

    private JdbcTemplate metadataJdbcTemplate(Long datasourceId) {
        NamedParameterJdbcTemplate template = connectionManager.getTemplate(datasourceId);
        JdbcTemplate jt = (JdbcTemplate) template.getJdbcOperations();
        jt.setQueryTimeout(properties.getQueryTimeoutSeconds());
        return jt;
    }

    /** 从结果集按列序取列名（getColumnLabel 拿 AS 别名），行数据用 LinkedHashMap 保序。 */
    private SqlQueryResultVO extract(ResultSet rs) throws java.sql.SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }
        SqlQueryResultVO vo = new SqlQueryResultVO();
        vo.setColumns(columns);
        vo.setRows(rows);
        return vo;
    }

    /**
     * 把结果里的时间类型值格式化成无 'T' 的常用字符串（yyyy-MM-dd HH:mm:ss 等）。
     *
     * <p>即席查询直接调；预配置查询在 {@code FieldTransformer} 之后调——配了 DATE_FORMAT 的列
     * 已是用户格式 String（本方法不动 String），只兜底处理未配转换器、仍为时间类型的列。</p>
     */
    private void normalizeTemporalValues(SqlQueryResultVO result) {
        if (result.getRows() == null) {
            return;
        }
        for (Map<String, Object> row : result.getRows()) {
            row.replaceAll((key, value) -> formatTemporal(value));
        }
    }

    private Object formatTemporal(Object value) {
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(DATETIME_FMT);
        }
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime().format(DATETIME_FMT);
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).format(DATE_FMT);
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().format(DATE_FMT);
        }
        if (value instanceof LocalTime) {
            return ((LocalTime) value).format(TIME_FMT);
        }
        if (value instanceof java.sql.Time) {
            return ((java.sql.Time) value).toLocalTime().format(TIME_FMT);
        }
        return value;
    }

    /**
     * 参数处理：必填校验 + 类型转换 + 默认值表达式解析 + 分页参数换算。包内可见，供单测直接验证。
     */
    MapSqlParameterSource bindParams(List<SqlDefineParam> params, Map<String, Object> input, boolean export) {
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        int pageSize = resolvePageSize(params, safeInput, export);
        MapSqlParameterSource source = new MapSqlParameterSource();
        for (SqlDefineParam param : params) {
            String name = param.getParamName();
            String raw = rawValue(safeInput, name);
            if (isBlank(raw) && StringUtils.hasText(param.getDefaultValue())) {
                raw = DefaultValueResolver.resolve(param.getDefaultValue(), param.getDateFormat());
            }
            if (isFlag(param.getIsPageNum())) {
                long offset = export ? 0L : computeOffset(parsePageNum(raw), pageSize);
                source.addValue(name, offset);
                continue;
            }
            if (isFlag(param.getIsPageSize())) {
                source.addValue(name, pageSize);
                continue;
            }
            if (isBlank(raw)) {
                if (isFlag(param.getRequired())) {
                    throw new BizException(ResultCode.SQL_PARAM_INVALID, "缺少必填参数: " + name);
                }
                source.addValue(name, null);
                continue;
            }
            source.addValue(name, convert(param.getParamType(), name, raw));
        }
        return source;
    }

    /** 分页页大小：导出时固定为 maxRows；否则取 isPageSize 参数值（缺省用 maxRows），并钳制到 [1, maxRows]。 */
    int resolvePageSize(List<SqlDefineParam> params, Map<String, Object> input, boolean export) {
        int maxRows = properties.getMaxRows();
        if (export) {
            return maxRows;
        }
        SqlDefineParam sizeParam = params.stream().filter(p -> isFlag(p.getIsPageSize())).findFirst().orElse(null);
        if (sizeParam == null) {
            return maxRows;
        }
        String raw = rawValue(input, sizeParam.getParamName());
        if (isBlank(raw) && StringUtils.hasText(sizeParam.getDefaultValue())) {
            raw = DefaultValueResolver.resolve(sizeParam.getDefaultValue());
        }
        int size = parsePositiveInt(raw, maxRows);
        return Math.min(Math.max(size, 1), maxRows);
    }

    /** offset = (pageNum-1) * pageSize，下限 0。 */
    static long computeOffset(int pageNum, int pageSize) {
        int effectivePageNum = Math.max(pageNum, 1);
        return (long) (effectivePageNum - 1) * pageSize;
    }

    private Object convert(String paramType, String name, String raw) {
        if (ParamType.INTEGER.name().equalsIgnoreCase(paramType)) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                throw new BizException(ResultCode.SQL_PARAM_INVALID, "参数 " + name + " 需为整数: " + raw);
            }
        }
        // STRING / DATETIME 透传字符串
        return raw;
    }

    private SqlQueryParamMetaVO toParamMeta(SqlDefineParam param) {
        SqlQueryParamMetaVO vo = new SqlQueryParamMetaVO();
        vo.setParamName(param.getParamName());
        vo.setParamDesc(param.getParamDesc());
        vo.setParamType(param.getParamType());
        vo.setDateFormat(param.getDateFormat());
        vo.setRequired(isFlag(param.getRequired()));
        // 默认值时间表达式按参数配置的日期格式解析，保证预填值能被前端日期控件按同格式回显
        vo.setDefaultValue(DefaultValueResolver.resolve(param.getDefaultValue(), param.getDateFormat()));
        vo.setDropDown(parseDropDown(param.getDropDown()));
        vo.setIsPageNum(isFlag(param.getIsPageNum()));
        vo.setIsPageSize(isFlag(param.getIsPageSize()));
        vo.setSort(param.getSort());
        return vo;
    }

    private Map<String, String> parseDropDown(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private void applyTransforms(Long defineId, SqlQueryResultVO result) {
        List<SqlFieldTransform> transforms = transformMapper.selectList(
            new LambdaQueryWrapper<SqlFieldTransform>().eq(SqlFieldTransform::getDefineId, defineId));
        fieldTransformer.apply(transforms, result.getRows());
    }

    private SqlDefine requireEnabledDefine(String defineKey) {
        SqlDefine define = defineMapper.selectOne(
            new LambdaQueryWrapper<SqlDefine>().eq(SqlDefine::getDefineKey, defineKey));
        if (define == null || define.getEnabled() == null || define.getEnabled() != ENABLED) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "SQL 定义不存在或未启用: " + defineKey);
        }
        return define;
    }

    private List<SqlDefineParam> listParams(Long defineId) {
        return paramMapper.selectList(new LambdaQueryWrapper<SqlDefineParam>()
            .eq(SqlDefineParam::getDefineId, defineId)
            .orderByAsc(SqlDefineParam::getSort));
    }

    private String rawValue(Map<String, Object> input, String name) {
        Object value = input.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isFlag(Integer value) {
        return value != null && value == ENABLED;
    }

    private boolean isFlag(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private int parsePageNum(String raw) {
        return parsePositiveInt(raw, 1);
    }

    private int parsePositiveInt(String raw, int defaultValue) {
        if (isBlank(raw)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String shortMessage(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
            ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..." : message;
    }
}

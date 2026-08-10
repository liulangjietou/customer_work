package com.richard.fyoung.customeradmin.sqlconfig.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineParamSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineParamVO;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlDefineVO;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlFieldTransformSaveRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlFieldTransformVO;
import com.richard.fyoung.customeradmin.sqlconfig.engine.SqlValidator;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDatasource;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDefine;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlDefineParam;
import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlFieldTransform;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDatasourceMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlDefineParamMapper;
import com.richard.fyoung.customeradmin.sqlconfig.mapper.SqlFieldTransformMapper;
import com.richard.fyoung.customerwork.infra.sqlkit.ParamType;
import com.richard.fyoung.customerwork.infra.sqlkit.TransformType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQL 定义管理：define CRUD（保存过 SqlValidator 只读校验）+ 复制 + 参数/列转换器子资源 CRUD。
 *
 * <p>删除 define 时级联删除其参数与列转换器（事务内）；复制会连带复制全部子表记录，
 * define_key 追加 {@code -copy} 后缀（冲突再追加数字）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class SqlDefineService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ENABLED = 1;
    private static final String COPY_SUFFIX = "-copy";

    private final SqlDefineMapper defineMapper;
    private final SqlDefineParamMapper paramMapper;
    private final SqlFieldTransformMapper transformMapper;
    private final SqlDatasourceMapper datasourceMapper;

    public SqlDefineService(SqlDefineMapper defineMapper, SqlDefineParamMapper paramMapper,
                            SqlFieldTransformMapper transformMapper, SqlDatasourceMapper datasourceMapper) {
        this.defineMapper = defineMapper;
        this.paramMapper = paramMapper;
        this.transformMapper = transformMapper;
        this.datasourceMapper = datasourceMapper;
    }

    // ============================ define CRUD ============================

    public PageResult<SqlDefineVO> page(PageQuery query) {
        LambdaQueryWrapper<SqlDefine> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w.like(SqlDefine::getDefineKey, query.getKeyword())
                .or().like(SqlDefine::getSqlDescribe, query.getKeyword()));
        }
        if (query.getStatus() != null) {
            wrapper.eq(SqlDefine::getEnabled, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), SqlDefine::getCreateTime);

        IPage<SqlDefine> page = defineMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toVo));
    }

    public SqlDefineVO get(Long id) {
        return toVo(requireDefine(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(SqlDefineSaveRequest request) {
        validateSql(request.querySql(), request.countSql());
        requireDefineKeyAvailable(request.defineKey(), null);
        requireDatasource(request.datasourceId());
        SqlDefine define = new SqlDefine();
        fillFromRequest(define, request);
        defineMapper.insert(define);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SqlDefineSaveRequest request) {
        SqlDefine define = requireDefine(id);
        validateSql(request.querySql(), request.countSql());
        requireDefineKeyAvailable(request.defineKey(), id);
        requireDatasource(request.datasourceId());
        fillFromRequest(define, request);
        defineMapper.updateById(define);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireDefine(id);
        paramMapper.delete(new LambdaQueryWrapper<SqlDefineParam>().eq(SqlDefineParam::getDefineId, id));
        transformMapper.delete(new LambdaQueryWrapper<SqlFieldTransform>().eq(SqlFieldTransform::getDefineId, id));
        defineMapper.deleteById(id);
    }

    /** 复制 define + 全部参数/列转换器；define_key 追加 -copy 后缀（冲突再追加数字）。 */
    @Transactional(rollbackFor = Exception.class)
    public void copy(Long id) {
        SqlDefine source = requireDefine(id);
        SqlDefine copy = new SqlDefine();
        copy.setDefineKey(resolveCopyKey(source.getDefineKey()));
        copy.setDatasourceId(source.getDatasourceId());
        copy.setSqlDescribe(source.getSqlDescribe());
        copy.setQuerySql(source.getQuerySql());
        copy.setCountSql(source.getCountSql());
        copy.setAutoLoad(source.getAutoLoad());
        copy.setEnabled(source.getEnabled());
        copy.setRemark(source.getRemark());
        defineMapper.insert(copy);

        for (SqlDefineParam param : listParamEntities(id)) {
            SqlDefineParam paramCopy = new SqlDefineParam();
            paramCopy.setDefineId(copy.getId());
            paramCopy.setParamName(param.getParamName());
            paramCopy.setParamDesc(param.getParamDesc());
            paramCopy.setParamType(param.getParamType());
            paramCopy.setDateFormat(param.getDateFormat());
            paramCopy.setRequired(param.getRequired());
            paramCopy.setDefaultValue(param.getDefaultValue());
            paramCopy.setDropDown(param.getDropDown());
            paramCopy.setIsPageNum(param.getIsPageNum());
            paramCopy.setIsPageSize(param.getIsPageSize());
            paramCopy.setSort(param.getSort());
            paramMapper.insert(paramCopy);
        }
        for (SqlFieldTransform transform : listTransformEntities(id)) {
            SqlFieldTransform transformCopy = new SqlFieldTransform();
            transformCopy.setDefineId(copy.getId());
            transformCopy.setFieldName(transform.getFieldName());
            transformCopy.setTransformType(transform.getTransformType());
            transformCopy.setTransformConfig(transform.getTransformConfig());
            transformMapper.insert(transformCopy);
        }
    }

    // ============================ 参数子资源 ============================

    public List<SqlDefineParamVO> listParams(Long defineId) {
        requireDefine(defineId);
        return listParamEntities(defineId).stream().map(this::toParamVo).collect(Collectors.toList());
    }

    public void createParam(Long defineId, SqlDefineParamSaveRequest request) {
        requireDefine(defineId);
        validateParam(request);
        SqlDefineParam param = new SqlDefineParam();
        param.setDefineId(defineId);
        fillParam(param, request);
        paramMapper.insert(param);
    }

    public void updateParam(Long defineId, Long paramId, SqlDefineParamSaveRequest request) {
        SqlDefineParam param = requireParam(defineId, paramId);
        validateParam(request);
        fillParam(param, request);
        paramMapper.updateById(param);
    }

    public void deleteParam(Long defineId, Long paramId) {
        requireParam(defineId, paramId);
        paramMapper.deleteById(paramId);
    }

    // ============================ 列转换器子资源 ============================

    public List<SqlFieldTransformVO> listTransforms(Long defineId) {
        requireDefine(defineId);
        return listTransformEntities(defineId).stream().map(this::toTransformVo).collect(Collectors.toList());
    }

    public void createTransform(Long defineId, SqlFieldTransformSaveRequest request) {
        requireDefine(defineId);
        validateTransform(request);
        SqlFieldTransform transform = new SqlFieldTransform();
        transform.setDefineId(defineId);
        fillTransform(transform, request);
        transformMapper.insert(transform);
    }

    public void updateTransform(Long defineId, Long transformId, SqlFieldTransformSaveRequest request) {
        SqlFieldTransform transform = requireTransform(defineId, transformId);
        validateTransform(request);
        fillTransform(transform, request);
        transformMapper.updateById(transform);
    }

    public void deleteTransform(Long defineId, Long transformId) {
        requireTransform(defineId, transformId);
        transformMapper.deleteById(transformId);
    }

    // ============================ 校验与装配 ============================

    private void validateSql(String querySql, String countSql) {
        SqlValidator.validateReadOnly(querySql);
        if (StringUtils.hasText(countSql)) {
            SqlValidator.validateReadOnly(countSql);
        }
    }

    private void validateParam(SqlDefineParamSaveRequest request) {
        if (!ParamType.isValid(request.paramType())) {
            throw new BizException(ResultCode.PARAM_INVALID, "paramType 非法（仅 STRING/INTEGER/DATETIME）: " + request.paramType());
        }
        if (StringUtils.hasText(request.dateFormat())) {
            if (!ParamType.DATETIME.name().equalsIgnoreCase(request.paramType())) {
                throw new BizException(ResultCode.PARAM_INVALID, "dateFormat 仅 DATETIME 类型参数可配置");
            }
            try {
                DateTimeFormatter.ofPattern(request.dateFormat());
            } catch (IllegalArgumentException e) {
                throw new BizException(ResultCode.PARAM_INVALID, "dateFormat 非法（如 yyyy-MM-dd HH:mm:ss / yyyy-MM-dd）: " + request.dateFormat());
            }
        }
        if (StringUtils.hasText(request.dropDown())) {
            requireJsonObject(request.dropDown(), "dropDown");
        }
    }

    private void validateTransform(SqlFieldTransformSaveRequest request) {
        if (!TransformType.isValid(request.transformType())) {
            throw new BizException(ResultCode.PARAM_INVALID, "transformType 非法（仅 DATE_FORMAT/VALUE_MAP）: " + request.transformType());
        }
        if (TransformType.VALUE_MAP.name().equalsIgnoreCase(request.transformType())) {
            requireJsonObject(request.transformConfig(), "transformConfig");
        }
    }

    private void requireJsonObject(String json, String field) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw new BizException(ResultCode.PARAM_INVALID, field + " 须为 JSON 对象");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, field + " 不是合法 JSON: " + e.getMessage());
        }
    }

    private void requireDefineKeyAvailable(String defineKey, Long excludeId) {
        LambdaQueryWrapper<SqlDefine> wrapper = new LambdaQueryWrapper<SqlDefine>()
            .eq(SqlDefine::getDefineKey, defineKey);
        if (excludeId != null) {
            wrapper.ne(SqlDefine::getId, excludeId);
        }
        if (defineMapper.exists(wrapper)) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "defineKey 已存在: " + defineKey);
        }
    }

    private void requireDatasource(Long datasourceId) {
        SqlDatasource datasource = datasourceMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "数据源不存在: " + datasourceId);
        }
    }

    private String resolveCopyKey(String originalKey) {
        String candidate = originalKey + COPY_SUFFIX;
        int suffix = 2;
        while (defineMapper.exists(new LambdaQueryWrapper<SqlDefine>().eq(SqlDefine::getDefineKey, candidate))) {
            candidate = originalKey + COPY_SUFFIX + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private void fillFromRequest(SqlDefine define, SqlDefineSaveRequest request) {
        define.setDefineKey(request.defineKey());
        define.setDatasourceId(request.datasourceId());
        define.setSqlDescribe(request.sqlDescribe());
        define.setQuerySql(request.querySql());
        define.setCountSql(request.countSql());
        define.setAutoLoad(Boolean.TRUE.equals(request.autoLoad()) ? ENABLED : 0);
        define.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : ENABLED);
        define.setRemark(request.remark());
    }

    private void fillParam(SqlDefineParam param, SqlDefineParamSaveRequest request) {
        param.setParamName(request.paramName());
        param.setParamDesc(request.paramDesc());
        param.setParamType(request.paramType().toUpperCase());
        param.setDateFormat(request.dateFormat());
        param.setRequired(Boolean.TRUE.equals(request.required()) ? ENABLED : 0);
        param.setDefaultValue(request.defaultValue());
        param.setDropDown(request.dropDown());
        param.setIsPageNum(Boolean.TRUE.equals(request.isPageNum()) ? ENABLED : 0);
        param.setIsPageSize(Boolean.TRUE.equals(request.isPageSize()) ? ENABLED : 0);
        param.setSort(request.sort() == null ? 0 : request.sort());
    }

    private void fillTransform(SqlFieldTransform transform, SqlFieldTransformSaveRequest request) {
        transform.setFieldName(request.fieldName());
        transform.setTransformType(request.transformType().toUpperCase());
        transform.setTransformConfig(request.transformConfig());
    }

    private List<SqlDefineParam> listParamEntities(Long defineId) {
        return paramMapper.selectList(new LambdaQueryWrapper<SqlDefineParam>()
            .eq(SqlDefineParam::getDefineId, defineId)
            .orderByAsc(SqlDefineParam::getSort));
    }

    private List<SqlFieldTransform> listTransformEntities(Long defineId) {
        return transformMapper.selectList(new LambdaQueryWrapper<SqlFieldTransform>()
            .eq(SqlFieldTransform::getDefineId, defineId));
    }

    private SqlDefineVO toVo(SqlDefine define) {
        SqlDefineVO vo = new SqlDefineVO();
        vo.setId(define.getId());
        vo.setDefineKey(define.getDefineKey());
        vo.setDatasourceId(define.getDatasourceId());
        SqlDatasource datasource = datasourceMapper.selectById(define.getDatasourceId());
        vo.setDatasourceName(datasource == null ? null : datasource.getName());
        vo.setSqlDescribe(define.getSqlDescribe());
        vo.setQuerySql(define.getQuerySql());
        vo.setCountSql(define.getCountSql());
        vo.setAutoLoad(define.getAutoLoad() != null && define.getAutoLoad() == ENABLED);
        vo.setEnabled(define.getEnabled() != null && define.getEnabled() == ENABLED);
        vo.setRemark(define.getRemark());
        vo.setCreateTime(define.getCreateTime());
        vo.setUpdateTime(define.getUpdateTime());
        return vo;
    }

    private SqlDefineParamVO toParamVo(SqlDefineParam param) {
        SqlDefineParamVO vo = new SqlDefineParamVO();
        vo.setId(param.getId());
        vo.setDefineId(param.getDefineId());
        vo.setParamName(param.getParamName());
        vo.setParamDesc(param.getParamDesc());
        vo.setParamType(param.getParamType());
        vo.setDateFormat(param.getDateFormat());
        vo.setRequired(param.getRequired() != null && param.getRequired() == ENABLED);
        vo.setDefaultValue(param.getDefaultValue());
        vo.setDropDown(param.getDropDown());
        vo.setIsPageNum(param.getIsPageNum() != null && param.getIsPageNum() == ENABLED);
        vo.setIsPageSize(param.getIsPageSize() != null && param.getIsPageSize() == ENABLED);
        vo.setSort(param.getSort());
        return vo;
    }

    private SqlFieldTransformVO toTransformVo(SqlFieldTransform transform) {
        SqlFieldTransformVO vo = new SqlFieldTransformVO();
        vo.setId(transform.getId());
        vo.setDefineId(transform.getDefineId());
        vo.setFieldName(transform.getFieldName());
        vo.setTransformType(transform.getTransformType());
        vo.setTransformConfig(transform.getTransformConfig());
        return vo;
    }

    private SqlDefine requireDefine(Long id) {
        SqlDefine define = defineMapper.selectById(id);
        if (define == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "SQL 定义不存在: " + id);
        }
        return define;
    }

    private SqlDefineParam requireParam(Long defineId, Long paramId) {
        SqlDefineParam param = paramMapper.selectById(paramId);
        if (param == null || !param.getDefineId().equals(defineId)) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "参数不存在或不属于该 SQL 定义: " + paramId);
        }
        return param;
    }

    private SqlFieldTransform requireTransform(Long defineId, Long transformId) {
        SqlFieldTransform transform = transformMapper.selectById(transformId);
        if (transform == null || !transform.getDefineId().equals(defineId)) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "列转换器不存在或不属于该 SQL 定义: " + transformId);
        }
        return transform;
    }
}

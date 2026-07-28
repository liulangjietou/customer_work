package com.richard.fyoung.customeradmin.contentguard.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordPageQuery;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordSaveRequest;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordVO;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordQueryParam;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWordAction;
import com.richard.fyoung.customerwork.sensitiveword.SensitiveWordCategory;
import com.richard.fyoung.customerwork.sensitiveword.entity.SensitiveWordEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 敏感词词库管理：分页查询、增删改、启停、批量导入导出。
 *
 * <p><b>写的是客服端库的 {@code cw_sensitive_word}——唯一真源</b>。后台改完不需要通知任何客服实例：
 * starter 侧的 {@code SensitiveWordRefresher} 轮询版本指纹，指纹一变就重建自动机，默认 60 秒内生效。
 * 这也是为什么这里每次写入都刷新 {@code updated_at_ms}：它就是那个指纹的组成部分，不刷新等于改了不生效。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class SensitiveWordService {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordService.class);

    /** 批量导入单次上限：一次导太多既拖长事务，也让失败时难以定位是哪几行的问题。 */
    private static final int MAX_IMPORT_SIZE = 2000;

    private final ContentGuardGatewayProvider gatewayProvider;

    public SensitiveWordService(ContentGuardGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    /** 分页查询词库。 */
    public PageResult<SensitiveWordVO> page(SensitiveWordPageQuery query) {
        ContentGuardGateway gateway = gatewayProvider.get();
        SensitiveWordQueryParam param = toParam(query);
        long total = gateway.wordExtMapper().countBy(param);
        List<SensitiveWordVO> list = new ArrayList<>();
        if (total > 0) {
            for (SensitiveWordEntity row : gateway.wordExtMapper().findPage(param)) {
                list.add(toVO(row));
            }
        }
        PageResult<SensitiveWordVO> result = new PageResult<>();
        result.setPageNum(query.getPageNum());
        result.setPageSize(query.getPageSize());
        result.setTotal(total);
        result.setList(list);
        return result;
    }

    /** 按 ID 取一条。 */
    public SensitiveWordVO get(Long id) {
        SensitiveWordEntity row = gatewayProvider.get().wordMapper().selectById(id);
        if (row == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "敏感词不存在: " + id);
        }
        return toVO(row);
    }

    /** 新增。词面唯一，重复直接拒绝而不是静默覆盖——覆盖会把别人配的类目/动作悄悄改掉。 */
    public void create(SensitiveWordSaveRequest request) {
        ContentGuardGateway gateway = gatewayProvider.get();
        String word = normalizeWord(request.getWord());
        if (gateway.wordExtMapper().findByWord(word) != null) {
            throw new BizException(ResultCode.PARAM_INVALID, "敏感词已存在: " + word);
        }
        SensitiveWordEntity row = new SensitiveWordEntity();
        row.setWord(word);
        row.setCategory(parseCategory(request.getCategory()).name());
        row.setAction(parseAction(request.getAction()).name());
        row.setEnabled(request.getEnabled() == null || request.getEnabled());
        long now = System.currentTimeMillis();
        row.setCreatedAtMs(now);
        row.setUpdatedAtMs(now);
        gateway.wordMapper().insert(row);
        log.info("[CONTENT-GUARD] sensitive word created, word={}, action={}", word, row.getAction());
    }

    /** 编辑。 */
    public void update(Long id, SensitiveWordSaveRequest request) {
        ContentGuardGateway gateway = gatewayProvider.get();
        SensitiveWordEntity existing = gateway.wordMapper().selectById(id);
        if (existing == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "敏感词不存在: " + id);
        }
        String word = normalizeWord(request.getWord());
        SensitiveWordEntity sameWord = gateway.wordExtMapper().findByWord(word);
        if (sameWord != null && !sameWord.getId().equals(id)) {
            throw new BizException(ResultCode.PARAM_INVALID, "敏感词已存在: " + word);
        }
        SensitiveWordEntity row = new SensitiveWordEntity();
        row.setId(id);
        row.setWord(word);
        row.setCategory(parseCategory(request.getCategory()).name());
        row.setAction(parseAction(request.getAction()).name());
        row.setEnabled(request.getEnabled() == null || request.getEnabled());
        row.setUpdatedAtMs(System.currentTimeMillis());
        gateway.wordMapper().updateById(row);
        log.info("[CONTENT-GUARD] sensitive word updated, id={}, word={}", id, word);
    }

    /** 删除。 */
    public void delete(Long id) {
        ContentGuardGateway gateway = gatewayProvider.get();
        if (gateway.wordMapper().deleteById(id) == 0) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "敏感词不存在: " + id);
        }
        log.info("[CONTENT-GUARD] sensitive word deleted, id={}", id);
    }

    /** 启停。 */
    public void toggle(Long id, boolean enabled) {
        ContentGuardGateway gateway = gatewayProvider.get();
        SensitiveWordEntity existing = gateway.wordMapper().selectById(id);
        if (existing == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "敏感词不存在: " + id);
        }
        SensitiveWordEntity row = new SensitiveWordEntity();
        row.setId(id);
        row.setEnabled(enabled);
        row.setUpdatedAtMs(System.currentTimeMillis());
        gateway.wordMapper().updateById(row);
        log.info("[CONTENT-GUARD] sensitive word toggled, id={}, enabled={}", id, enabled);
    }

    /**
     * 批量导入：每行 {@code 词面,类目,动作}（类目与动作可省，分别默认 CUSTOM 与 BLOCK）。
     *
     * <p>走 upsert 而不是逐条判重后 insert：导入的语义就是"以这份清单为准"，同名词更新其类目与动作，
     * 比报错让运营逐条排查更贴合实际用法。返回实际处理条数。</p>
     */
    public int importWords(List<String> lines) {
        if (CollectionUtils.isEmpty(lines)) {
            return 0;
        }
        if (lines.size() > MAX_IMPORT_SIZE) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "单次导入最多 " + MAX_IMPORT_SIZE + " 条，当前 " + lines.size() + " 条");
        }
        ContentGuardGateway gateway = gatewayProvider.get();
        long now = System.currentTimeMillis();
        int count = 0;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",");
            String word = normalizeWord(parts[0]);
            if (word.isEmpty()) {
                continue;
            }
            SensitiveWordEntity row = new SensitiveWordEntity();
            row.setWord(word);
            row.setCategory(parts.length > 1 ? parseCategory(parts[1]).name() : SensitiveWordCategory.CUSTOM.name());
            row.setAction(parts.length > 2 ? parseAction(parts[2]).name() : SensitiveWordAction.BLOCK.name());
            row.setEnabled(true);
            row.setCreatedAtMs(now);
            row.setUpdatedAtMs(now);
            gateway.wordMapper().upsert(row);
            count++;
        }
        log.info("[CONTENT-GUARD] sensitive words imported, count={}", count);
        return count;
    }

    /** 导出全部词条（按导入格式：词面,类目,动作），供运营备份与迁移。 */
    public List<String> exportWords() {
        List<SensitiveWordEntity> rows = gatewayProvider.get().wordMapper().selectList(null);
        List<String> lines = new ArrayList<>(rows.size());
        for (SensitiveWordEntity row : rows) {
            lines.add(row.getWord() + "," + row.getCategory() + "," + row.getAction());
        }
        return lines;
    }

    /** 可选类目/动作枚举，供前端下拉直接渲染，避免前后端各维护一份常量。 */
    public List<String> categories() {
        return Arrays.stream(SensitiveWordCategory.values()).map(Enum::name).toList();
    }

    public List<String> actions() {
        return Arrays.stream(SensitiveWordAction.values()).map(Enum::name).toList();
    }

    private SensitiveWordQueryParam toParam(SensitiveWordPageQuery query) {
        SensitiveWordQueryParam param = new SensitiveWordQueryParam();
        param.setKeyword(query.getKeyword());
        param.setCategory(query.getCategory());
        param.setAction(query.getAction());
        param.setEnabled(query.getStatus() == null ? null : query.getStatus() == 1);
        long pageSize = query.getPageSize() <= 0 ? 10 : query.getPageSize();
        long pageNum = query.getPageNum() <= 0 ? 1 : query.getPageNum();
        param.setLimit((int) pageSize);
        param.setOffset((int) ((pageNum - 1) * pageSize));
        return param;
    }

    private SensitiveWordVO toVO(SensitiveWordEntity row) {
        SensitiveWordVO vo = new SensitiveWordVO();
        vo.setId(row.getId());
        vo.setWord(row.getWord());
        vo.setCategory(row.getCategory());
        vo.setAction(row.getAction());
        vo.setEnabled(row.getEnabled());
        vo.setCreatedAtMs(row.getCreatedAtMs());
        vo.setUpdatedAtMs(row.getUpdatedAtMs());
        return vo;
    }

    /** 词面去首尾空白：粘贴进来的词常带空格，不处理会导致"看起来一样却匹配不上"。 */
    private String normalizeWord(String word) {
        return word == null ? "" : word.trim();
    }

    private SensitiveWordCategory parseCategory(String raw) {
        try {
            return SensitiveWordCategory.valueOf(raw.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "非法类目: " + raw);
        }
    }

    private SensitiveWordAction parseAction(String raw) {
        try {
            return SensitiveWordAction.valueOf(raw.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "非法处置动作: " + raw);
        }
    }
}

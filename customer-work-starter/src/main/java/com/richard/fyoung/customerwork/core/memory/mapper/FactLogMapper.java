package com.richard.fyoung.customerwork.core.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.core.memory.entity.FactLogDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 事实日志 Mapper：写入走 {@link BaseMapper#insert}（表是 append-only，无更新/删除语义），
 * 带上限的倒序读取走 XML。
 * @author owlzhangfq@gmail.com
 */
public interface FactLogMapper extends BaseMapper<FactLogDO> {

    /**
     * 取某分区最近写入的若干条事实记录（{@code id} 倒序，即最新在前）。
     *
     * <p>调用方需自行反转以恢复写入顺序。设上限是因为事实日志只增不减，
     * 不封顶的话统计链路会把整表拉进内存。</p>
     */
    List<FactLogDO> selectRecent(@Param("scopeId") String scopeId, @Param("limit") int limit);
}

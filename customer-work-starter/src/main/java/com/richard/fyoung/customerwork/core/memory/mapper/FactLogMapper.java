package com.richard.fyoung.customerwork.core.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.core.memory.entity.FactLogDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 事实日志 Mapper：常规业务写入走 {@link BaseMapper#insert}，带上限的倒序读取走 XML；
 * 删除只允许隐私治理与保留策略入口调用。
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

    /** 跨租户保留策略任务分批清理到期事实。调用方必须显式进入治理作用域。 */
    int deleteExpiredBefore(@Param("cutoffMs") long cutoffMs, @Param("limit") int limit);
}

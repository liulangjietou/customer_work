package com.richard.fyoung.customerwork.calllog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.calllog.entity.AgentCallSegmentDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体调用分段明细 Mapper。写入走 {@link BaseMapper#insert}；按主记录 id 查询/删除在
 * {@code AgentCallSegmentMapper.xml} 中手写。
 * @author owlzhangfq@gmail.com
 */
public interface AgentCallSegmentMapper extends BaseMapper<AgentCallSegmentDO> {

    /** 按主记录 id 查全部分段（seq 升序）。 */
    List<AgentCallSegmentDO> findByCallLogId(@Param("callLogId") long callLogId);

    /** 按主记录 id 删除其全部分段。 */
    int deleteByCallLogId(@Param("callLogId") long callLogId);
}

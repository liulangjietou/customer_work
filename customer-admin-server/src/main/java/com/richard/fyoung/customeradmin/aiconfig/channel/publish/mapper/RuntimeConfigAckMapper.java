package com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimeConfigAckEntity;
import org.apache.ibatis.annotations.Param;

/** 运行时配置实例回执 Mapper。 */
public interface RuntimeConfigAckMapper extends BaseMapper<RuntimeConfigAckEntity> {

    int upsert(@Param("ack") RuntimeConfigAckEntity ack);

    int countByStatus(@Param("tenantId") String tenantId, @Param("revision") String revision,
                      @Param("status") String status);
}

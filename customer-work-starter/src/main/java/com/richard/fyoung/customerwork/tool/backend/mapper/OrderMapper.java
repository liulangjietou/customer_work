package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customerwork.data.order.OrderDirectoryRow;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 订单 SQL 边界：用户方法必须限定精确归属，坐席方法限定服务端签名租户；不依赖租户插件。 */
public interface OrderMapper extends BaseMapper<OrderDO> {
    /** 读取认证用户自己的订单。 */
    OrderDO findOwned(@Param("tenantId") String tenantId, @Param("userId") String userId,
                      @Param("orderId") String orderId);

    /** 归属校验与改址在同一条 UPDATE 完成。 */
    int modifyAddressForOwner(@Param("tenantId") String tenantId, @Param("userId") String userId,
                              @Param("orderId") String orderId, @Param("address") String address);

    /** 状态条件与归属条件同时参与取消写入。 */
    int cancelForOwner(@Param("tenantId") String tenantId, @Param("userId") String userId,
                       @Param("orderId") String orderId, @Param("cancellable") List<String> cancellable,
                       @Param("cancelled") String cancelled);

    /** 仅向认证用户的订单追加加急标记。 */
    int urgeShipmentForOwner(@Param("tenantId") String tenantId, @Param("userId") String userId,
                             @Param("orderId") String orderId);

    /** 坐席多维分页，用户 JOIN 也限定同租户与精确用户 ID。 */
    IPage<OrderDirectoryRow> pageForAgent(Page<OrderDirectoryRow> page,
                                         @Param("tenantId") String tenantId,
                                         @Param("userId") String userId,
                                         @Param("orderId") String orderId,
                                         @Param("status") String status,
                                         @Param("username") String username);

    /** 坐席查询当前签名租户内的订单详情。 */
    OrderDirectoryRow detailForAgent(@Param("tenantId") String tenantId, @Param("orderId") String orderId);

    /** 坐席改址，租户条件直接参与更新。 */
    int modifyAddressForAgent(@Param("tenantId") String tenantId, @Param("orderId") String orderId,
                              @Param("address") String address);

    /** 坐席取消只影响当前租户且仍处于可取消状态的订单。 */
    int cancelForAgent(@Param("tenantId") String tenantId, @Param("orderId") String orderId,
                       @Param("cancellable") List<String> cancellable, @Param("cancelled") String cancelled);
}

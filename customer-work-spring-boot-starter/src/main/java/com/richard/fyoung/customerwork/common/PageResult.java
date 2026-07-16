package com.richard.fyoung.customerwork.common;

import java.util.List;

/**
 * 通用分页结果：满足条件的总数 + 当前页数据。
 *
 * <p>对外 JSON 契约固定为 {@code {"total": n, "items": [...]}}——{@code items} 字段名与前端一致，
 * 控制器可直接返回本对象而无需再手工 map。各业务域（工单 / 订单等）以不同泛型 {@code T} 复用。</p>
 *
 * @param total 满足条件的总条数
 * @param items 当前页数据
 * @param <T>   数据元素类型
 * @author owlzhangfq@gmail.com
 */
public record PageResult<T>(long total, List<T> items) {
}

package com.richard.fyoung.customeradmin.common.page;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;

/**
 * 通用分页响应体。
 * @author owlzhangfq@gmail.com
 */
@Data
public class PageResult<T> {

    private long pageNum;
    private long pageSize;
    private long total;
    private List<T> list;

    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> result = new PageResult<>();
        result.pageNum = page.getCurrent();
        result.pageSize = page.getSize();
        result.total = page.getTotal();
        result.list = page.getRecords();
        return result;
    }
}

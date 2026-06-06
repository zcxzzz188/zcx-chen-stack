package com.zcx.chenstack.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author zcx
 * @since 2025-08-24
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageVo<T> {

    // 分页数据
    private T data;
    // 总数
    private Long total;

}

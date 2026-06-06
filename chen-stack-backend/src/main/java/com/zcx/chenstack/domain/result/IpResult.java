package com.zcx.chenstack.domain.result;

import com.zcx.chenstack.domain.entity.IpDetail;
import lombok.Data;

/**
 * @author zcx
 * @since 2025-08-11
 */
@Data
public class IpResult {

    private Integer ret;
    private IpDetail data;

}

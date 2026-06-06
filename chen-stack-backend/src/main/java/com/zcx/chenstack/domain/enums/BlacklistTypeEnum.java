package com.zcx.chenstack.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 黑名单类型枚举
 *
 * @author zcx
 * @since 2025-10-03
 */
@Getter
@AllArgsConstructor
public enum BlacklistTypeEnum {

    USER(0, "用户"),
    IP(1, "IP地址");

    private final Integer code;
    private final String description;

}


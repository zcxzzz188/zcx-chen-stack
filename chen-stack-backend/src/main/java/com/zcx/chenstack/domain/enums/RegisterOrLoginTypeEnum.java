package com.zcx.chenstack.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RegisterOrLoginTypeEnum {

    /**
     * 邮箱或用户名登录
     */
    EMAIL(0, "email", "用户名/邮箱登录");


    /**
     * 注册方式
     */
    private final Integer code;

    /**
     * 策略
     */
    private final String type;

    /**
     * 描述
     */
    private final String description;


    //根据code返回type
    public static String getType(Integer code) {
        for (RegisterOrLoginTypeEnum registerOrLoginTypeEnum : RegisterOrLoginTypeEnum.values()) {
            if (registerOrLoginTypeEnum.getCode().equals(code)) {
                return registerOrLoginTypeEnum.getType();
            }
        }
        return "unknown";
    }

    //根据type返回code
    public static Integer getCode(String type) {
        for (RegisterOrLoginTypeEnum registerOrLoginTypeEnum : RegisterOrLoginTypeEnum.values()) {
            if (registerOrLoginTypeEnum.getType().equals(type)) {
                return registerOrLoginTypeEnum.getCode();
            }
        }
        return 0;
    }

}

package com.zcx.chenstack.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/** 已登录用户修改本人密码；不接收目标用户 ID，不能修改他人密码。 */
@Data
public class ChangePasswordDto {

    @NotEmpty(message = "请输入当前密码")
    private String oldPassword;

    @NotEmpty(message = "请输入新密码")
    @Length(min = 6, max = 20, message = "新密码长度必须为 6～20 位")
    @Pattern(regexp = "^[a-zA-Z0-9!@#$%^&*._-]+$",
            message = "新密码格式不正确：仅允许英文字母、数字及以下特殊字符：!@#$%^&*._-")
    private String newPassword;
}

package com.zcx.chenstack.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

@Data
public class ResetPasswordDto {

    // 邮箱
    @Email
    @NotEmpty(message = "邮箱不能为空")
    private String email;

    // 邮箱验证码
    @NotEmpty(message = "验证码不能为空")
    @Length(max = 6, min = 6, message = "验证码长度必须为6位")
    private String emailCheckCode;

    // 密码
    @NotEmpty(message = "密码不能为空")
    @Length(min = 6, max = 20, message = "密码长度必须在6-20位之间")
    private String password;

}

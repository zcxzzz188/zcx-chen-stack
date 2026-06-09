package com.zcx.chenstack.config;

import com.zcx.chenstack.aspect.OriginCheckInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类
 * 注册自定义拦截器
 *
 * @author zcx
 * @since 2025-10-02
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    @Resource
    private OriginCheckInterceptor originCheckInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册来源校验拦截器
        registry.addInterceptor(originCheckInterceptor)
                .addPathPatterns("/**"); // 拦截所有请求
    }
}

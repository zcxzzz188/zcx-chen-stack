package com.zcx.chenstack.security;

import com.zcx.chenstack.config.ChenStackConfig;
import com.zcx.chenstack.domain.constants.SecurityConstants;
import com.zcx.chenstack.utils.SecurityUtils;
import jakarta.annotation.Resource;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collections;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Resource
    private SysUserDetailsService sysUserDetailsService;

    @Resource
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Resource
    private ChenStackConfig chenStackConfig; // 现在导入正确

    // Keep JWT authentication inside Spring Security, including stream async dispatches.
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // 设置认证管理器
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.PUT, "/user/info").access((authentication, context) ->
                        new AuthorizationDecision(SysUserDetailsService.isCommunityAccount(SecurityUtils.getUser())))
                .requestMatchers(SecurityConstants.Optional_Auth_Urls).permitAll()
                .requestMatchers("/article/admin/**", "/column/admin/**", "/comment/admin/**",
                        "/photo/admin/**", "/message/admin/**").authenticated()
                .requestMatchers("/article/**", "/column/**", "/comment/**", "/photo/**", "/message/**",
                        "/follow/**", "/conversation/**", "/favorite/**", "/like/**", "/history/**",
                        "/user/settings/**", "/user/verifyResetEmail", "/user/resetEmail", "/ai/**")
                .access((authentication, context) ->
                        new AuthorizationDecision(SysUserDetailsService.isCommunityAccount(SecurityUtils.getUser())))
                .anyRequest().authenticated());
        http.csrf(AbstractHttpConfigurer::disable);
        // 配置跨域
        http.cors(cors -> cors.configurationSource(configurationSource()));
        // 添加JWT过滤器
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterServletRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    // 创建AuthenticationManager
    @Bean
    public AuthenticationManager sysUserAuthenticationManager() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(sysUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(provider);
    }

    // 配置密码编码器
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 配置跨域，允许跨域 配置CorsConfigurationSource
    public CorsConfigurationSource configurationSource() {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedMethods(Collections.singletonList("*"));
        corsConfiguration.setAllowedHeaders(Collections.singletonList("*"));
        corsConfiguration.setAllowedOrigins(chenStackConfig.getAllowOrigins());
        corsConfiguration.setExposedHeaders(java.util.List.of("X-Refresh-Token", "X-Error-Code"));

        // 创建 CorsConfigurationSource对象
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);
        return source;
    }

}

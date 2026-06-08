package com.zcx.chenstack.aspect;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.domain.dto.OperationlogMessage;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.enums.OperationStatusEnum;
import com.zcx.chenstack.domain.enums.OperationTypeEnum;
import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.rabbitmq.OperationlogProducer;
import com.zcx.chenstack.utils.IpUtils;
import com.zcx.chenstack.utils.SecurityUtils;
import com.zcx.chenstack.utils.WebUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 操作日志切面
 * 拦截带有 @OperationLog 注解的方法，记录 admin 和 viewer 角色的操作日志
 *
 * @author zcx
 * @since 2025-07-08
 */
@Aspect
@Component
@Slf4j
public class OperationLogAspect {

    @Resource
    private OperationlogProducer operationlogProducer;

    @Resource
    private IpUtils ipUtils;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 需要过滤的敏感字段名
     */
    private static final Set<String> SENSITIVE_FIELDS = new HashSet<>() {{
        add("password");
        add("token");
        add("secret");
        add("key");
        add("accessToken");
        add("refreshToken");
    }};

    /**
     * 环绕通知，拦截带有 @OperationLog 注解的方法
     */
    @Around("@annotation(com.zcx.chenstack.aspect.OperationLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 获取注解信息
        OperationLog operationLog = method.getAnnotation(OperationLog.class);
        if (operationLog == null) {
            return joinPoint.proceed();
        }

        // 获取当前登录用户
        SysUser currentUser = null;
        try {
            currentUser = SecurityUtils.getUser();
        } catch (Exception e) {
            log.warn("获取当前登录用户失败，跳过操作日志记录", e);
            return joinPoint.proceed();
        }

        if (currentUser == null) {
            // 未登录，不记录
            return joinPoint.proceed();
        }

        List<?> userRoles = Collections.emptyList();
        try {
            userRoles = currentUser.getSysRoles() == null ? Collections.emptyList() : currentUser.getSysRoles();
        } catch (Exception e) {
            log.warn("获取当前用户角色失败，跳过操作日志记录，userId: {}", currentUser.getId(), e);
            return joinPoint.proceed();
        }

        // 只记录 admin 和 viewer 角色的操作
        boolean isAdminOrViewer;
        try {
            isAdminOrViewer = userRoles.stream()
                    .filter(com.zcx.chenstack.domain.entity.SysRole.class::isInstance)
                    .map(com.zcx.chenstack.domain.entity.SysRole.class::cast)
                    .anyMatch(r -> "admin".equals(r.getRole()) || "viewer".equals(r.getRole()));
        } catch (Exception e) {
            log.warn("判断当前用户角色失败，跳过操作日志记录，userId: {}", currentUser.getId(), e);
            return joinPoint.proceed();
        }

        if (!isAdminOrViewer) {
            // 普通用户，不记录操作日志
            return joinPoint.proceed();
        }

        // 确定操作人角色
        String operatorRole = "UNKNOWN";
        try {
            operatorRole = userRoles.stream()
                    .filter(com.zcx.chenstack.domain.entity.SysRole.class::isInstance)
                    .map(com.zcx.chenstack.domain.entity.SysRole.class::cast)
                    .filter(r -> "admin".equals(r.getRole()) || "viewer".equals(r.getRole()))
                    .findFirst()
                    .map(r -> r.getRole())
                    .orElse("admin");
        } catch (Exception e) {
            log.warn("获取操作人角色失败，userId: {}", currentUser.getId(), e);
        }

        // 构建操作日志消息体
        OperationlogMessage message = new OperationlogMessage();

        // 优先使用 type 枚举，如果为 OTHER 则使用 operation 字符串
        String operation = operationLog.operation();
        if (operationLog.type() != OperationTypeEnum.OTHER) {
            operation = operationLog.type().getDescription();
        } else if (StrUtil.isBlank(operation)) {
            operation = operationLog.type().getDescription();
        }

        message.setModule(operationLog.module())
                .setDescription(operationLog.description())
                .setOperation(operation)
                .setMethod(signature.getDeclaringType().getName() + ":" + signature.getName())
                .setRequestMethod(getRequestMethod())
                .setOperatorId(currentUser.getId())
                .setOperatorName(StrUtil.blankToDefault(currentUser.getUsername(), "UNKNOWN"))
                .setOperatorRole(operatorRole)
                .setRequestUrl("")
                .setIp("UNKNOWN")
                .setAddress("UNKNOWN");

        try {
            message.setRequestUrl(StrUtil.blankToDefault(WebUtils.getRequestUrl(), "UNKNOWN"));
        } catch (Exception e) {
            log.warn("获取请求URL失败", e);
        }

        try {
            message.setIp(StrUtil.blankToDefault(ipUtils.getIp(), "UNKNOWN"));
        } catch (Exception e) {
            log.warn("获取请求IP失败", e);
        }

        try {
            message.setAddress(StrUtil.blankToDefault(ipUtils.getAddress(), "UNKNOWN"));
        } catch (Exception e) {
            log.warn("获取请求归属地失败", e);
        }

        // 记录请求参数（过滤敏感信息）
        try {
            String requestParam = filterSensitiveParams(joinPoint.getArgs());
            message.setRequestParam(requestParam);
        } catch (Exception e) {
            log.warn("记录请求参数失败", e);
            message.setRequestParam("记录失败");
        }

        long startTime = System.currentTimeMillis();
        Object result = null;
        Integer status = OperationStatusEnum.SUCCESS.getCode();
        String exceptionMsg = null;

        try {
            // 执行目标方法
            result = joinPoint.proceed();
            if (result instanceof Result<?> resultWrapper
                    && resultWrapper.getCode() != null
                    && !Integer.valueOf(200).equals(resultWrapper.getCode())) {
                status = OperationStatusEnum.FAILURE.getCode();
                String resultMsg = StrUtil.isBlank(resultWrapper.getMsg()) ? "业务处理失败" : resultWrapper.getMsg();
                exceptionMsg = StrUtil.sub(resultMsg, 0, 2000);
            }
        } catch (Throwable e) {
            // 记录异常
            status = OperationStatusEnum.ERROR.getCode();
            String errorMessage = StrUtil.isBlank(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
            exceptionMsg = StrUtil.sub(errorMessage, 0, 2000);
            throw e;
        } finally {
            // 计算执行时间
            long time = System.currentTimeMillis() - startTime;
            message.setTime(time);
            message.setStatus(status);
            message.setException(exceptionMsg);

            // 记录响应结果（过滤大数据）
            try {
                String responseResult = objectMapper.writeValueAsString(result);
                if (StrUtil.length(responseResult) > 2000) {
                    responseResult = StrUtil.sub(responseResult, 0, 2000) + "...（已截断）";
                }
                message.setResponseResult(responseResult);
            } catch (Exception e) {
                log.warn("记录响应结果失败", e);
                message.setResponseResult("记录失败");
            }

            // 发送到 MQ（异步写入数据库）
            try {
                operationlogProducer.sendOperationlogMessage(message);
            } catch (Exception e) {
                log.error("发送操作日志到 MQ 失败", e);
                // MQ 发送失败不影响主业务
            }
        }

        return result;
    }

    /**
     * 获取请求方法（GET/POST/PUT/DELETE）
     */
    private String getRequestMethod() {
        try {
            return WebUtils.getRequest().getMethod();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * 过滤敏感参数
     */
    private String filterSensitiveParams(Object[] args) throws Exception {
        if (args == null || args.length == 0) {
            return "";
        }

        // 简单实现：直接序列化，然后过滤敏感字段
        // 如需更精细的过滤，可以使用反射遍历对象字段
        String json = objectMapper.writeValueAsString(args);

        // 过滤敏感字段值（简单替换）
        for (String field : SENSITIVE_FIELDS) {
            json = json.replaceAll("(?i)\"?" + field + "\"?\\s*:\\s*\"?[^\"]*\"?", "\"" + field + "\":\"***\"");
        }

        // 超过 800 字符截断
        if (StrUtil.length(json) > 800) {
            return StrUtil.sub(json, 0, 800) + "...（已截断）";
        }

        return json;
    }
}

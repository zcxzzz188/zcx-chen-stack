package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.wiki.WikiProductException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AiRuntimeConfigurationController.class)
public class AiRuntimeConfigurationExceptionHandler {
    @ExceptionHandler(WikiProductException.class)
    public ResponseEntity<Result<Void>> product(WikiProductException error) { return error(error.status(), error.getMessage()); }
    @ExceptionHandler({SecurityException.class, AccessDeniedException.class})
    public ResponseEntity<Result<Void>> denied(RuntimeException ignored) { return error(403, "没有此AI配置操作权限"); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> invalid(Exception ignored) { return error(400, "AI配置请求参数无效"); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> stale(IllegalStateException ignored) { return error(409, "配置已被其他管理员更新，请刷新后重试"); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> unavailable(DataAccessException ignored) { return error(503, "配置服务暂不可用，请稍后重试"); }
    private static ResponseEntity<Result<Void>> error(int status, String message) {
        Result<Void> result = Result.error(message); result.setCode(status);
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(result);
    }
}

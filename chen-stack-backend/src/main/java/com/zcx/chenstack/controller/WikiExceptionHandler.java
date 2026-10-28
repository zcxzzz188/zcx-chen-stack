package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.wiki.WikiProductException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {WikiController.class, WikiAdminController.class})
public class WikiExceptionHandler {
    @ExceptionHandler(WikiProductException.class) public ResponseEntity<Result<Void>> product(WikiProductException error) { return error(error.status(), error.getMessage()); }
    @ExceptionHandler({SecurityException.class, AccessDeniedException.class}) public ResponseEntity<Result<Void>> denied(RuntimeException error) {
        return error(403, "Wiki来源或操作未获授权，请重新核查");
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> invalid(Exception ignored) { return error(400, "Wiki请求参数无效"); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Result<Void>> stale(IllegalStateException ignored) { return error(409, "内容、来源或任务状态已变化，请重新核查"); }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<Void>> database(DataAccessException ignored) { return error(503, "Wiki暂不可用，请稍后重试"); }
    private static ResponseEntity<Result<Void>> error(int status, String message) {
        Result<Void> result = Result.error(message); result.setCode(status);
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(result);
    }
}

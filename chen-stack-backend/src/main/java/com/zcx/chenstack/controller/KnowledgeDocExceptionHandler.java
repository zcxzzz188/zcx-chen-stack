package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.knowledge.KnowledgeDocException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 知识文档通道的异常归一化；不把堆栈或存储路径带回响应。 */
@ConditionalOnProperty(prefix = "chen.knowledge", name = "enabled", havingValue = "true")
@RestControllerAdvice
public class KnowledgeDocExceptionHandler {

    @ExceptionHandler(KnowledgeDocException.class)
    public ResponseEntity<Result<Void>> handle(KnowledgeDocException error) {
        Result<Void> result = Result.error(error.getMessage());
        result.setCode(error.status());
        return ResponseEntity.status(error.status())
                .cacheControl(CacheControl.noStore())
                .header("X-Error-Code", error.code())
                .body(result);
    }
}

package com.agent.coding.mcp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts {@link McpException} into the frontend error contract
 * {@code {"detail": ...}} (see SkillExceptionHandler for the same shape).
 */
@RestControllerAdvice
public class McpExceptionHandler {

    @ExceptionHandler(McpException.class)
    public ResponseEntity<Map<String, Object>> handleMcp(McpException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}

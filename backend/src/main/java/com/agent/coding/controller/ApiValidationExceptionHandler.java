package com.agent.coding.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global validation-error mapping in the FastAPI ({@code 422 + detail[]})
 * shape of the reference contract (openapi.json). Ported from QwenPaw
 * exception_handlers (#7677): rejected inputs that contain non-finite
 * numbers (NaN / Infinity) must still yield a valid, observable 422 body —
 * the raw input is never echoed into the response.
 */
@RestControllerAdvice
public class ApiValidationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiValidationExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException exc) {
        // Malformed JSON — including bare NaN / Infinity literals — lands here.
        return detail422(List.of(Map.of(
                "loc", List.of("body"),
                "msg", "Input should be a valid JSON value",
                "type", "json_invalid")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException exc) {
        List<Map<String, Object>> detail = new ArrayList<>();
        for (var fe : exc.getBindingResult().getFieldErrors()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("loc", List.of("body", fe.getField()));
            item.put("msg", fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage());
            item.put("type", "value_error");
            detail.add(item);
        }
        return detail422(detail);
    }

    private static ResponseEntity<Map<String, Object>> detail422(List<Map<String, Object>> detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", detail);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }
}

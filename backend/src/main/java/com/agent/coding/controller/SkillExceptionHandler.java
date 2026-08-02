package com.agent.coding.controller;

import com.agent.coding.skill.SkillConflictError;
import com.agent.coding.skill.SkillNotFoundException;
import com.agent.coding.skill.SkillScanError;
import com.agent.coding.skill.SkillTagLimitError;
import com.agent.coding.skill.SkillsError;
import com.agent.coding.skill.SkillHubService.SkillConflictErrorDetail;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error response contract for the frontend (see frontend/src/utils/error.ts
 * and frontend/src/utils/scanError.ts):
 *
 * <ul>
 *   <li>Most failures are returned as {@code {"detail": ...}} where detail is
 *       either a string (displayed as-is) or a structured object (e.g.
 *       {@code {"reason": "conflict", "suggested_name": ...}}), matching the
 *       FastAPI {@code HTTPException(detail=...)} shape the frontend parses.</li>
 *   <li>Security-scan failures return the scan payload itself at the top level
 *       with {@code "type": "security_scan_failed"} (status 422), so
 *       {@code tryParseScanError} can surface the findings block.</li>
 * </ul>
 */
@RestControllerAdvice
public class SkillExceptionHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    @ExceptionHandler(SkillScanError.class)
    public ResponseEntity<Map<String, Object>> handleScan(SkillScanError e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "security_scan_failed");
        payload.put("detail", e.getMessage());
        payload.put("skill_name", e.skillName);
        payload.put("max_severity", maxSeverityOf(e.findings));
        payload.put("findings", e.findings);
        return ResponseEntity.unprocessableEntity().body(payload);
    }

    @ExceptionHandler(SkillConflictErrorDetail.class)
    public ResponseEntity<Map<String, Object>> handleConflictDetail(SkillConflictErrorDetail e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", e.detail);
        return ResponseEntity.status(409).body(body);
    }

    @ExceptionHandler(SkillConflictError.class)
    public ResponseEntity<Map<String, Object>> handleConflict(SkillConflictError e) {
        Map<String, Object> body = new LinkedHashMap<>();
        // Conflict messages are usually serialized detail maps (payloadJson).
        Object detail = e.getMessage();
        String message = e.getMessage();
        if (message != null && message.startsWith("{")) {
            try {
                Map<String, Object> parsed = MAPPER.readValue(message, MAP_TYPE);
                if (parsed != null) detail = parsed;
            } catch (Exception ignored) {
                // fall through to the raw message
            }
        }
        body.put("detail", detail);
        return ResponseEntity.status(409).body(body);
    }

    @ExceptionHandler(SkillNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(SkillNotFoundException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", e.getMessage());
        return ResponseEntity.status(404).body(body);
    }

    @ExceptionHandler(SkillTagLimitError.class)
    public ResponseEntity<Map<String, Object>> handleTagLimit(SkillTagLimitError e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", e.getMessage());
        return ResponseEntity.unprocessableEntity().body(body);
    }

    @ExceptionHandler(SkillsError.class)
    public ResponseEntity<Map<String, Object>> handleSkillsError(SkillsError e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    private String maxSeverityOf(java.util.List<Map<String, Object>> findings) {
        for (Map<String, Object> f : findings) {
            if ("CRITICAL".equalsIgnoreCase(String.valueOf(f.get("severity")))) return "critical";
        }
        return "high";
    }
}

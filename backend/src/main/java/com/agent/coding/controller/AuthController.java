package com.agent.coding.controller;

import com.agent.coding.dto.AuthStatusResponse;
import com.agent.coding.skill.SkillStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single-user authentication,+ app/routers/auth.py.
 *
 * <p>Auth is disabled by default (env {@code MAJO_AUTH_ENABLED} unset): the
 * frontend AuthGuard sees {@code enabled=false} and skips login. When enabled,
 * one account is stored as a salted SHA-256 hash in {@code WORKING_DIR/auth.json}
 * with JWT-style bearer tokens (default 7-day expiry).
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final long TOKEN_EXPIRY_SECONDS = 7 * 24 * 3600L;

    private Path authFile() {
        return SkillStore.WORKING_DIR.resolve("auth.json");
    }

    private boolean isAuthEnabled() {
        String flag = System.getenv("MAJO_AUTH_ENABLED");
        if (flag == null || flag.isBlank()) {
            return false;
        }
        return flag.trim().equalsIgnoreCase("true") || flag.trim().equals("1")
                || flag.trim().equalsIgnoreCase("yes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadAuth() {
        Path file = authFile();
        if (Files.isRegularFile(file)) {
            return SkillStore.readJson(file, Map.of());
        }
        return new LinkedHashMap<>();
    }

    private void saveAuth(Map<String, Object> data) {
        SkillStore.writeJsonAtomic(authFile(), data);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hashPassword(String password, String salt) {
        return sha256(salt + ":" + password);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean tokenMatches(String stored, String presented) {
        return stored != null && !stored.isEmpty() && stored.equals(presented);
    }

    @GetMapping("/status")
    public AuthStatusResponse status() {
        Map<String, Object> auth = loadAuth();
        boolean hasUsers = !auth.isEmpty() && auth.get("username") != null;
        return new AuthStatusResponse(isAuthEnabled(), hasUsers);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        if (!isAuthEnabled()) {
            return ResponseEntity.ok(Map.of("token", "", "username", ""));
        }
        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        String password = String.valueOf(body.getOrDefault("password", ""));
        Map<String, Object> auth = loadAuth();
        String storedUsername = String.valueOf(auth.getOrDefault("username", ""));
        String storedSalt = String.valueOf(auth.getOrDefault("salt", ""));
        String storedHash = String.valueOf(auth.getOrDefault("password_hash", ""));

        if (!storedUsername.equals(username)
                || !hashPassword(password, storedSalt).equals(storedHash)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Invalid username or password"));
        }
        String token = randomToken();
        long expiresAt = Instant.now().getEpochSecond() + TOKEN_EXPIRY_SECONDS;
        Map<String, Object> tokens = new LinkedHashMap<>();
        Object existing = auth.get("tokens");
        if (existing instanceof Map<?, ?> m) {
            tokens.putAll((Map<String, Object>) m);
        }
        tokens.put(token, expiresAt);
        auth.put("tokens", tokens);
        saveAuth(auth);
        log.info("[auth] login success: {}", username);
        return ResponseEntity.ok(Map.of("token", token, "username", username));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, Object> body) {
        if (!isAuthEnabled()) {
            return ResponseEntity.ok(Map.of("token", "", "username", ""));
        }
        Map<String, Object> auth = loadAuth();
        if (auth.get("username") != null && !String.valueOf(auth.get("username")).isBlank()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("detail", "User already registered"));
        }
        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        String password = String.valueOf(body.getOrDefault("password", ""));
        if (username.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "username and password are required"));
        }
        byte[] saltBytes = new byte[16];
        new SecureRandom().nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);
        String token = randomToken();
        auth.put("username", username);
        auth.put("salt", salt);
        auth.put("password_hash", hashPassword(password, salt));
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put(token, Instant.now().getEpochSecond() + TOKEN_EXPIRY_SECONDS);
        auth.put("tokens", tokens);
        saveAuth(auth);
        log.info("[auth] registered user: {}", username);
        return ResponseEntity.ok(Map.of("token", token, "username", username));
    }

    @GetMapping("/verify")
    public ResponseEntity<?> verify(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!isAuthEnabled()) {
            return ResponseEntity.ok(Map.of("valid", true));
        }
        String token = bearerToken(authorization);
        if (token == null) {
            return ResponseEntity.ok(Map.of("valid", false));
        }
        Map<String, Object> auth = loadAuth();
        Object tokens = auth.get("tokens");
        if (tokens instanceof Map<?, ?> m && ((Map<?, ?>) m).containsKey(token)) {
            return ResponseEntity.ok(Map.of("valid", true));
        }
        return ResponseEntity.ok(Map.of("valid", false));
    }

    @PostMapping("/revoke-token")
    public ResponseEntity<?> revokeToken(@RequestBody Map<String, Object> body) {
        String token = String.valueOf(body.getOrDefault("token", ""));
        Map<String, Object> auth = loadAuth();
        Object tokens = auth.get("tokens");
        if (tokens instanceof Map<?, ?> m) {
            ((Map<Object, Object>) m).remove(token);
            saveAuth(auth);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/revoke-all-tokens")
    public ResponseEntity<?> revokeAllTokens() {
        Map<String, Object> auth = loadAuth();
        auth.put("tokens", new LinkedHashMap<>());
        saveAuth(auth);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/update-profile")
    public ResponseEntity<?> updateProfile(@RequestBody Map<String, Object> body,
                                           @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!isAuthEnabled()) {
            return ResponseEntity.ok(Map.of("token", "", "username", ""));
        }
        String token = bearerToken(authorization);
        Map<String, Object> auth = loadAuth();
        Object tokens = auth.get("tokens");
        if (token == null || !(tokens instanceof Map<?, ?> m) || !m.containsKey(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Not authenticated"));
        }
        String currentPassword = String.valueOf(body.getOrDefault("current_password", ""));
        String storedSalt = String.valueOf(auth.getOrDefault("salt", ""));
        String storedHash = String.valueOf(auth.getOrDefault("password_hash", ""));
        if (!hashPassword(currentPassword, storedSalt).equals(storedHash)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Current password is incorrect"));
        }
        String newUsername = body.get("new_username") == null ? null : String.valueOf(body.get("new_username"));
        String newPassword = body.get("new_password") == null ? null : String.valueOf(body.get("new_password"));
        String username = String.valueOf(auth.getOrDefault("username", ""));
        if (newUsername != null && !newUsername.isBlank()) {
            username = newUsername.trim();
            auth.put("username", username);
        }
        if (newPassword != null && !newPassword.isBlank()) {
            byte[] saltBytes = new byte[16];
            new SecureRandom().nextBytes(saltBytes);
            String salt = Base64.getEncoder().encodeToString(saltBytes);
            auth.put("salt", salt);
            auth.put("password_hash", hashPassword(newPassword, salt));
        }
        saveAuth(auth);
        String newToken = randomToken();
        Map<String, Object> newTokens = new LinkedHashMap<>();
        newTokens.put(newToken, Instant.now().getEpochSecond() + TOKEN_EXPIRY_SECONDS);
        auth.put("tokens", newTokens);
        saveAuth(auth);
        return ResponseEntity.ok(Map.of("token", newToken, "username", username));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}

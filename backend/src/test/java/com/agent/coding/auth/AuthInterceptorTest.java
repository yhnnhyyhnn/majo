package com.agent.coding.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auth interceptor behavior when auth is disabled (the default, matching
 * qwenpaw): every request passes through without a token check.
 */
class AuthInterceptorTest {

    private final AuthInterceptor interceptor = new AuthInterceptor();

    @Test
    void disabledAuthAllowsAllRequests() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/chat");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, resp, new Object()));
    }

    @Test
    void disabledAuthAllowsProtectedEndpoints() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/console/chat");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, resp, new Object()));
    }
}
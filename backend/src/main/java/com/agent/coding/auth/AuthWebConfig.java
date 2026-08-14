package com.agent.coding.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the auth interceptor for all API routes. The interceptor is a
 * no-op when auth is disabled (default), so this wiring is safe either way.
 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public AuthWebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }
}

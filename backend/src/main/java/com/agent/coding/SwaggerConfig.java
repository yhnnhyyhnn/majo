package com.agent.coding;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    /** Single version source: filtered from the POM via application.yml. */
    @Value("${majo.version:0.3.0}")
    private String appVersion;

    @Bean
    OpenAPI majoOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Majo API")
                .version(appVersion)
                .description("Majo AI Coding Agent Backend"));
    }
}

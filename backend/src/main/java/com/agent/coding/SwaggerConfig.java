package com.agent.coding;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    OpenAPI majoOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Majo API")
                .version("0.1.0")
                .description("Majo AI Coding Agent Backend"));
    }
}

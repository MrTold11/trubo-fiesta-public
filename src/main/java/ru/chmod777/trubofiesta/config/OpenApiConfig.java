package ru.chmod777.trubofiesta.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI truboFiestaOpenApi(@org.springframework.beans.factory.annotation.Value("${spring.application.name}") String name) {
        return new OpenAPI().info(new Info()
                .title(name + " API")
                .version("v1"));
    }
}

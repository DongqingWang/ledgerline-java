package dev.ledgerline.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer wireFormat() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .featuresToDisable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Local demo origins only; no credentialed cross-origin requests.
        registry.addMapping("/**").allowedOrigins("http://localhost:5180", "http://127.0.0.1:5180")
            .allowedMethods("GET", "POST", "OPTIONS");
    }
}

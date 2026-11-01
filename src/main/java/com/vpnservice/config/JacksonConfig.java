package com.vpnservice.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

@Configuration
public class JacksonConfig {

    // В Jackson 3 отсутствующее поле нельзя положить в int/long/boolean (в Jackson 2 туда шёл 0).
    // Без этого запрос без totalGb или панель, не приславшая какое-то поле клиента, ломают весь разбор
    @Bean
    public JsonMapperBuilderCustomizer allowMissingPrimitives() {
        return builder -> builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
}

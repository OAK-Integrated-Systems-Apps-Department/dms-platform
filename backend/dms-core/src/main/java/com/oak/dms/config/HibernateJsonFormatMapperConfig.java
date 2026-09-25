package com.oak.dms.config;

import org.hibernate.cfg.MappingSettings;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the Jackson mapper Hibernate uses for {@code @JdbcTypeCode(SqlTypes.JSON)}
 * columns (document_type_schemas.fields, document_metadata.field_values).
 * Both Jackson 2 and Jackson 3 are on the classpath, so Hibernate's own
 * auto-detection would otherwise decide - and neither default reads JSON
 * numbers as {@link java.math.BigDecimal}, which metadata values need to
 * round-trip exactly (0.1 must not come back as 0.1000000000000000055...).
 * Unknown properties are ignored so a JSON element written by a newer
 * release never breaks reads on an older one mid-deploy.
 */
@Configuration
public class HibernateJsonFormatMapperConfig {

    @Bean
    public HibernatePropertiesCustomizer jsonFormatMapperCustomizer() {
        JsonMapper jsonMapper = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return properties -> properties.put(MappingSettings.JSON_FORMAT_MAPPER, new Jackson3JsonFormatMapper(jsonMapper));
    }
}

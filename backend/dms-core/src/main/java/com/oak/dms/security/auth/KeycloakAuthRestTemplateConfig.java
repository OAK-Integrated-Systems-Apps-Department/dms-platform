package com.oak.dms.security.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Short, fixed timeouts here specifically: {@link KeycloakAuthClient} is
 * invoked directly from a public, unauthenticated controller endpoint, so
 * a slow or wedged Keycloak must not be able to tie up dms-core's
 * request-handling threads indefinitely - an easy denial-of-service
 * vector otherwise.
 */
@Configuration
@RequiredArgsConstructor
public class KeycloakAuthRestTemplateConfig {

    private final KeycloakAuthProperties properties;

    @Bean
    public RestTemplate keycloakAuthRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .build();
    }
}

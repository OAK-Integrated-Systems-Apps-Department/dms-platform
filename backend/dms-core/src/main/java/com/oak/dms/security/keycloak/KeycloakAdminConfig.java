package com.oak.dms.security.keycloak;

import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * dms-core authenticates to Keycloak's Admin REST API using
 * its own confidential client (client_credentials grant), scoped in
 * Keycloak with the "manage-users" service account role on the
 * oak-dms realm. This is separate from the resource-server JWT
 * validation used to authenticate incoming API calls.
 */
@Configuration
@RequiredArgsConstructor
public class KeycloakAdminConfig {

    private final KeycloakAdminProperties properties;

    @Bean
    public Keycloak keycloakAdminClient() {
        return KeycloakBuilder.builder()
                .serverUrl(properties.getServerUrl())
                .realm(properties.getRealm())
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .grantType("client_credentials")
                .build();
    }
}

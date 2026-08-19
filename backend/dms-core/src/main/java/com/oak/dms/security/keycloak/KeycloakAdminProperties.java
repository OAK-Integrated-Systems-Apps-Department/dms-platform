package com.oak.dms.security.keycloak;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the keycloak.admin.* configuration keys
 * ({@code application.yml}) used to build the admin API client in
 * {@link KeycloakAdminConfig}.
 */
@Component
@ConfigurationProperties(prefix = "keycloak.admin")
@Getter
@Setter
public class KeycloakAdminProperties {
    private String serverUrl;
    private String realm;
    private String clientId;
    private String clientSecret;
}

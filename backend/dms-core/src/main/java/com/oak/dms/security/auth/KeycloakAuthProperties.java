package com.oak.dms.security.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the keycloak.auth.* configuration keys used by
 * {@link KeycloakAuthClient} to perform the Resource Owner Password
 * Credentials ("direct grant") flow on behalf of the login form.
 * <p>
 * Deliberately separate from {@link com.oak.dms.security.keycloak.KeycloakAdminProperties}:
 * that one authenticates dms-core itself to Keycloak's Admin API as a
 * confidential client (client_credentials); this one identifies the
 * public, secret-less {@code oak-dms-web} client that the end user's own
 * credentials are exchanged under. Mixing the two up would mean either
 * leaking the admin client's secret into a user-facing flow, or trying to
 * use the admin client (which has directAccessGrantsEnabled=false) to log
 * users in.
 */
@Component
@ConfigurationProperties(prefix = "keycloak.auth")
@Getter
@Setter
public class KeycloakAuthProperties {
    private String serverUrl;
    private String realm;
    private String clientId;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;
}

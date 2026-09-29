package com.oak.dms.metadata.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the dms.metadata.* configuration keys controlling the resolved
 * schema cache in {@link SchemaCacheService}. The TTL is a backstop, not the
 * primary invalidation mechanism - see that interface's own doc comment.
 */
@Component
@ConfigurationProperties(prefix = "dms.metadata")
@Getter
@Setter
public class SchemaCacheProperties {

    private long schemaCacheTtlSeconds = 3600;
}

package com.oak.dms.metadata;

import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Shared fixture for metadata-module integration tests: a real Postgres
 * container, migrated by the application's own Flyway scripts - this is
 * what actually exercises the V26 deferred/partial display-order
 * constraints, which H2 cannot faithfully reproduce - plus a real Redis
 * container backing {@code SchemaCacheService}.
 * <p>
 * Tests call service beans directly rather than through MockMvc/HTTP: this
 * module has no existing JWT-authentication test harness, and building one
 * is out of scope for this change. A direct service call exercises the same
 * transactional/caching/constraint behaviour a controller call would,
 * modulo the HTTP/security layer itself.
 * <p>
 * Each test gets its own fresh tenant + user row, since the metadata
 * tables' {@code tenant_id}/{@code created_by} columns carry real foreign
 * keys to {@code tenants}/{@code users}, and a {@link TenantContext} bound
 * to them - mirroring what {@code TenantContextFilter} would populate from
 * a real authenticated request.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractMetadataIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("dms_test")
            .withUsername("dms_test")
            .withPassword("dms_test");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Every test class extending this base points at the same shared
        // container, so each opens its own Hikari pool against it - a small
        // pool plus a more generous acquisition timeout keeps total
        // concurrent connections low without making a slow CI runner flake.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "45000");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    protected UUID tenantId;
    protected UUID userId;

    @BeforeEach
    void setUpTenantContext() {
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into tenants (tenant_id, tenant_name, domain, subscription_tier) values (?, ?, ?, ?)",
                tenantId, "Test Tenant " + tenantId, "test-" + tenantId + ".example.com", "basic");
        jdbcTemplate.update(
                "insert into users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name, username) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                userId, tenantId, UUID.randomUUID(), "test-" + userId + "@example.com", "Test User", "Test", "User",
                "test-" + userId);
        TenantContext.set(tenantId, userId);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }
}

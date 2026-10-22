package com.oak.dms.relationship;

import com.oak.dms.relationship.seed.RelationshipTypeCatalog;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** V107's SQL seed and {@link RelationshipTypeCatalog} (used for new tenants) must list the same ten types. */
class RelationshipTypeSeedParityTest {

    private static final Pattern ROW = Pattern.compile(
            "\\('([A-Z_]+)',\\s*'(\\w+)',\\s*'([^']+)',\\s*'([^']+)',\\s*'(\\w+)',\\s*(true|false),\\s*(\\d+)\\)");

    @Test
    void sqlSeedMatchesJavaCatalog() throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V107__seed_relationship_types_for_existing_tenants.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<RelationshipTypeCatalog.Seed> fromSql = new ArrayList<>();
        Matcher m = ROW.matcher(sql);
        while (m.find()) {
            fromSql.add(new RelationshipTypeCatalog.Seed(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5),
                    Boolean.parseBoolean(m.group(6)), Integer.parseInt(m.group(7))));
        }
        assertThat(fromSql).hasSize(10).containsExactlyElementsOf(RelationshipTypeCatalog.SYSTEM_TYPES);
    }

    @Test
    void catalogFollowsTheBriefsRules() {
        assertThat(RelationshipTypeCatalog.SYSTEM_TYPES).filteredOn(RelationshipTypeCatalog.Seed::preventsCycles)
                .extracting(RelationshipTypeCatalog.Seed::code)
                .containsExactlyInAnyOrder("PARENT_CHILD", "SUPERSEDES", "REPLACES");
        assertThat(RelationshipTypeCatalog.SYSTEM_TYPES).filteredOn(s -> s.directionality().equals("symmetric"))
                .extracting(RelationshipTypeCatalog.Seed::code).containsExactly("RELATED");
    }
}

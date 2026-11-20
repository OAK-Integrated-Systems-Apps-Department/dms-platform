package com.oak.dms.watermark.job;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOC-20: a watermark output key is made of ids only, and cleanup accepts a stored key only when it is the job's own -
 * decided on its path segments, not on a text prefix and not on how the host normalises paths.
 */
class WatermarkOutputKeysTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID job = UUID.randomUUID();
    private final String prefix = tenant + "/tmp/watermarks/" + job + "/";

    private boolean ok(String key) {
        return WatermarkOutputKeys.isInsideJobNamespace(tenant, job, key);
    }

    @Test
    void aNewKey_isTwoIdsAndAFixedWord_andIsAccepted() {
        String key = WatermarkOutputKeys.keyFor(tenant, job);

        assertThat(key).isEqualTo(prefix + "output");
        assertThat(WatermarkOutputKeys.keyFor(tenant, job)).as("the same job always gets the same key").isEqualTo(key);
        assertThat(WatermarkOutputKeys.keyFor(tenant, UUID.randomUUID())).isNotEqualTo(key);
        assertThat(ok(key)).isTrue();
    }

    @Test
    void legitimateLegacyKeys_areAccepted() {
        assertThat(ok(prefix + "report.pdf")).isTrue();
        assertThat(ok(prefix + "weird name with spaces & (parens).pdf")).isTrue();
        assertThat(ok(prefix + "a/b/c.pdf")).as("a nested name written by the old code").isTrue();
        assertThat(ok(prefix + "sub\\dir.pdf")).as("a backslash that is not a traversal").isTrue();
        assertThat(ok(prefix + "a..b.pdf")).as("two dots inside a name are not a parent segment").isTrue();
        assertThat(ok(prefix + "..pdf")).isTrue();
    }

    @Test
    void traversal_isRefused_whateverTheSeparator() {
        assertThat(ok(prefix + "../../x.pdf")).isFalse();
        assertThat(ok(prefix + "..")).isFalse();
        assertThat(ok(prefix + "../x.pdf")).isFalse();
        assertThat(ok(prefix + "a/../../x.pdf")).isFalse();
        assertThat(ok(prefix + "a/b/..")).isFalse();
        assertThat(ok(prefix + "..\\..\\x.pdf")).isFalse();
        assertThat(ok(prefix + "a\\..\\x.pdf")).isFalse();
        assertThat(ok(prefix + "./x.pdf")).isFalse();
        assertThat(ok(prefix + "a/./x.pdf")).isFalse();
    }

    @Test
    void aKeyThatMatchesThePrefixButContainsParentSegments_isRefused() {
        String crossTenant = prefix + "../../../../" + UUID.randomUUID() + "/tmp/watermarks/" + UUID.randomUUID() + "/their.pdf";
        assertThat(crossTenant).startsWith(prefix);
        assertThat(ok(crossTenant)).isFalse();
    }

    @Test
    void keysOutsideTheJobNamespace_areRefused() {
        assertThat(ok(null)).isFalse();
        assertThat(ok("")).isFalse();
        assertThat(ok(prefix)).as("nothing after the prefix").isFalse();
        assertThat(ok(tenant + "/tmp/watermarks/" + UUID.randomUUID() + "/output")).as("another job").isFalse();
        assertThat(ok(UUID.randomUUID() + "/tmp/watermarks/" + job + "/output")).as("another tenant").isFalse();
        assertThat(ok("/" + prefix + "output")).as("absolute").isFalse();
        assertThat(ok("/etc/passwd")).isFalse();
        assertThat(ok("C:\\Windows\\x.pdf")).isFalse();
        assertThat(ok("\\\\server\\share\\x.pdf")).isFalse();
        assertThat(ok(tenant + "/tmp/watermarks/" + job)).as("the directory itself").isFalse();
        assertThat(ok(tenant + "/documents/" + job + "/output")).isFalse();
        assertThat(WatermarkOutputKeys.isInsideJobNamespace(null, job, prefix + "output")).isFalse();
        assertThat(WatermarkOutputKeys.isInsideJobNamespace(tenant, null, prefix + "output")).isFalse();
    }

    @Test
    void emptySegmentsAndControlCharacters_areRefused() {
        assertThat(ok(prefix + "a//b.pdf")).isFalse();
        assertThat(ok(prefix + "a/")).isFalse();
        assertThat(ok(prefix + "/x.pdf")).isFalse();
        assertThat(ok(prefix + "x\u0000.pdf")).isFalse();
        assertThat(ok(prefix + "x\n.pdf")).isFalse();
        assertThat(ok(prefix + "x\u0007y.pdf")).isFalse();
        assertThat(ok(prefix + "x\u007f.pdf")).isFalse();
    }

    @Test
    void describe_neverContainsTheKey() {
        String key = prefix + "secret-customer-name.pdf";

        String described = WatermarkOutputKeys.describe(key);

        assertThat(described).doesNotContain("secret").doesNotContain(tenant.toString()).startsWith("length=" + key.length());
        assertThat(WatermarkOutputKeys.describe(null)).isEqualTo("null");
    }
}

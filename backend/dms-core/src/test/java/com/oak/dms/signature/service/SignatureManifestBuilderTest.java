package com.oak.dms.signature.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.signature.enums.SignatureMeaning;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The manifest is what gets signed - its text must be exact, deterministic and carry every field decision 3 requires. */
class SignatureManifestBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SignatureManifestBuilder builder = new SignatureManifestBuilder(mapper);

    private SignatureManifestBuilder.ManifestInput input(String comment, UUID taskId) {
        return new SignatureManifestBuilder.ManifestInput(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                3,
                "abc123",
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "Sign Er",
                "signer@test.example",
                SignatureMeaning.approved,
                comment,
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                taskId,
                OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC));
    }

    @Test
    void containsEveryRequiredField() throws Exception {
        String text = builder.build(input("Looks good", UUID.fromString("66666666-6666-6666-6666-666666666666")));
        JsonNode node = mapper.readTree(text);

        assertThat(node.path("format").asText()).isEqualTo("dms-signature-manifest-v1");
        assertThat(node.path("tenantId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(node.path("documentId").asText()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(node.path("versionId").asText()).isEqualTo("33333333-3333-3333-3333-333333333333");
        assertThat(node.path("versionNumber").asInt()).isEqualTo(3);
        assertThat(node.path("fileSha256").asText()).isEqualTo("abc123");
        assertThat(node.path("signer").path("id").asText()).isEqualTo("44444444-4444-4444-4444-444444444444");
        assertThat(node.path("signer").path("name").asText()).isEqualTo("Sign Er");
        assertThat(node.path("signer").path("email").asText()).isEqualTo("signer@test.example");
        assertThat(node.path("meaning").asText()).isEqualTo("approved");
        assertThat(node.path("comment").asText()).isEqualTo("Looks good");
        assertThat(node.path("workflowInstanceId").asText()).isEqualTo("55555555-5555-5555-5555-555555555555");
        assertThat(node.path("workflowTaskId").asText()).isEqualTo("66666666-6666-6666-6666-666666666666");
        assertThat(node.path("signedAt").asText()).contains("2026-01-02T03:04:05");
    }

    @Test
    void nullCommentIsWrittenAsNull() throws Exception {
        String text = builder.build(input(null, UUID.randomUUID()));
        JsonNode node = mapper.readTree(text);
        assertThat(node.path("comment").isNull()).isTrue();
    }

    @Test
    void sameInputProducesByteForByteIdenticalText() {
        SignatureManifestBuilder.ManifestInput in = input("same", UUID.fromString("66666666-6666-6666-6666-666666666666"));
        assertThat(builder.build(in)).isEqualTo(builder.build(in));
    }

    @Test
    void changingAnyFieldChangesTheText() {
        UUID taskId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        String original = builder.build(input("same", taskId));
        String differentComment = builder.build(input("different", taskId));
        assertThat(original).isNotEqualTo(differentComment);
    }
}

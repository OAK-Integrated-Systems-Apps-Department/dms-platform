package com.oak.dms.watermark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writes the OpenAPI document to {@code docs/api/} with watermarking
 * SWITCHED ON.
 *
 * <h2>Why the flag matters here</h2>
 * {@code WatermarkController} and {@code WatermarkTemplateController} are
 * conditional on {@code dms.watermark.enabled}. Generated with the flag at its
 * default the specification simply would not contain any of the watermark
 * endpoints - and the whole point of checking a spec in is that a reader can
 * see what the API offers. So this test sets the flag explicitly rather than
 * relying on however the generating environment happens to be configured.
 *
 * <h2>Why it is gated</h2>
 * It writes into the repository, which no ordinary test should do - a CI run
 * that modifies the working tree makes every later "is anything uncommitted?"
 * check meaningless. It runs only when {@code WATERMARK_OPENAPI_DIR} names
 * where to write.
 */
@Tag("docs")
@EnabledIfEnvironmentVariable(named = "WATERMARK_OPENAPI_DIR", matches = ".+",
        disabledReason = "Spec generation is run by hand - set WATERMARK_OPENAPI_DIR")
@SpringBootTest(properties = "dms.watermark.enabled=true")
class OpenApiSpecWriter {

    @Autowired
    private WebApplicationContext context;

    @Test
    void writeTheSpec() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Pretty-printed and key-sorted, because this file is reviewed in
        // diffs: springdoc's own ordering is not stable between runs, and an
        // unformatted single line would make every change look like the whole
        // file changed.
        ObjectMapper mapper = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
        Object tree = mapper.readValue(json, Object.class);

        Path directory = Path.of(System.getenv("WATERMARK_OPENAPI_DIR"));
        Files.createDirectories(directory);
        Path file = directory.resolve("openapi.json");
        Files.writeString(file, writer.writeValueAsString(tree) + "\n", StandardCharsets.UTF_8);

        System.out.println("=== OpenAPI written to " + file.toAbsolutePath() + " ===");
        System.out.println("    watermark paths: "
                + json.split("\"/api/v1/documents/\\{documentId\\}/watermark\"", -1).length);
    }
}

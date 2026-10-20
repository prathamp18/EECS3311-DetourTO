package ca.yorku.eecs3311.detourto.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

    @TempDir
    Path dir;

    @Test
    void parsesKeyValueLinesCommentsQuotesAndExport() throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                # comment
                AWS_REGION=ca-central-1
                export BEDROCK_MODEL_ID="my-model"
                EMPTY=
                not a pair
                QUOTED='a b c'
                """);
        Map<String, String> parsed = AppConfig.parseEnvFile(env);
        assertEquals("ca-central-1", parsed.get("AWS_REGION"));
        assertEquals("my-model", parsed.get("BEDROCK_MODEL_ID"));
        assertEquals("a b c", parsed.get("QUOTED"));
        assertEquals("", parsed.get("EMPTY"));
        assertFalse(parsed.containsKey("not a pair"));
    }

    @Test
    void defaultsApplyWhenKeysAreMissingOrBlank() {
        AppConfig cfg = AppConfig.of(Map.of("BEDROCK_MODEL_ID", "  "));
        assertTrue(cfg.modelId().isEmpty());
        assertEquals("us-east-1", cfg.awsRegion());
        assertEquals(Path.of(AppConfig.DEFAULT_GTFS_PATH), cfg.gtfsPath());
        assertFalse(cfg.useScriptedLlm());
    }

    @Test
    void missingEnvFileIsNotAnError() {
        AppConfig cfg = AppConfig.load(dir.resolve("does-not-exist.env"));
        assertEquals(AppConfig.DEFAULT_FEED_BASE_URL,
                cfg.getOrDefault("TTC_FEED_BASE_URL_UNUSED_KEY", AppConfig.DEFAULT_FEED_BASE_URL));
    }
}

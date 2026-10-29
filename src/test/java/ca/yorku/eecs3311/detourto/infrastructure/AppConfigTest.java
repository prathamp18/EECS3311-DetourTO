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
                LLM_PROVIDER=gemini
                export GEMINI_MODEL="my-model"
                EMPTY=
                not a pair
                QUOTED='a b c'
                """);
        Map<String, String> parsed = AppConfig.parseEnvFile(env);
        assertEquals("gemini", parsed.get("LLM_PROVIDER"));
        assertEquals("my-model", parsed.get("GEMINI_MODEL"));
        assertEquals("a b c", parsed.get("QUOTED"));
        assertEquals("", parsed.get("EMPTY"));
        assertFalse(parsed.containsKey("not a pair"));
    }

    @Test
    void defaultsApplyWhenKeysAreMissingOrBlank() {
        AppConfig cfg = AppConfig.of(Map.of("GEMINI_API_KEY", "  "));
        assertEquals("gemini", cfg.llmProvider());
        assertTrue(cfg.geminiApiKey().isEmpty());
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

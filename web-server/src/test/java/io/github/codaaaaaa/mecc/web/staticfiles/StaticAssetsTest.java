package io.github.codaaaaaa.mecc.web.staticfiles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StaticAssetsTest {

    @Test
    void loadsFilesListedInIndex() throws IOException {
        StaticAssets assets = StaticAssets.load(source(Map.of(
                "mecc-web/asset-index.txt", "index.html\nassets/app.css\n\n",
                "mecc-web/index.html", "<html>",
                "mecc-web/assets/app.css", "body{}")));

        assertEquals(2, assets.size());
        assertArrayEquals("body{}".getBytes(StandardCharsets.UTF_8), assets.get("assets/app.css").orElseThrow().bytes());
        assertEquals("text/css; charset=utf-8", assets.get("assets/app.css").orElseThrow().contentType());
    }

    @Test
    void missingIndexMeansEmptyBundle() throws IOException {
        assertTrue(StaticAssets.load(source(Map.of())).isEmpty());
    }

    @Test
    void rejectsTraversalAndMissingFiles() {
        assertThrows(IOException.class, () -> StaticAssets.load(source(Map.of(
                "mecc-web/asset-index.txt", "../secret.txt"))));
        assertThrows(IOException.class, () -> StaticAssets.load(source(Map.of(
                "mecc-web/asset-index.txt", "gone.js"))));
    }

    private static StaticAssetSource source(Map<String, String> files) {
        return path -> Optional.ofNullable(files.get(path))
                .map(content -> (InputStream) new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}

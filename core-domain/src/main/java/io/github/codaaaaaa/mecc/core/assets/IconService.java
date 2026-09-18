package io.github.codaaaaaa.mecc.core.assets;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Resource icons (spec section 20). Rendering never touches Minecraft state. */
public interface IconService {

    /**
     * Renders or returns a cached icon.
     *
     * @param iconKey e.g. {@code item/minecraft/iron_ingot} or {@code fluid/minecraft/water}
     * @return empty when no icon can be produced; clients show a placeholder
     */
    CompletionStage<Optional<IconImage>> icon(String iconKey);

    /** Changes whenever installed assets change. */
    String assetVersion();

    /**
     * @param png  PNG bytes; must not be modified
     * @param etag strong entity tag derived from the content hash
     */
    record IconImage(byte[] png, String etag) {
    }
}

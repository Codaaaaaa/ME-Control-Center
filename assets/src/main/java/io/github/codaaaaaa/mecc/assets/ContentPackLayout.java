package io.github.codaaaaaa.mecc.assets;

/**
 * Layout of an ME Control Center content pack (spec section 19), as written by the optional Client Exporter. A content
 * pack is shaped like a resource pack, so it is read as one more asset pack: its language files merge into
 * the translation tables and its icons are found with the ordinary pack priority.
 *
 * <pre>
 * mecc-content-pack.json                        manifest
 * assets/&lt;namespace&gt;/lang/&lt;locale&gt;.json          the client's merged language table for that namespace
 * assets/&lt;namespace&gt;/mecc_icons/item/&lt;path&gt;.png   icon rendered by the game client
 * assets/&lt;namespace&gt;/mecc_icons/fluid/&lt;path&gt;.png
 * </pre>
 *
 * Icons that move in the game (animated textures, the enchantment glint) are animated PNGs; the server
 * passes them to the browser unchanged. The same icon paths work in an admin resource pack, to replace a
 * single icon by hand. Mirrored by the exporter's {@code ContentPackFormat}; the two must agree.
 */
public final class ContentPackLayout {
    public static final int FORMAT_VERSION = 1;
    public static final String MANIFEST = "mecc-content-pack.json";
    public static final String ICON_DIRECTORY = "mecc_icons";

    private ContentPackLayout() {
    }
}

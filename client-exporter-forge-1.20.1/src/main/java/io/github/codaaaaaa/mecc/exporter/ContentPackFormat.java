package io.github.codaaaaaa.mecc.exporter;

/**
 * Layout of an ME Control Center content pack (spec section 19). The pack is shaped like a resource pack so the server
 * reads it with the same machinery as any other asset source:
 *
 * <pre>
 * mecc-content-pack.json                        manifest
 * assets/&lt;namespace&gt;/lang/&lt;locale&gt;.json          the client's merged language table for that namespace
 * assets/&lt;namespace&gt;/mecc_icons/item/&lt;path&gt;.png   rendered item icon, {@link #ICON_SIZE} square
 * assets/&lt;namespace&gt;/mecc_icons/fluid/&lt;path&gt;.png  rendered fluid icon
 * </pre>
 *
 * Mirrored by {@code io.github.codaaaaaa.mecc.assets.ContentPackLayout} on the server; the two must agree.
 */
final class ContentPackFormat {
    static final int VERSION = 1;
    static final String MANIFEST = "mecc-content-pack.json";
    static final String ICON_DIRECTORY = "mecc_icons";
    static final int ICON_SIZE = 64;

    private ContentPackFormat() {
    }

    /** @param iconKey {@code item/<namespace>/<path>} or {@code fluid/<namespace>/<path>} */
    static String iconEntry(String iconKey) {
        String[] parts = iconKey.split("/", 3);
        return "assets/" + parts[1] + "/" + ICON_DIRECTORY + "/" + parts[0] + "/" + parts[2] + ".png";
    }
}

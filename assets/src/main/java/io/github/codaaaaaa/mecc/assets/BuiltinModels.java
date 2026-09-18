package io.github.codaaaaaa.mecc.assets;

import java.util.Map;
import java.util.Optional;

/**
 * Structural definitions of vanilla parent models that modded models commonly inherit from. Written from
 * the documented model format so that icons work on dedicated servers, which do not include vanilla models.
 */
final class BuiltinModels {
    private BuiltinModels() {
    }

    private static final Map<String, ModelResolver.Kind> MARKERS = Map.of(
            "minecraft:builtin/generated", ModelResolver.Kind.GENERATED,
            "minecraft:item/generated", ModelResolver.Kind.GENERATED,
            "minecraft:item/handheld", ModelResolver.Kind.GENERATED,
            "minecraft:item/handheld_rod", ModelResolver.Kind.GENERATED,
            "minecraft:builtin/entity", ModelResolver.Kind.BUILTIN_ENTITY);

    private static final String CUBE = """
            {"parent":"block/block","elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
              "down":{"texture":"#down"},"up":{"texture":"#up"},"north":{"texture":"#north"},
              "south":{"texture":"#south"},"west":{"texture":"#west"},"east":{"texture":"#east"}}}]}
            """;

    private static String cube(String down, String up, String north, String south, String west, String east) {
        return """
                {"parent":"block/cube","textures":{"particle":"%s","down":"%s","up":"%s","north":"%s","south":"%s","west":"%s","east":"%s"}}
                """.formatted(north, down, up, north, south, west, east);
    }

    private static String box(int fromY, int toY, String extra) {
        return """
                {"from":[0,%d,0],"to":[16,%d,16],"faces":{
                  "down":{"texture":"#bottom"},"up":{"texture":"#top"},"north":{"texture":"#side"},
                  "south":{"texture":"#side"},"west":{"texture":"#side"},"east":{"texture":"#side"}}%s}
                """.formatted(fromY, toY, extra);
    }

    private static final Map<String, String> DEFINITIONS = Map.ofEntries(
            Map.entry("minecraft:block/block", "{}"),
            Map.entry("minecraft:block/cube", CUBE),
            Map.entry("minecraft:block/cube_all", cube("#all", "#all", "#all", "#all", "#all", "#all")),
            Map.entry("minecraft:block/cube_mirrored_all", cube("#all", "#all", "#all", "#all", "#all", "#all")),
            Map.entry("minecraft:block/cube_directional", cube("#all", "#all", "#all", "#all", "#all", "#all")),
            Map.entry("minecraft:block/leaves", """
                    {"parent":"block/block","elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                      "down":{"texture":"#all","tintindex":0},"up":{"texture":"#all","tintindex":0},
                      "north":{"texture":"#all","tintindex":0},"south":{"texture":"#all","tintindex":0},
                      "west":{"texture":"#all","tintindex":0},"east":{"texture":"#all","tintindex":0}}}]}
                    """),
            Map.entry("minecraft:block/cube_column", cube("#end", "#end", "#side", "#side", "#side", "#side")),
            Map.entry("minecraft:block/cube_column_horizontal", cube("#side", "#side", "#end", "#end", "#side", "#side")),
            Map.entry("minecraft:block/cube_bottom_top", cube("#bottom", "#top", "#side", "#side", "#side", "#side")),
            Map.entry("minecraft:block/cube_top", cube("#side", "#top", "#side", "#side", "#side", "#side")),
            Map.entry("minecraft:block/orientable", cube("#top", "#top", "#front", "#side", "#side", "#side")),
            Map.entry("minecraft:block/orientable_with_bottom", cube("#bottom", "#top", "#front", "#side", "#side", "#side")),
            Map.entry("minecraft:block/orientable_vertical", cube("#side", "#front", "#side", "#side", "#side", "#side")),
            Map.entry("minecraft:block/slab", "{\"parent\":\"block/block\",\"elements\":[" + box(0, 8, "") + "]}"),
            Map.entry("minecraft:block/slab_top", "{\"parent\":\"block/block\",\"elements\":[" + box(8, 16, "") + "]}"),
            Map.entry("minecraft:block/stairs", """
                    {"parent":"block/block","elements":[%s,
                      {"from":[8,8,0],"to":[16,16,16],"faces":{
                        "up":{"texture":"#top"},"north":{"texture":"#side"},"south":{"texture":"#side"},
                        "west":{"texture":"#side"},"east":{"texture":"#side"}}}]}
                    """.formatted(box(0, 8, ""))));

    /** Terminal model kinds recognized by id alone. */
    static ModelResolver.Kind marker(String modelId) {
        return MARKERS.get(modelId);
    }

    static Optional<String> definition(String modelId) {
        return Optional.ofNullable(DEFINITIONS.get(modelId));
    }
}

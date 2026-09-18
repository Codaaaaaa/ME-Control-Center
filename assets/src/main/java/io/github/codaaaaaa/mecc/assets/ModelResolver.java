package io.github.codaaaaaa.mecc.assets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves Minecraft JSON models through their parent chain (spec section 20, Tier 2).
 *
 * <p>Dedicated servers do not ship vanilla models, yet most modded models inherit from them. Common vanilla
 * parents are therefore defined here structurally; files in asset packs take precedence when present.
 *
 * <p>Models using a Forge custom loader ({@code "loader": "..."}) have no standard shape, but they commonly
 * embed ordinary model definitions inside their own structure - one per variant, per fluid, per part. When
 * the outer definition has nothing to draw, the first embedded definition that does is used instead. That
 * covers a large share of modded machines and containers without knowing any individual loader.
 */
public final class ModelResolver {
    private static final int MAX_DEPTH = 16;
    /** How deep embedded model definitions may themselves embed further definitions. */
    private static final int MAX_NESTING = 3;
    private static final int MAX_EMBEDDED = 8;
    private static final int MAX_EMBEDDED_DEPTH = 5;
    /** Embedded definitions one icon may be built from, so odd assets cannot multiply into a file storm. */
    private static final int MAX_EMBEDDED_TOTAL = 64;
    /** Members of a model definition that are never model definitions themselves. */
    private static final Set<String> NOT_MODELS =
            Set.of("textures", "display", "elements", "faces", "overrides", "groups", "transform");

    public enum Kind {
        /** Flat item built from {@code layer0..n} textures. */
        GENERATED,
        /** Rendered from cuboid elements. */
        ELEMENTS,
        /** Rendered by client code; no static representation. */
        BUILTIN_ENTITY,
        /** No elements and no layers (e.g. a custom model loader). */
        UNKNOWN
    }

    public enum Face {
        DOWN, UP, NORTH, SOUTH, WEST, EAST
    }

    /**
     * @param uv       {@code [u0, v0, u1, v1]} in 0-16 texture space, or {@code null} for the default
     * @param texture  texture reference, e.g. {@code #side}
     * @param rotation 0, 90, 180, or 270
     */
    public record ModelFace(float[] uv, String texture, int rotation, boolean tinted) {
    }

    public record ModelElement(float[] from, float[] to, Map<Face, ModelFace> faces) {
    }

    public record Model(Kind kind, Map<String, String> textures, List<ModelElement> elements) {
        /** Follows {@code #name} references to a texture id, e.g. {@code minecraft:block/stone}. */
        public Optional<String> texture(String reference) {
            String current = reference;
            for (int i = 0; current != null && current.startsWith("#") && i < 10; i++) {
                current = textures.get(current.substring(1));
            }
            return current == null || current.startsWith("#") || current.isBlank() ? Optional.empty() : Optional.of(current);
        }
    }

    private final AssetLibrary library;
    private final ObjectMapper json = new ObjectMapper();

    public ModelResolver(AssetLibrary library) {
        this.library = library;
    }

    /** @param modelId e.g. {@code ae2:item/controller} or {@code block/stone} */
    public Optional<Model> resolve(String modelId) {
        String id = normalize(modelId);
        if (id == null) {
            return Optional.empty();
        }
        Kind marker = BuiltinModels.marker(id);
        if (marker != null) {
            return Optional.of(new Model(marker, Map.of(), List.of()));
        }
        int[] budget = {MAX_EMBEDDED_TOTAL};
        return load(id).map(node -> build(node, Map.of(), 0, budget));
    }

    /**
     * Finds the model of a block whose item has no model of its own, through the block's blockstate file.
     *
     * @param namespace block namespace
     * @param path      block path, e.g. {@code machine/press}
     */
    public Optional<Model> resolveBlockState(String namespace, String path) {
        Optional<byte[]> bytes = library.read(namespace, "blockstates/" + path + ".json");
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = json.readTree(bytes.get());
        } catch (Exception e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        for (JsonNode node : List.of(root.path("variants"), root.path("multipart"))) {
            Optional<Model> model = firstStateModel(node, 0).flatMap(this::resolve);
            if (model.isPresent()) {
                return model;
            }
        }
        return Optional.empty();
    }

    /** First {@code "model": "<id>"} of a blockstate's variant or multipart definition. */
    private static Optional<String> firstStateModel(JsonNode node, int depth) {
        if (node == null || depth > 4) {
            return Optional.empty();
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<String> found = firstStateModel(item, depth + 1);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (!node.isObject()) {
            return Optional.empty();
        }
        JsonNode model = node.get("model");
        if (model != null && model.isTextual()) {
            return Optional.of(model.textValue());
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            Optional<String> found = firstStateModel(entry.getValue(), depth + 1);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Builds a model from a definition and its parent chain.
     *
     * @param inherited textures of the enclosing definition, used only where the chain defines none
     * @param nesting   how many enclosing custom-loader definitions this one is embedded in
     * @param budget    embedded definitions this icon may still be built from, shared by the whole resolve
     */
    private Model build(JsonNode root, Map<String, String> inherited, int nesting, int[] budget) {
        Map<String, String> textures = new LinkedHashMap<>();
        List<ModelElement> elements = null;
        Kind kind = null;
        List<JsonNode> embedded = new ArrayList<>();
        JsonNode model = root;

        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            JsonNode textureNode = model.get("textures");
            if (textureNode != null && textureNode.isObject()) {
                for (Map.Entry<String, JsonNode> entry : textureNode.properties()) {
                    if (entry.getValue().isTextual()) {
                        textures.putIfAbsent(entry.getKey(), entry.getValue().textValue());
                    }
                }
            }
            if (elements == null && model.has("elements")) {
                elements = parseElements(model.get("elements"));
            }
            if (nesting < MAX_NESTING) {
                collectEmbedded(model, embedded, 0);
            }
            JsonNode parent = model.get("parent");
            String next = parent != null && parent.isTextual() ? normalize(parent.textValue()) : null;
            if (next == null) {
                break;
            }
            Kind marker = BuiltinModels.marker(next);
            if (marker != null) {
                kind = marker;
                break;
            }
            Optional<JsonNode> loaded = load(next);
            if (loaded.isEmpty()) {
                break;
            }
            model = loaded.get();
        }

        inherited.forEach(textures::putIfAbsent);
        if (kind == null) {
            kind = elements != null && !elements.isEmpty() ? Kind.ELEMENTS : Kind.UNKNOWN;
        }
        // Declaration order is kept: it is the only hint about which texture a custom loader considers
        // the main one (e.g. "base" before "fluid" for a fluid container).
        Map<String, String> resolved = Collections.unmodifiableMap(new LinkedHashMap<>(textures));
        if (kind == Kind.UNKNOWN) {
            for (JsonNode candidate : embedded) {
                if (--budget[0] < 0) {
                    break;
                }
                Model sub = build(candidate, resolved, nesting + 1, budget);
                if (sub.kind() != Kind.UNKNOWN) {
                    return sub;
                }
            }
        }
        return new Model(kind, resolved, elements == null ? List.of() : List.copyOf(elements));
    }

    /**
     * Collects model definitions embedded in a custom loader's structure, outermost first. A loader may
     * write them inline or name another model file; both forms appear in the wild, sometimes side by side.
     */
    private void collectEmbedded(JsonNode node, List<JsonNode> found, int depth) {
        if (depth > MAX_EMBEDDED_DEPTH) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (found.size() >= MAX_EMBEDDED) {
                return;
            }
            if (NOT_MODELS.contains(entry.getKey())) {
                continue;
            }
            JsonNode value = entry.getValue();
            if (value.isArray()) {
                for (JsonNode item : value) {
                    if (item.isObject()) {
                        collectCandidate(item, found, depth);
                    }
                }
            } else if (value.isObject()) {
                collectCandidate(value, found, depth);
            } else if (value.isTextual() && "model".equals(entry.getKey())) {
                String id = normalize(value.textValue());
                if (id != null && BuiltinModels.marker(id) == null) {
                    load(id).ifPresent(found::add);
                }
            }
        }
    }

    private void collectCandidate(JsonNode node, List<JsonNode> found, int depth) {
        if (node.path("parent").isTextual() || node.path("elements").isArray()) {
            found.add(node);
        } else {
            collectEmbedded(node, found, depth + 1);
        }
    }

    private Optional<JsonNode> load(String modelId) {
        int colon = modelId.indexOf(':');
        String namespace = modelId.substring(0, colon);
        String path = modelId.substring(colon + 1);
        Optional<byte[]> bytes = library.read(namespace, "models/" + path + ".json");
        if (bytes.isPresent()) {
            try {
                JsonNode node = json.readTree(bytes.get());
                if (node != null && node.isObject()) {
                    return Optional.of(node);
                }
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        return BuiltinModels.definition(modelId).map(text -> {
            try {
                return json.readTree(text);
            } catch (Exception e) {
                throw new IllegalStateException("Invalid builtin model " + modelId, e);
            }
        });
    }

    static String normalize(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.indexOf(':') < 0 ? "minecraft:" + lower : lower;
    }

    private static List<ModelElement> parseElements(JsonNode node) {
        List<ModelElement> elements = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return elements;
        }
        for (JsonNode element : node) {
            float[] from = vector(element.get("from"));
            float[] to = vector(element.get("to"));
            JsonNode faces = element.get("faces");
            if (from == null || to == null || faces == null || !faces.isObject()) {
                continue;
            }
            Map<Face, ModelFace> parsed = new EnumMap<>(Face.class);
            for (Map.Entry<String, JsonNode> entry : faces.properties()) {
                Face face;
                try {
                    face = Face.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                JsonNode value = entry.getValue();
                JsonNode texture = value.get("texture");
                if (texture == null || !texture.isTextual()) {
                    continue;
                }
                float[] uv = null;
                JsonNode uvNode = value.get("uv");
                if (uvNode != null && uvNode.isArray() && uvNode.size() == 4) {
                    uv = new float[] {
                            (float) uvNode.get(0).asDouble(), (float) uvNode.get(1).asDouble(),
                            (float) uvNode.get(2).asDouble(), (float) uvNode.get(3).asDouble()};
                }
                int rotation = Math.floorMod(value.path("rotation").asInt(0), 360) / 90 * 90;
                parsed.put(face, new ModelFace(uv, texture.textValue(), rotation, value.has("tintindex")));
            }
            elements.add(new ModelElement(from, to, parsed));
        }
        return elements;
    }

    private static float[] vector(JsonNode node) {
        if (node == null || !node.isArray() || node.size() != 3) {
            return null;
        }
        return new float[] {(float) node.get(0).asDouble(), (float) node.get(1).asDouble(), (float) node.get(2).asDouble()};
    }
}

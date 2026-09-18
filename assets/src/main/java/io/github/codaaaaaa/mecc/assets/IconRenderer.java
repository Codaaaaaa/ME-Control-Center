package io.github.codaaaaaa.mecc.assets;

import io.github.codaaaaaa.mecc.assets.ModelResolver.Face;
import io.github.codaaaaaa.mecc.assets.ModelResolver.Kind;
import io.github.codaaaaaa.mecc.assets.ModelResolver.Model;
import io.github.codaaaaaa.mecc.assets.ModelResolver.ModelElement;
import io.github.codaaaaaa.mecc.assets.ModelResolver.ModelFace;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Renders inventory-style icons from static assets without the Minecraft client (spec section 20, Tier 2).
 * An icon the game client itself rendered, from a content pack or a hand-made resource pack
 * ({@link ContentPackLayout}), always wins (Tier 3); everything below only runs without one.
 *
 * <ul>
 *   <li>Generated items: {@code layer0..n} textures composited flat.</li>
 *   <li>Element models: cuboids drawn in the standard inventory isometric view (top, north on the left,
 *       west on the right), with simple face shading. Element rotations are ignored.</li>
 *   <li>Fluids: the still texture found by common naming conventions.</li>
 * </ul>
 * Animated textures use their first frame. Thread-safe: stateless
 * apart from the thread-safe asset library.
 */
public final class IconRenderer {
    /** Changes the asset version so browsers refetch icons after renderer improvements. */
    static final String RENDERER_VERSION = "4";
    public static final int SIZE = 64;

    /** Texture roles that tend to be the recognizable face of a model, most representative first. */
    private static final List<String> REPRESENTATIVE = List.of(
            "#layer0", "#particle", "#all", "#base", "#texture", "#side", "#front", "#top", "#end", "#cross");
    private static final int MAX_LAYERS = 8;
    /** Animated icons run to a few hundred KiB; anything far beyond that is not an icon. */
    private static final int MAX_CLIENT_RENDERED_BYTES = 4 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int GRASS_TINT = 0x7FB238;
    private static final int WATER_TINT = 0x3F76E4;
    private static final double COS30 = Math.cos(Math.toRadians(30));
    private static final double SIN30 = 0.5;

    private final AssetLibrary library;
    private final ModelResolver models;

    public IconRenderer(AssetLibrary library) {
        this.library = library;
        this.models = new ModelResolver(library);
    }

    /** @param iconKey {@code item/<namespace>/<path>} or {@code fluid/<namespace>/<path>} */
    public Optional<BufferedImage> render(String iconKey) {
        String[] parts = iconKey.split("/", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        return switch (parts[0]) {
            case "item" -> clientRendered(parts[0], parts[1], parts[2]).or(() -> renderItem(parts[1], parts[2]));
            case "fluid" -> clientRendered(parts[0], parts[1], parts[2]).or(() -> renderFluid(parts[1], parts[2]));
            default -> Optional.empty();
        };
    }

    /**
     * The PNG the game client rendered for this icon, exactly as exported. Served as is, because decoding it
     * would drop all but the first frame of an animated PNG.
     */
    public Optional<byte[]> clientRenderedPng(String iconKey) {
        String[] parts = iconKey.split("/", 3);
        if (parts.length != 3 || !(parts[0].equals("item") || parts[0].equals("fluid"))) {
            return Optional.empty();
        }
        return library.read(parts[1], ContentPackLayout.ICON_DIRECTORY + "/" + parts[0] + "/" + parts[2] + ".png")
                .filter(bytes -> bytes.length <= MAX_CLIENT_RENDERED_BYTES && isPng(bytes));
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length > PNG_SIGNATURE.length
                && Arrays.equals(bytes, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length);
    }

    private Optional<BufferedImage> clientRendered(String type, String namespace, String path) {
        return library.read(namespace, ContentPackLayout.ICON_DIRECTORY + "/" + type + "/" + path + ".png")
                .flatMap(IconRenderer::decode)
                .map(image -> flat(List.of(image)));
    }

    private Optional<BufferedImage> renderItem(String namespace, String path) {
        Optional<Model> model = models.resolve(namespace + ":item/" + path);
        if (model.isEmpty()) {
            // Some mods omit item models for block items and rely on the block model.
            model = models.resolve(namespace + ":block/" + path);
        }
        if (model.isEmpty()) {
            // Blocks whose item has no model of its own: go through the blockstate.
            model = models.resolveBlockState(namespace, path);
        }
        if (model.isPresent()) {
            Model resolved = model.get();
            if (resolved.kind() == Kind.GENERATED) {
                Optional<BufferedImage> flat = layers(resolved);
                if (flat.isPresent()) {
                    return flat;
                }
            } else if (resolved.kind() == Kind.ELEMENTS) {
                Optional<BufferedImage> iso = isometric(resolved);
                if (iso.isPresent()) {
                    return iso;
                }
            }
            // Client-rendered items (chests, beds), custom loaders and incomplete models: fall back to the
            // most representative texture the model names, then to any texture it names at all. That last
            // step is what covers loaders this renderer knows nothing about.
            Optional<BufferedImage> named = firstVisible(resolved, REPRESENTATIVE);
            if (named.isPresent()) {
                return named.map(image -> flat(List.of(image)));
            }
            Optional<BufferedImage> any = firstVisible(resolved,
                    resolved.textures().keySet().stream().map(name -> "#" + name).toList());
            if (any.isPresent()) {
                return any.map(image -> flat(List.of(image)));
            }
        }
        // No usable model at all: mods that ship only a texture under the item's own name.
        for (String candidate : List.of(namespace + ":item/" + path, namespace + ":items/" + path,
                namespace + ":block/" + path)) {
            Optional<BufferedImage> image = texture(candidate);
            if (image.isPresent()) {
                return Optional.of(flat(List.of(image.get())));
            }
        }
        return Optional.empty();
    }

    /**
     * First of the referenced textures that exists and has at least one non-transparent pixel. Custom
     * loaders often name blank placeholder textures for overlays they fill in at runtime; picking one of
     * those would produce an empty icon.
     */
    private Optional<BufferedImage> firstVisible(Model model, List<String> references) {
        for (String reference : references) {
            Optional<BufferedImage> image = model.texture(reference).flatMap(this::texture)
                    .filter(IconRenderer::hasVisiblePixels);
            if (image.isPresent()) {
                return image;
            }
        }
        return Optional.empty();
    }

    private static boolean hasVisiblePixels(BufferedImage image) {
        // Icon textures are small; large ones are sampled so this stays cheap.
        int stride = Math.max(1, (int) Math.sqrt((double) image.getWidth() * image.getHeight() / 4096));
        for (int y = 0; y < image.getHeight(); y += stride) {
            for (int x = 0; x < image.getWidth(); x += stride) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private Optional<BufferedImage> renderFluid(String namespace, String path) {
        List<String> candidates = List.of(
                namespace + ":block/" + path + "_still",
                namespace + ":block/fluid/" + path + "_still",
                namespace + ":block/fluids/" + path + "_still",
                namespace + ":fluid/" + path + "_still",
                namespace + ":block/" + path,
                namespace + ":block/fluid/" + path);
        for (String candidate : candidates) {
            Optional<BufferedImage> texture = texture(candidate);
            if (texture.isPresent()) {
                BufferedImage image = texture.get();
                if (namespace.equals("minecraft") && path.contains("water")) {
                    image = tint(image, WATER_TINT);
                }
                return Optional.of(flat(List.of(image)));
            }
        }
        return Optional.empty();
    }

    private Optional<BufferedImage> layers(Model model) {
        List<BufferedImage> images = new ArrayList<>();
        for (int i = 0; i < MAX_LAYERS; i++) {
            Optional<String> reference = model.texture("#layer" + i);
            if (reference.isEmpty()) {
                break;
            }
            texture(reference.get()).ifPresent(images::add);
        }
        return images.isEmpty() ? Optional.empty() : Optional.of(flat(images));
    }

    private static BufferedImage flat(List<BufferedImage> layers) {
        BufferedImage output = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = output.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setComposite(AlphaComposite.SrcOver);
            for (BufferedImage layer : layers) {
                g.drawImage(layer, 0, 0, SIZE, SIZE, null);
            }
        } finally {
            g.dispose();
        }
        return output;
    }

    // --- isometric element rendering -------------------------------------------------------------

    private Optional<BufferedImage> isometric(Model model) {
        BufferedImage output = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = output.createGraphics();
        boolean drewSomething = false;
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

            List<ModelElement> ordered = new ArrayList<>(model.elements());
            // Painter's algorithm: farther (smaller flipped x+z) and lower elements first.
            ordered.sort(Comparator
                    .comparingDouble((ModelElement e) -> depth(e))
                    .thenComparingDouble(e -> (e.from()[1] + e.to()[1]) / 2));
            View view = View.fit(ordered);
            for (ModelElement element : ordered) {
                drewSomething |= drawFace(g, model, view, element, Face.NORTH, 0.8f);
                drewSomething |= drawFace(g, model, view, element, Face.WEST, 0.6f);
                drewSomething |= drawFace(g, model, view, element, Face.UP, 1.0f);
            }
        } finally {
            g.dispose();
        }
        return drewSomething ? Optional.of(output) : Optional.empty();
    }

    private static double depth(ModelElement element) {
        double x = 16 - (element.from()[0] + element.to()[0]) / 2;
        double z = 16 - (element.from()[2] + element.to()[2]) / 2;
        return x + z;
    }

    private boolean drawFace(Graphics2D g, Model model, View view, ModelElement element, Face face, float brightness) {
        ModelFace modelFace = element.faces().get(face);
        if (modelFace == null) {
            return false;
        }
        Optional<String> textureId = model.texture(modelFace.texture());
        Optional<BufferedImage> loaded = textureId.flatMap(this::texture);
        if (loaded.isEmpty()) {
            return false;
        }
        BufferedImage image = loaded.get();
        // Tint colours come from client code. Vanilla's are foliage green often enough to be worth
        // guessing; a mod's are anything at all, and guessing there paints its machines green.
        if (modelFace.tinted() && ModelResolver.normalize(textureId.get()).startsWith("minecraft:")) {
            image = tint(image, GRASS_TINT);
        }
        image = shade(image, brightness);

        float[] f = element.from();
        float[] t = element.to();
        // Corners as seen from outside the face: top-left, top-right, bottom-right, bottom-left.
        double[][] corners;
        float[] defaultUv;
        switch (face) {
            case UP -> {
                corners = new double[][] {{f[0], t[1], f[2]}, {t[0], t[1], f[2]}, {t[0], t[1], t[2]}, {f[0], t[1], t[2]}};
                defaultUv = new float[] {f[0], f[2], t[0], t[2]};
            }
            case NORTH -> {
                corners = new double[][] {{t[0], t[1], f[2]}, {f[0], t[1], f[2]}, {f[0], f[1], f[2]}, {t[0], f[1], f[2]}};
                defaultUv = new float[] {16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
            }
            case WEST -> {
                corners = new double[][] {{f[0], t[1], f[2]}, {f[0], t[1], t[2]}, {f[0], f[1], t[2]}, {f[0], f[1], f[2]}};
                defaultUv = new float[] {f[2], 16 - t[1], t[2], 16 - f[1]};
            }
            default -> {
                return false;
            }
        }
        float[] uv = modelFace.uv() != null ? modelFace.uv() : defaultUv;

        Point2D[] screen = new Point2D[4];
        for (int i = 0; i < 4; i++) {
            screen[i] = view.toScreen(project(corners[i]));
        }
        double scaleU = image.getWidth() / 16.0;
        double scaleV = image.getHeight() / 16.0;
        double u0 = uv[0] * scaleU;
        double v0 = uv[1] * scaleV;
        double u1 = uv[2] * scaleU;
        double v1 = uv[3] * scaleV;
        if (u0 == u1 || v0 == v1) {
            return false;
        }
        // Texture corners in the same order; face rotation shifts which screen corner each lands on.
        int shift = modelFace.rotation() / 90;
        Point2D q0 = screen[shift % 4];
        Point2D q1 = screen[(1 + shift) % 4];
        Point2D q3 = screen[(3 + shift) % 4];
        double m00 = (q1.getX() - q0.getX()) / (u1 - u0);
        double m10 = (q1.getY() - q0.getY()) / (u1 - u0);
        double m01 = (q3.getX() - q0.getX()) / (v1 - v0);
        double m11 = (q3.getY() - q0.getY()) / (v1 - v0);
        double tx = q0.getX() - m00 * u0 - m01 * v0;
        double ty = q0.getY() - m10 * u0 - m11 * v0;
        AffineTransform transform = new AffineTransform(m00, m10, m01, m11, tx, ty);

        Path2D.Double clip = new Path2D.Double();
        clip.moveTo(screen[0].getX(), screen[0].getY());
        for (int i = 1; i < 4; i++) {
            clip.lineTo(screen[i].getX(), screen[i].getY());
        }
        clip.closePath();
        g.setClip(clip);
        g.drawImage(image, transform, null);
        g.setClip(null);
        return true;
    }

    /**
     * Inventory view in model units: model flipped around Y so north faces left, then an orthographic
     * 30° projection. A full block spans about 27.7 x 32 units.
     */
    private static Point2D project(double[] point) {
        double x = 16 - point[0];
        double y = point[1];
        double z = 16 - point[2];
        return new Point2D.Double((x - z) * COS30, (x + z) * SIN30 - y);
    }

    /**
     * Maps projected model units to image pixels. Full blocks keep the standard inventory size; smaller
     * models are enlarged (up to a limit) and centered, as the game's per-model GUI transforms would do.
     */
    private record View(double scale, double offsetX, double offsetY) {
        private static final double FULL_BLOCK_SCALE = SIZE / 33.0;
        private static final double MAX_ENLARGEMENT = 2.5;
        private static final double FULL_BLOCK_WIDTH = 32 * COS30;
        private static final double FULL_BLOCK_HEIGHT = 32;

        static View fit(List<ModelElement> elements) {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (ModelElement element : elements) {
                float[] f = element.from();
                float[] t = element.to();
                for (int corner = 0; corner < 8; corner++) {
                    Point2D p = project(new double[] {
                            (corner & 1) == 0 ? f[0] : t[0], (corner & 2) == 0 ? f[1] : t[1], (corner & 4) == 0 ? f[2] : t[2]});
                    minX = Math.min(minX, p.getX());
                    maxX = Math.max(maxX, p.getX());
                    minY = Math.min(minY, p.getY());
                    maxY = Math.max(maxY, p.getY());
                }
            }
            if (minX > maxX) {
                return new View(FULL_BLOCK_SCALE, SIZE / 2.0, SIZE / 2.0);
            }
            double width = Math.max(1e-3, maxX - minX);
            double height = Math.max(1e-3, maxY - minY);
            // Models that are block-sized in either dimension (slabs, stairs, fences) keep block scale and
            // position, like in the game's inventory.
            if (width >= FULL_BLOCK_WIDTH * 0.75 || height >= FULL_BLOCK_HEIGHT * 0.75) {
                return new View(FULL_BLOCK_SCALE, SIZE / 2.0, SIZE / 2.0);
            }
            double fit = Math.min(SIZE * 0.9 / width, SIZE * 0.9 / height);
            double scale = Math.min(fit, FULL_BLOCK_SCALE * MAX_ENLARGEMENT);
            double centerX = (minX + maxX) / 2;
            double centerY = (minY + maxY) / 2;
            return new View(scale, SIZE / 2.0 - centerX * scale, SIZE / 2.0 - centerY * scale);
        }

        Point2D toScreen(Point2D modelPoint) {
            return new Point2D.Double(offsetX + modelPoint.getX() * scale, offsetY + modelPoint.getY() * scale);
        }
    }

    // --- textures ----------------------------------------------------------------------------------

    private Optional<BufferedImage> texture(String textureId) {
        String id = ModelResolver.normalize(textureId);
        if (id == null) {
            return Optional.empty();
        }
        int colon = id.indexOf(':');
        return library.read(id.substring(0, colon), "textures/" + id.substring(colon + 1) + ".png")
                .flatMap(IconRenderer::decode);
    }

    private static Optional<BufferedImage> decode(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() <= 0 || image.getWidth() > 4096 || image.getHeight() > 65536) {
                return Optional.empty();
            }
            int width = image.getWidth();
            // Animation strips stack frames vertically: keep the first square frame.
            int height = image.getHeight() > width && image.getHeight() % width == 0 ? width : image.getHeight();
            BufferedImage argb = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            try {
                g.drawImage(image, 0, 0, width, height, 0, 0, width, height, null);
            } finally {
                g.dispose();
            }
            return Optional.of(argb);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static BufferedImage shade(BufferedImage image, float brightness) {
        if (brightness >= 0.999f) {
            return image;
        }
        return mapPixels(image, argb -> {
            int a = argb >>> 24;
            int r = Math.round(((argb >> 16) & 0xFF) * brightness);
            int gr = Math.round(((argb >> 8) & 0xFF) * brightness);
            int b = Math.round((argb & 0xFF) * brightness);
            return (a << 24) | (r << 16) | (gr << 8) | b;
        });
    }

    private static BufferedImage tint(BufferedImage image, int rgb) {
        int tr = (rgb >> 16) & 0xFF;
        int tg = (rgb >> 8) & 0xFF;
        int tb = rgb & 0xFF;
        return mapPixels(image, argb -> {
            int a = argb >>> 24;
            int r = ((argb >> 16) & 0xFF) * tr / 255;
            int gr = ((argb >> 8) & 0xFF) * tg / 255;
            int b = (argb & 0xFF) * tb / 255;
            return (a << 24) | (r << 16) | (gr << 8) | b;
        });
    }

    private interface PixelMapper {
        int map(int argb);
    }

    private static BufferedImage mapPixels(BufferedImage image, PixelMapper mapper) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                result.setRGB(x, y, mapper.map(image.getRGB(x, y)));
            }
        }
        return result;
    }
}

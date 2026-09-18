package io.github.codaaaaaa.mecc.exporter;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.codaaaaaa.mecc.exporter.AnimationClip.Motion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Decides which icons move in an inventory and how to record them, so the clip matches the game:
 *
 * <ul>
 *   <li>Animated textures follow their {@code .mcmeta}. Played from their first frame, their picture only
 *       changes at known ticks, so even slow ones (prismarine shifts colour every 15 seconds) are recorded
 *       for their whole period, drawn only when a frame changes, and loop exactly.</li>
 *   <li>The enchantment glint, and items drawn by custom renderers, may change on any tick: they are drawn
 *       every tick for a few seconds and cross-faded into a loop.</li>
 * </ul>
 *
 * Which textures an item shows is found by drawing it once into buffers that keep nothing but texture
 * coordinates, then looking up the animated sprite under each face. That sees what the item really draws,
 * however its mod builds the model (GregTech's machine models, for one, do not list their overlays anywhere
 * else).
 *
 * <p>Render thread only.
 */
final class AnimationProbe {
    /** Longest exact loop of plain texture animations, which are drawn only when a frame changes. */
    static final int MAX_EXACT_LOOP_TICKS = 6000;
    /** Longest clip drawn on every tick; longer ones are cut there and cross-faded. */
    static final int MAX_LOOP_TICKS = 200;
    /**
     * The glint scrolls on two axes with periods of 27.5 s and 7.5 s (at the default glint speed), so it only
     * truly repeats after 82.5 s. Six seconds with a cross-fade looks the same and stays small.
     */
    static final int GLINT_LOOP_TICKS = 120;
    static final int CROSSFADE_TICKS = 20;
    /** How long a custom renderer is watched for movement. */
    static final int CUSTOM_RENDERER_TICKS = 60;
    private static final int CELLS = 256;

    /** An animated sprite an icon shows, and its timeline. */
    record SpriteAnimation(TextureAtlasSprite sprite, SpriteTiming timing) {
    }

    /**
     * @param motion     how to record the icon
     * @param animations the animated sprites it shows; the recording plays them from their first frame
     */
    record Recording(Motion motion, List<SpriteAnimation> animations) {
    }

    private final ResourceManager resources;
    private final Map<ResourceLocation, Optional<SpriteTiming>> timings = new HashMap<>();
    /** Grid cell of {@link #CELLS} per atlas side, to the animated sprites overlapping it. */
    private final Map<Integer, List<SpriteAnimation>> atlasAnimations = new HashMap<>();
    private final List<float[]> faceCentres = new ArrayList<>();
    private final MultiBufferSource.BufferSource sampler;

    AnimationProbe(ResourceManager resources) {
        this.resources = resources;
        AbstractTexture blocks = Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS);
        if (blocks instanceof TextureAtlas atlas) {
            for (ResourceLocation location : atlas.getTextureLocations()) {
                TextureAtlasSprite sprite = atlas.getSprite(location);
                timing(sprite).ifPresent(timing -> index(new SpriteAnimation(sprite, timing)));
            }
        }
        // Every buffer the item asks for records the centre of each face it draws, and draws nothing.
        sampler = new MultiBufferSource.BufferSource(new BufferBuilder(256), Map.of()) {
            @Override
            public VertexConsumer getBuffer(RenderType type) {
                return new FaceCentres(faceCentres);
            }

            @Override
            public void endLastBatch() {
            }

            @Override
            public void endBatch() {
            }

            @Override
            public void endBatch(RenderType type) {
            }
        };
    }

    /** @return how to record the icon, or empty if it looks the same at every moment */
    Optional<Recording> recording(IconSource source) {
        boolean foil = false;
        boolean customRenderer = false;
        Set<SpriteAnimation> animations = new LinkedHashSet<>();
        if (source instanceof IconSource.ItemIcon item) {
            foil = item.stack().hasFoil();
            customRenderer = Minecraft.getInstance().getItemRenderer()
                    .getModel(item.stack(), null, null, 0).isCustomRenderer();
            animations.addAll(sampleAnimations(item));
        } else if (source instanceof IconSource.FluidIcon fluid) {
            fluid.sprite().ifPresent(sprite ->
                    timing(sprite).ifPresent(timing -> animations.add(new SpriteAnimation(sprite, timing))));
        }

        long period = 1;
        boolean interpolated = false;
        for (SpriteAnimation animation : animations) {
            period = Math.min(lcm(period, animation.timing().period()), Integer.MAX_VALUE);
            interpolated |= animation.timing().interpolated();
        }

        Motion motion;
        if (foil || customRenderer) {
            int base = foil ? GLINT_LOOP_TICKS : CUSTOM_RENDERER_TICKS;
            // A whole number of texture periods, so only the time-driven part needs the cross-fade.
            long loop = period > 1 ? period * ceilDiv(base, (int) Math.min(period, base)) : base;
            motion = new Motion(loop <= MAX_LOOP_TICKS ? (int) loop : base, CROSSFADE_TICKS);
        } else if (animations.isEmpty()) {
            return Optional.empty();
        } else if (!interpolated && period <= MAX_EXACT_LOOP_TICKS) {
            motion = new Motion((int) period, 0, changeTicks(animations, (int) period));
        } else if (period <= MAX_LOOP_TICKS) {
            motion = new Motion((int) period, 0);
        } else {
            motion = new Motion(MAX_LOOP_TICKS, CROSSFADE_TICKS);
        }
        return Optional.of(new Recording(motion, List.copyOf(animations)));
    }

    /** The animated block-atlas sprites under the faces the item draws. */
    private Set<SpriteAnimation> sampleAnimations(IconSource.ItemIcon item) {
        Set<SpriteAnimation> found = new LinkedHashSet<>();
        if (atlasAnimations.isEmpty()) {
            return found;
        }
        faceCentres.clear();
        new GuiGraphics(Minecraft.getInstance(), sampler).renderItem(item.stack(), 0, 0);
        for (float[] centre : faceCentres) {
            for (SpriteAnimation animation : atlasAnimations.getOrDefault(cell(centre[0], centre[1]), List.of())) {
                TextureAtlasSprite sprite = animation.sprite();
                if (centre[0] >= sprite.getU0() && centre[0] < sprite.getU1()
                        && centre[1] >= sprite.getV0() && centre[1] < sprite.getV1()) {
                    found.add(animation);
                    break;
                }
            }
        }
        return found;
    }

    private void index(SpriteAnimation animation) {
        TextureAtlasSprite sprite = animation.sprite();
        int left = (int) (sprite.getU0() * CELLS);
        int right = (int) Math.ceil(sprite.getU1() * CELLS);
        int top = (int) (sprite.getV0() * CELLS);
        int bottom = (int) Math.ceil(sprite.getV1() * CELLS);
        for (int x = left; x < right; x++) {
            for (int y = top; y < bottom; y++) {
                atlasAnimations.computeIfAbsent(y * CELLS + x, cell -> new ArrayList<>()).add(animation);
            }
        }
    }

    private static int cell(float u, float v) {
        return (int) (v * CELLS) * CELLS + (int) (u * CELLS);
    }

    /** Every tick of the loop at which any of the animations starts a new frame. */
    private static int[] changeTicks(Set<SpriteAnimation> animations, int loop) {
        TreeSet<Integer> ticks = new TreeSet<>();
        ticks.add(0);
        for (SpriteAnimation animation : animations) {
            int period = animation.timing().period();
            int[] changes = animation.timing().changes();
            for (int start = 0; start < loop; start += period) {
                for (int change : changes) {
                    ticks.add(start + change);
                }
            }
        }
        return ticks.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The sprite's animation, as its {@code .mcmeta} describes it, or empty for a still sprite. */
    private Optional<SpriteTiming> timing(TextureAtlasSprite sprite) {
        int frames = (int) sprite.contents().getUniqueFrames().count();
        if (frames <= 1) {
            return Optional.empty();
        }
        return timings.computeIfAbsent(sprite.contents().name(), name -> {
            ResourceLocation file = new ResourceLocation(name.getNamespace(), "textures/" + name.getPath() + ".png");
            Optional<AnimationMetadataSection> animation = resources.getResource(file).flatMap(resource -> {
                try {
                    return resource.metadata().getSection(AnimationMetadataSection.SERIALIZER);
                } catch (IOException e) {
                    return Optional.empty();
                }
            });
            List<int[]> entries = new ArrayList<>();
            animation.ifPresent(section -> section.forEachFrame((index, time) -> entries.add(new int[] {index, Math.max(1, time)})));
            if (entries.isEmpty()) {
                // Without an explicit frame list every frame shows once, in order, for the default time.
                int time = animation.map(section -> Math.max(1, section.getDefaultFrameTime())).orElse(1);
                for (int i = 0; i < frames; i++) {
                    entries.add(new int[] {i, time});
                }
            }
            return Optional.of(new SpriteTiming(
                    entries.stream().mapToInt(entry -> entry[0]).toArray(),
                    entries.stream().mapToInt(entry -> entry[1]).toArray(),
                    animation.map(AnimationMetadataSection::isInterpolatedFrames).orElse(false)));
        });
    }

    private static long lcm(long a, long b) {
        return a / gcd(a, b) * b;
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** Keeps the texture-coordinate centre of every quad drawn into it, and nothing else. */
    private static final class FaceCentres implements VertexConsumer {
        private final List<float[]> centres;
        private float u;
        private float v;
        private int vertices;

        FaceCentres(List<float[]> centres) {
            this.centres = centres;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.u += u;
            this.v += v;
            return this;
        }

        @Override
        public void endVertex() {
            if (++vertices == 4) {
                centres.add(new float[] {u / 4, v / 4});
                u = 0;
                v = 0;
                vertices = 0;
            }
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }
}

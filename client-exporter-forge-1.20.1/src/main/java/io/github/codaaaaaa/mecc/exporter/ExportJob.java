package io.github.codaaaaaa.mecc.exporter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.github.codaaaaaa.mecc.exporter.AnimationClip.Motion;
import io.github.codaaaaaa.mecc.exporter.AnimationProbe.Recording;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.versions.forge.ForgeVersion;
import org.slf4j.Logger;

/**
 * One export run, in two phases, each advancing at most one batch per client tick so the game stays
 * responsive:
 *
 * <ol>
 *   <li>still icons, 256 to an atlas, drawn once;</li>
 *   <li>moving icons (see {@link AnimationProbe}), 16 to an atlas, recorded tick by tick. A batch is
 *       recorded within a single client tick, stepping its textures' animations and the glint's clock by
 *       hand, so every frame is exactly one game tick after the previous one however fast the game runs.</li>
 * </ol>
 *
 * Encoding and writing happen on the writer's thread. Render thread only.
 */
final class ExportJob {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** Batches waiting to be encoded before rendering pauses; a batch of clips can hold tens of MiB. */
    private static final int MAX_PENDING_BATCHES = 2;
    private static final long NANOS_PER_TICK = 50_000_000L;

    private final Consumer<Component> feedback;
    private final List<String> locales;
    private final List<IconSource> still = new ArrayList<>();
    private final List<IconSource> moving = new ArrayList<>();
    private final List<Recording> recordings = new ArrayList<>();
    private final Map<String, Integer> failuresByNamespace = new TreeMap<>();
    private final String fingerprint;
    private final Path target;
    private final ContentPackWriter writer;
    private final IconAtlasRenderer stillRenderer = new IconAtlasRenderer(16);
    private final IconAtlasRenderer movingRenderer = new IconAtlasRenderer(4);
    private final int total;
    private int nextStill;
    private int nextMoving;
    private int nextProgressReport;
    private boolean finishing;
    private boolean done;

    ExportJob(List<String> locales, Path directory, Consumer<Component> feedback) throws Exception {
        this.feedback = feedback;
        this.locales = List.copyOf(locales);
        classify(collectSources(), new AnimationProbe(Minecraft.getInstance().getResourceManager()));
        this.total = still.size() + moving.size();
        this.fingerprint = fingerprint();
        this.target = directory.resolve("mecc-content-pack-" + fingerprint + ".zip");
        this.writer = new ContentPackWriter(directory);
        this.nextProgressReport = total / 4;
        exportLanguages(Minecraft.getInstance().getResourceManager());
        feedback.accept(Component.translatable("mecc_exporter.started", total, locales.size()));
    }

    boolean isDone() {
        return done;
    }

    /** Advances the export by at most one batch. Call once per client tick. */
    void tick() {
        if (done || finishing) {
            return;
        }
        if (writer.failure() != null) {
            fail(writer.failure());
            return;
        }
        if (nextStill < still.size() || nextMoving < moving.size()) {
            if (writer.pendingBatches() < MAX_PENDING_BATCHES) {
                if (nextStill < still.size()) {
                    renderStillBatch();
                } else {
                    recordMovingBatch();
                }
                reportProgress();
            }
            return;
        }
        finish();
    }

    private void renderStillBatch() {
        List<IconSource> batch = List.copyOf(still.subList(nextStill,
                Math.min(still.size(), nextStill + stillRenderer.batchSize())));
        NativeImage atlas = stillRenderer.render(batch, this::recordFailure, OptionalLong.empty());
        writer.writeIcons(atlas, stillRenderer.columns(), batch);
        nextStill += batch.size();
    }

    private void recordMovingBatch() {
        int end = Math.min(moving.size(), nextMoving + movingRenderer.batchSize());
        List<IconSource> batch = List.copyOf(moving.subList(nextMoving, end));
        List<Motion> motions = recordings.subList(nextMoving, end).stream().map(Recording::motion).toList();
        int ticks = motions.stream().mapToInt(Motion::recordTicks).max().orElse(0);
        List<List<int[]>> recorded = new ArrayList<>();
        batch.forEach(source -> recorded.add(new ArrayList<>()));

        // Each tick, every animated sprite of the batch is set to exactly how it looks that many ticks after its
        // first frame, which is where the probe's schedule starts. The game's own tickers are left alone; the
        // world simply shows those sprites in the recorded state until their next frame.
        Map<TextureAtlasSprite, SpriteAnimator> animators = new LinkedHashMap<>();
        for (Recording recording : recordings.subList(nextMoving, end)) {
            for (AnimationProbe.SpriteAnimation animation : recording.animations()) {
                animators.computeIfAbsent(animation.sprite(), sprite -> new SpriteAnimator(sprite, animation.timing()));
            }
        }
        try {
            long start = Util.getNanos();
            for (int tick = 0; tick < ticks; tick++) {
                int now = tick;
                boolean draw = false;
                for (int i = 0; i < batch.size(); i++) {
                    draw |= now < motions.get(i).recordTicks() && motions.get(i).changesAt(now);
                }
                if (!draw) {
                    for (int i = 0; i < batch.size(); i++) {
                        if (tick < motions.get(i).recordTicks()) {
                            // Between changes the picture is the previous one; the clip merges the repeats.
                            List<int[]> frames = recorded.get(i);
                            frames.add(frames.get(frames.size() - 1));
                        }
                    }
                    continue;
                }
                animators.values().forEach(animator -> animator.show(now));
                try (NativeImage atlas = movingRenderer.render(batch, this::recordFailure,
                        OptionalLong.of(start + tick * NANOS_PER_TICK))) {
                    for (int i = 0; i < batch.size(); i++) {
                        if (tick >= motions.get(i).recordTicks()) {
                            continue;
                        }
                        List<int[]> frames = recorded.get(i);
                        frames.add(motions.get(i).changesAt(tick)
                                ? IconAtlasRenderer.slot(atlas, movingRenderer.columns(), i)
                                : frames.get(frames.size() - 1));
                    }
                }
            }
        } finally {
            animators.values().forEach(SpriteAnimator::close);
        }
        writer.writeClips(batch, motions, recorded);
        nextMoving = end;
    }

    private void reportProgress() {
        int exported = nextStill + nextMoving;
        if (exported >= nextProgressReport && exported < total) {
            feedback.accept(Component.translatable("mecc_exporter.progress", exported, total));
            nextProgressReport = exported + Math.max(1, total / 4);
        }
    }

    /** Splits still from moving icons; moving ones are ordered by length, so long clips share batches. */
    private void classify(List<IconSource> sources, AnimationProbe probe) {
        List<Map.Entry<IconSource, Recording>> found = new ArrayList<>();
        for (IconSource source : sources) {
            Optional<Recording> recording;
            try {
                recording = probe.recording(source);
            } catch (RuntimeException e) {
                // A model that cannot even be inspected is exported as a still image, if it draws at all.
                LOGGER.debug("Could not inspect {} for animation: {}", source.iconKey(), e.toString());
                recording = Optional.empty();
            }
            if (recording.isPresent()) {
                found.add(Map.entry(source, recording.get()));
            } else {
                still.add(source);
            }
        }
        found.sort(Comparator.comparingInt(entry -> entry.getValue().motion().recordTicks()));
        found.forEach(entry -> {
            moving.add(entry.getKey());
            recordings.add(entry.getValue());
        });
    }

    private void recordFailure(IconSource source, RuntimeException e) {
        String namespace = source.iconKey().split("/", 3)[1];
        if (failuresByNamespace.merge(namespace, 1, Integer::sum) == 1) {
            // One stack trace per mod is enough to report the problem; the rest would only bury it.
            LOGGER.warn("Could not render {} (further failures from {} are only counted)", source.iconKey(), namespace, e);
        }
    }

    private void finish() {
        finishing = true;
        stillRenderer.close();
        movingRenderer.close();
        writer.writeFile(ContentPackFormat.MANIFEST, () -> manifest().getBytes(StandardCharsets.UTF_8));
        writer.finish(target).whenComplete((path, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                fail(error);
                return;
            }
            writer.close();
            done = true;
            if (!failuresByNamespace.isEmpty()) {
                LOGGER.warn("Icons that failed to render, by namespace: {}", failuresByNamespace);
            }
            LOGGER.info("ME Control Center content pack written to {}", path.toAbsolutePath());
            feedback.accept(Component.translatable("mecc_exporter.done", writer.iconsWritten(),
                    writer.iconsAnimated(), total - writer.iconsWritten(), locales.size(), ExporterCommands.fileLink(path)));
            feedback.accept(Component.translatable("mecc_exporter.next_step"));
        }));
    }

    private void fail(Throwable error) {
        LOGGER.error("ME Control Center content pack export failed", error);
        feedback.accept(Component.translatable("mecc_exporter.failed", String.valueOf(error.getMessage())));
        done = true;
        finishing = true;
        stillRenderer.close();
        movingRenderer.close();
        writer.close();
    }

    // --- what goes into the pack -------------------------------------------------------------------

    private static List<IconSource> collectSources() {
        List<IconSource> sources = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            if (item == Items.AIR) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            sources.add(new IconSource.ItemIcon("item/" + id.getNamespace() + "/" + id.getPath(), new ItemStack(item)));
        }
        for (Fluid fluid : ForgeRegistries.FLUIDS.getValues()) {
            // Storage holds source fluids only; their flowing twins would just duplicate the icon.
            if (fluid == Fluids.EMPTY || !fluid.isSource(fluid.defaultFluidState())) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
            sources.add(new IconSource.FluidIcon("fluid/" + id.getNamespace() + "/" + id.getPath(), fluid));
        }
        return sources;
    }

    /**
     * Writes, per namespace and locale, the client's merged language table: vanilla's full translations,
     * every mod's, and whatever resource packs and runtime-generated packs change. The lookup happens here;
     * the reading happens on the writer thread.
     */
    private void exportLanguages(ResourceManager resources) {
        for (String locale : locales) {
            for (String namespace : resources.getNamespaces()) {
                List<Resource> stack = resources.getResourceStack(new ResourceLocation(namespace, "lang/" + locale + ".json"));
                if (stack.isEmpty()) {
                    continue;
                }
                writer.writeFile("assets/" + namespace + "/lang/" + locale + ".json", () -> {
                    Map<String, String> table = new TreeMap<>();
                    for (Resource resource : stack) {
                        try (InputStream in = resource.open()) {
                            Language.loadFromJson(in, table::put);
                        } catch (Exception e) {
                            LOGGER.warn("Skipping unreadable {}:lang/{}.json from {}: {}", namespace, locale,
                                    resource.sourcePackId(), e.toString());
                        }
                    }
                    return table.isEmpty() ? null : GSON.toJson(table).getBytes(StandardCharsets.UTF_8);
                });
            }
        }
    }

    private String manifest() {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", ContentPackFormat.VERSION);
        manifest.addProperty("generator", "mecc_exporter " + MeccExporter.version());
        manifest.addProperty("createdAt", Instant.now().toString());
        manifest.addProperty("minecraft", SharedConstants.getCurrentVersion().getName());
        manifest.addProperty("loader", "forge");
        manifest.addProperty("loaderVersion", ForgeVersion.getVersion());
        manifest.addProperty("fingerprint", fingerprint);
        manifest.addProperty("iconSize", ContentPackFormat.ICON_SIZE);
        JsonArray localeArray = new JsonArray();
        locales.forEach(localeArray::add);
        manifest.add("locales", localeArray);
        JsonObject icons = new JsonObject();
        icons.addProperty("exported", writer.iconsWritten());
        icons.addProperty("animated", writer.iconsAnimated());
        icons.addProperty("blank", writer.iconsBlank());
        icons.addProperty("failed", failuresByNamespace.values().stream().mapToInt(Integer::intValue).sum());
        manifest.add("icons", icons);
        JsonArray mods = new JsonArray();
        for (Map.Entry<String, IModInfo> mod : installedMods().entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", mod.getKey());
            entry.addProperty("name", mod.getValue().getDisplayName());
            entry.addProperty("version", mod.getValue().getVersion().toString());
            mods.add(entry);
        }
        manifest.add("mods", mods);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest);
    }

    private static Map<String, IModInfo> installedMods() {
        Map<String, IModInfo> mods = new TreeMap<>();
        ModList.get().getMods().forEach(mod -> mods.put(mod.getModId(), mod));
        return mods;
    }

    /**
     * Identifies the installation (spec section 45): the same modpack version always gives the same name,
     * and the server can tell when a pack comes from a different one. This exporter itself is left out, so
     * installing it does not make the pack look foreign.
     */
    private static String fingerprint() throws Exception {
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("minecraft", SharedConstants.getCurrentVersion().getName());
        identity.put("forge", ForgeVersion.getVersion());
        installedMods().forEach((id, mod) -> {
            if (!id.equals(MeccExporter.MOD_ID)) {
                identity.put(id, mod.getVersion().toString());
            }
        });
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, 6);
    }
}

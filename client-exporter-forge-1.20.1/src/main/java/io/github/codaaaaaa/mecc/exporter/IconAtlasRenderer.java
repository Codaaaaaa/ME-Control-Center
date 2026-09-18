package io.github.codaaaaaa.mecc.exporter;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BiConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.TimeSource;
import org.joml.Matrix4f;

/**
 * Renders batches of icons into an off-screen atlas with the game's own GUI item renderer, so every icon
 * looks exactly as it does in an inventory: baked models, custom renderers, tints and all.
 *
 * <p>Render thread only. The game's projection, model-view, target and clock are restored after every batch.
 */
final class IconAtlasRenderer implements AutoCloseable {
    /** GUI units per slot; the projection scales them up to {@link ContentPackFormat#ICON_SIZE} pixels. */
    private static final int SLOT = 16;

    private final int columns;
    private final int atlasSize;
    private final TextureTarget target;

    /** @param columns icons per row and per column */
    IconAtlasRenderer(int columns) {
        this.columns = columns;
        this.atlasSize = columns * ContentPackFormat.ICON_SIZE;
        this.target = new TextureTarget(atlasSize, atlasSize, true, Minecraft.ON_OSX);
        target.setClearColor(0, 0, 0, 0);
    }

    int batchSize() {
        return columns * columns;
    }

    /**
     * @param batch   up to {@link #batchSize()} icons, laid out row by row
     * @param failure called for an icon whose rendering threw; the slot stays empty
     * @param clock   the time, in {@link Util#getNanos()} terms, that time-driven effects such as the
     *                enchantment glint see while drawing; empty for the real time
     * @return the atlas, top row first; the caller closes it
     */
    NativeImage render(List<IconSource> batch, BiConsumer<IconSource, RuntimeException> failure, OptionalLong clock) {
        Minecraft minecraft = Minecraft.getInstance();
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);

        Matrix4f projection = RenderSystem.getProjectionMatrix();
        VertexSorting sorting = RenderSystem.getVertexSorting();
        float units = columns * SLOT;
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, units, units, 0, 1000, 3000),
                VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.translate(0, 0, -2000);
        RenderSystem.applyModelViewMatrix();
        Lighting.setupFor3DItems();
        TimeSource.NanoTimeSource realClock = Util.timeSource;
        if (clock.isPresent()) {
            // Only this thread is fooled, and only while drawing: other threads (sound, an integrated
            // server) keep the real time.
            Thread renderThread = Thread.currentThread();
            long frozen = clock.getAsLong();
            Util.timeSource = () -> Thread.currentThread() == renderThread ? frozen : realClock.getAsLong();
        }
        try {
            GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            for (int i = 0; i < batch.size(); i++) {
                IconSource source = batch.get(i);
                try {
                    source.draw(graphics, (i % columns) * SLOT, (i / columns) * SLOT);
                    // Flushed per icon: one mod's broken renderer must not take the rest of the batch with it,
                    // and time-driven effects read the clock when their batch is drawn, which is now.
                    graphics.flush();
                } catch (RuntimeException e) {
                    failure.accept(source, e);
                }
                RenderSystem.setShaderColor(1, 1, 1, 1);
            }
        } finally {
            Util.timeSource = realClock;
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            minecraft.getMainRenderTarget().bindWrite(true);
        }

        NativeImage atlas = new NativeImage(atlasSize, atlasSize, false);
        RenderSystem.bindTexture(target.getColorTextureId());
        atlas.downloadTexture(0, false);
        atlas.flipY();
        return atlas;
    }

    /** One slot's pixels in {@code NativeImage} order, row by row. Any thread. */
    static int[] slot(NativeImage atlas, int columns, int index) {
        int size = ContentPackFormat.ICON_SIZE;
        int left = (index % columns) * size;
        int top = (index / columns) * size;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = atlas.getPixelRGBA(left + x, top + y);
            }
        }
        return pixels;
    }

    int columns() {
        return columns;
    }

    @Override
    public void close() {
        target.destroyBuffers();
    }
}

package io.github.codaaaaaa.mecc.exporter;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Shows an animated sprite exactly as it looks a given number of ticks into its animation, by uploading
 * that frame into the atlas itself, at every mipmap level, blending interpolated frames the way the game
 * does.
 *
 * <p>The game's own sprite tickers are deliberately bypassed: their phase is wherever play left it, and
 * renderers such as Embeddium and Sodium skip their uploads for anything not currently visible in the world,
 * which an icon being exported never is. Render thread only.
 */
final class SpriteAnimator implements AutoCloseable {
    private final TextureAtlasSprite sprite;
    private final SpriteTiming timing;
    private final SpriteContents contents;
    private final int framesPerRow;
    private final NativeImage[] blended;

    SpriteAnimator(TextureAtlasSprite sprite, SpriteTiming timing) {
        this.sprite = sprite;
        this.timing = timing;
        this.contents = sprite.contents();
        this.framesPerRow = Math.max(1, contents.getOriginalImage().getWidth() / contents.width());
        this.blended = new NativeImage[contents.byMipLevel.length];
    }

    /** Uploads the sprite's state {@code tick} ticks after its first frame. */
    void show(int tick) {
        SpriteTiming.State state = timing.stateAt(tick);
        Minecraft.getInstance().getTextureManager().getTexture(sprite.atlasLocation()).bind();
        NativeImage[] levels = contents.byMipLevel;
        // As SpriteContents uploads: mipmapped when there are levels, and never auto-closing the source image.
        boolean mipmapped = levels.length > 1;
        for (int level = 0; level < levels.length; level++) {
            int width = contents.width() >> level;
            int height = contents.height() >> level;
            if (width == 0 || height == 0) {
                break;
            }
            int frameX = frameX(state.frame()) >> level;
            int frameY = frameY(state.frame()) >> level;
            int nextX = frameX(state.next()) >> level;
            int nextY = frameY(state.next()) >> level;
            if (!fits(levels[level], frameX, frameY, width, height) || !fits(levels[level], nextX, nextY, width, height)) {
                // A frame list naming a frame the image does not have; the game would not show it either.
                return;
            }
            if (state.weight() >= 1.0) {
                levels[level].upload(level, sprite.getX() >> level, sprite.getY() >> level, frameX, frameY,
                        width, height, mipmapped, false);
                continue;
            }
            if (blended[level] == null) {
                blended[level] = new NativeImage(width, height, false);
            }
            double weight = state.weight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int current = levels[level].getPixelRGBA(frameX + x, frameY + y);
                    int next = levels[level].getPixelRGBA(nextX + x, nextY + y);
                    // As the game blends: colour channels mix, alpha stays the current frame's.
                    int pixel = current & 0xFF000000;
                    for (int shift = 0; shift < 24; shift += 8) {
                        int a = (current >> shift) & 0xFF;
                        int b = (next >> shift) & 0xFF;
                        pixel |= (int) (weight * a + (1 - weight) * b) << shift;
                    }
                    blended[level].setPixelRGBA(x, y, pixel);
                }
            }
            blended[level].upload(level, sprite.getX() >> level, sprite.getY() >> level, 0, 0, width, height,
                    mipmapped, false);
        }
    }

    private static boolean fits(NativeImage image, int x, int y, int width, int height) {
        return x + width <= image.getWidth() && y + height <= image.getHeight();
    }

    private int frameX(int frame) {
        return frame % framesPerRow * contents.width();
    }

    private int frameY(int frame) {
        return frame / framesPerRow * contents.height();
    }

    @Override
    public void close() {
        for (NativeImage image : blended) {
            if (image != null) {
                image.close();
            }
        }
    }
}

package io.github.codaaaaaa.mecc.exporter;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;

/** Something to draw into one 16x16 GUI slot, and the server icon key it is exported under. */
sealed interface IconSource {
    String iconKey();

    /** Draws at GUI coordinates {@code x, y}, the way an inventory slot would show it. */
    void draw(GuiGraphics graphics, int x, int y);

    /** An item's default stack: the server's icon keys carry no NBT, so neither does the icon. */
    record ItemIcon(String iconKey, ItemStack stack) implements IconSource {
        @Override
        public void draw(GuiGraphics graphics, int x, int y) {
            graphics.renderItem(stack, x, y);
        }
    }

    /** A fluid's still texture with its tint, as fluid terminals and tanks show it. */
    record FluidIcon(String iconKey, Fluid fluid) implements IconSource {
        /** The still texture; empty when the fluid has none, so the server renders it instead. */
        Optional<TextureAtlasSprite> sprite() {
            ResourceLocation still = IClientFluidTypeExtensions.of(fluid).getStillTexture(new FluidStack(fluid, 1000));
            if (still == null) {
                return Optional.empty();
            }
            TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
            return sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())
                    ? Optional.empty()
                    : Optional.of(sprite);
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y) {
            Optional<TextureAtlasSprite> sprite = sprite();
            if (sprite.isEmpty()) {
                return;
            }
            int tint = IClientFluidTypeExtensions.of(fluid).getTintColor(new FluidStack(fluid, 1000));
            float alpha = ((tint >>> 24) & 0xFF) / 255f;
            RenderSystem.enableBlend();
            graphics.blit(x, y, 0, 16, 16, sprite.get(), ((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f,
                    (tint & 0xFF) / 255f, alpha == 0 ? 1 : alpha);
        }
    }
}

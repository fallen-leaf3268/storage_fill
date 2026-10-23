package dev.airun.storagefill.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.airun.storagefill.client.LegacyRecipeQueryButtonHost;
import mezz.jei.common.util.ImmutableRect2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(targets = "mezz.jei.gui.recipes.RecipeGuiLayouts", remap = false)
public abstract class LegacyRecipeQueryLayoutsMixin {
    @Shadow @Final private List<?> recipeLayoutsWithButtons;

    @Inject(method = "updateLayout", at = @At("RETURN"))
    private void storageFillPositionQueryButtons(ImmutableRect2i area, int recipesPerPage, CallbackInfo callback) {
        for (Object layout : recipeLayoutsWithButtons) {
            if (layout instanceof LegacyRecipeQueryButtonHost host) host.storageFillUpdateQueryBounds();
        }
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private void storageFillDrawQueryButtons(GuiGraphics graphics, int mouseX, int mouseY,
            CallbackInfoReturnable<?> callback) {
        float partialTicks = Minecraft.getInstance().getDeltaFrameTime();
        for (Object layout : recipeLayoutsWithButtons) {
            if (layout instanceof LegacyRecipeQueryButtonHost host && host.storageFillQueryButton() != null) {
                host.storageFillQueryButton().draw(graphics, mouseX, mouseY, partialTicks);
            }
        }
        RenderSystem.disableBlend();
    }

    @Inject(method = "drawTooltips", at = @At("RETURN"))
    private void storageFillQueryTooltips(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo callback) {
        for (Object layout : recipeLayoutsWithButtons) {
            if (layout instanceof LegacyRecipeQueryButtonHost host && host.storageFillQueryButton() != null) {
                host.storageFillQueryButton().drawTooltips(graphics, mouseX, mouseY);
            }
        }
    }
}

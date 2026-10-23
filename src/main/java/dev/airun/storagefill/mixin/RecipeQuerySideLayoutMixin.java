package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.RecipeQueryButtonAreas;
import dev.airun.storagefill.client.RecipeQueryLayout;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.library.gui.recipes.RecipeLayout;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RecipeLayout.class, remap = false)
public abstract class RecipeQuerySideLayoutMixin {
    @Inject(method = "getSideButtonArea", at = @At("HEAD"), cancellable = true)
    private void storageFillQuerySideArea(int index, CallbackInfoReturnable<Rect2i> callback) {
        if (index >= 0 && ((RecipeQueryLayout) this).storageFillQueryButtonVisible()) {
            callback.setReturnValue(RecipeQueryButtonAreas.relative((IRecipeLayoutDrawable<?>) this, index));
        }
    }
}

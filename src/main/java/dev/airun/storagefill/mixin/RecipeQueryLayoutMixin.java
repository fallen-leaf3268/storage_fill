package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.RecipeQueryLayout;
import mezz.jei.library.gui.recipes.RecipeLayout;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RecipeLayout.class, remap = false)
public abstract class RecipeQueryLayoutMixin implements RecipeQueryLayout {
    @Unique private boolean storageFillQueryButtons;
    @Shadow public abstract Rect2i getRect();
    @Shadow public abstract Rect2i getRecipeTransferButtonArea();
    @Shadow public abstract Rect2i getRectWithBorder();

    @Override
    public void storageFillReserveQueryButtons() { storageFillQueryButtons = true; }

    @Inject(method = "getRectWithBorder", at = @At("RETURN"), cancellable = true)
    private void storageFillQueryHeight(CallbackInfoReturnable<Rect2i> callback) {
        if (!storageFillQueryButtons) return;
        Rect2i area = callback.getReturnValue();
        int minimum = getRecipeTransferButtonArea().getHeight() * 2 + 2;
        if (area.getHeight() < minimum) {
            callback.setReturnValue(new Rect2i(area.getX(), area.getY(), area.getWidth(), minimum));
        }
    }

    @Inject(method = "getSideButtonArea", at = @At("HEAD"), cancellable = true)
    private void storageFillQuerySideArea(int index, CallbackInfoReturnable<Rect2i> callback) {
        if (!storageFillQueryButtons || index < 0) return;
        Rect2i button = getRecipeTransferButtonArea();
        Rect2i border = getRectWithBorder();
        Rect2i recipe = getRect();
        int rows = Math.max(2, (border.getHeight() + 2) / (button.getHeight() + 2));
        int x = button.getX() + index / rows * (button.getWidth() + 2);
        int y = border.getY() - recipe.getY() + border.getHeight() - button.getHeight()
            - index % rows * (button.getHeight() + 2);
        callback.setReturnValue(new Rect2i(x, y, button.getWidth(), button.getHeight()));
    }
}

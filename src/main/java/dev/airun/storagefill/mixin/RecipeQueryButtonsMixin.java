package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.RecipeQueryButton;
import dev.airun.storagefill.client.RecipeQueryLayout;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.elements.IconButton;
import mezz.jei.gui.recipes.RecipeLayoutWithButtons;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RecipeLayoutWithButtons.class, remap = false)
public abstract class RecipeQueryButtonsMixin<R> {
    @Shadow @Final private IRecipeLayoutDrawable<R> recipeLayout;
    @Unique private int storageFillQueryOffset;

    @Inject(method = "updateBounds", at = @At("HEAD"))
    private void storageFillBeginQueryBounds(int x, int y, CallbackInfo callback) {
        storageFillQueryOffset = recipeLayout instanceof RecipeQueryLayout reservation
            && reservation.storageFillQueryButtonVisible() ? 1 : 0;
    }

    @ModifyArg(method = "updateBounds", at = @At(value = "INVOKE",
        target = "Lmezz/jei/gui/recipes/RecipeLayoutWithButtons;getAbsoluteButtonArea(I)Lmezz/jei/common/util/ImmutableRect2i;"), index = 0)
    private int storageFillShiftNativeButton(int index) { return index + storageFillQueryOffset; }

    @Redirect(method = "updateBounds", at = @At(value = "INVOKE",
        target = "Lmezz/jei/gui/elements/IconButton;updateBounds(Lmezz/jei/common/util/ImmutableRect2i;)V"))
    private void storageFillQueryBounds(IconButton button, ImmutableRect2i area) {
        if (((RecipeQueryIconButtonAccessor) (Object) button).storageFillController() instanceof RecipeQueryButton) {
            storageFillQueryOffset = 0;
            area = storageFillArea(0, recipeLayout.getRect());
        }
        button.updateBounds(area);
    }

    @Unique
    private ImmutableRect2i storageFillArea(int index, Rect2i origin) {
        Rect2i area = recipeLayout.getSideButtonArea(index);
        return new ImmutableRect2i(origin.getX() + area.getX(), origin.getY() + area.getY(), area.getWidth(), area.getHeight());
    }
}

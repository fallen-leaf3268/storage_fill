package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.RecipeQueryLayout;
import dev.airun.storagefill.JeiCompatibility;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.library.gui.recipes.RecipeLayout;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(value = RecipeLayout.class, remap = false)
public abstract class RecipeQueryLayoutMixin implements RecipeQueryLayout {
    @Unique private BooleanSupplier storageFillQueryButtonVisible;
    @Unique private boolean storageFillTransferButtonVisible;
    @Unique private boolean storageFillTransferButtonVisibilityKnown;
    @Unique private IRecipeSlotsView storageFillStableRecipeSlots;
    @Shadow public abstract Rect2i getRecipeTransferButtonArea();

    @Override
    public void storageFillReserveQueryButtons(BooleanSupplier visible) { storageFillQueryButtonVisible = visible; }

    @Override
    public boolean storageFillQueryButtonVisible() {
        return storageFillQueryButtonVisible != null && storageFillQueryButtonVisible.getAsBoolean();
    }

    @Override
    public void storageFillSetTransferButtonVisible(boolean visible) {
        storageFillTransferButtonVisible = visible;
        storageFillTransferButtonVisibilityKnown = true;
    }

    @Override
    public boolean storageFillTransferButtonVisible() { return storageFillTransferButtonVisible; }

    @Inject(method = "getRecipeSlotsView", at = @At("RETURN"), cancellable = true)
    private void storageFillStableRecipeSlots(CallbackInfoReturnable<IRecipeSlotsView> callback) {
        if (JeiCompatibility.CONTROLLER_BUTTONS) return;
        if (storageFillStableRecipeSlots == null) storageFillStableRecipeSlots = callback.getReturnValue();
        callback.setReturnValue(storageFillStableRecipeSlots);
    }

    @Inject(method = "getRectWithBorder", at = @At("RETURN"), cancellable = true)
    private void storageFillQueryHeight(CallbackInfoReturnable<Rect2i> callback) {
        if (storageFillQueryButtonVisible == null
            || storageFillTransferButtonVisibilityKnown && !storageFillQueryButtonVisible()) return;
        Rect2i area = callback.getReturnValue();
        int minimum = getRecipeTransferButtonArea().getHeight() * 2 + 2;
        if (area.getHeight() < minimum) {
            callback.setReturnValue(new Rect2i(area.getX(), area.getY(), area.getWidth(), minimum));
        }
    }

}

package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.RecipeQueryButton;
import dev.airun.storagefill.client.RecipeQueryButtonHost;
import dev.airun.storagefill.client.RecipeQueryLayout;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.elements.IconButton;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipeBookmarkButton;
import mezz.jei.gui.recipes.RecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipeTransferButton;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = RecipeLayoutWithButtons.class, remap = false)
public abstract class RecipeQueryButtonsMixin<R> implements RecipeQueryButtonHost {
    @Shadow @Final private IRecipeLayoutDrawable<R> recipeLayout;
    @Shadow @Final private RecipeTransferButton transferButton;
    @Shadow @Final private RecipeBookmarkButton bookmarkButton;
    @Shadow @Final private List<IconButton> extraButtons;
    @Unique private IconButton storageFillQueryButton;

    @Override
    public void storageFillInstallQueryButton(IconButton button) { storageFillQueryButton = button; }

    @Inject(method = "create", at = @At("RETURN"), cancellable = true)
    private static <T> void storageFillCreateQuery(IRecipeLayoutDrawable<T> layout, RecipeTransferButton transfer,
            RecipeBookmarkButton bookmark, List<IRecipeButtonControllerFactory> factories,
            CallbackInfoReturnable<IRecipeLayoutWithButtons<T>> callback) {
        if (!(callback.getReturnValue() instanceof RecipeLayoutWithButtons<?> original)
            || !(layout instanceof RecipeQueryLayout reservation)) return;
        reservation.storageFillReserveQueryButtons();
        IconButton query = new IconButton(new RecipeQueryButton(layout, transfer));
        List<IconButton> buttons = new ArrayList<>();
        buttons.add(query);
        buttons.addAll(original.extraButtons());
        RecipeLayoutWithButtons<T> result = new RecipeLayoutWithButtons<>(layout, transfer, bookmark, buttons);
        ((RecipeQueryButtonHost) (Object) result).storageFillInstallQueryButton(query);
        callback.setReturnValue(result);
    }

    @Inject(method = "updateBounds", at = @At("TAIL"))
    private void storageFillQueryBounds(int x, int y, CallbackInfo callback) {
        IconButton query = storageFillQueryButton;
        if (query == null || !query.isVisible()) return;
        Rect2i origin = recipeLayout.getRect();
        int index = 0;
        query.updateBounds(storageFillArea(index++, origin));
        if (transferButton.isVisible()) transferButton.updateBounds(storageFillArea(index++, origin));
        if (bookmarkButton.isVisible()) bookmarkButton.updateBounds(storageFillArea(index++, origin));
        for (IconButton button : extraButtons) {
            if (button != query && button.isVisible()) button.updateBounds(storageFillArea(index++, origin));
        }
    }

    @Unique
    private ImmutableRect2i storageFillArea(int index, Rect2i origin) {
        Rect2i area = recipeLayout.getSideButtonArea(index);
        return new ImmutableRect2i(origin.getX() + area.getX(), origin.getY() + area.getY(), area.getWidth(), area.getHeight());
    }
}

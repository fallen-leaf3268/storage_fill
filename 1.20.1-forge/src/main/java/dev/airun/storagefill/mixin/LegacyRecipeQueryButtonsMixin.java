package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.LegacyRecipeQueryButton;
import dev.airun.storagefill.client.LegacyRecipeQueryButtonHost;
import dev.airun.storagefill.client.RecipeQueryButtonAreas;
import dev.airun.storagefill.client.RecipeQueryLayout;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.handlers.CombinedInputHandler;
import mezz.jei.gui.recipes.RecipeBookmarkButton;
import mezz.jei.gui.recipes.RecipeTransferButton;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "mezz.jei.gui.recipes.RecipeLayoutWithButtons", remap = false)
public abstract class LegacyRecipeQueryButtonsMixin<R> implements LegacyRecipeQueryButtonHost {
    @Shadow @Final private IRecipeLayoutDrawable<R> recipeLayout;
    @Shadow @Final private RecipeTransferButton transferButton;
    @Shadow @Final private RecipeBookmarkButton bookmarkButton;
    @Unique private LegacyRecipeQueryButton storageFillQueryButton;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void storageFillCreateQueryButton(CallbackInfo callback) {
        if (!(recipeLayout instanceof RecipeQueryLayout reservation)) return;
        storageFillQueryButton = new LegacyRecipeQueryButton(recipeLayout);
        reservation.storageFillReserveQueryButtons(storageFillQueryButton::isAvailable);
    }

    @Override
    public LegacyRecipeQueryButton storageFillQueryButton() { return storageFillQueryButton; }

    @Override
    public void storageFillUpdateQueryBounds() {
        if (storageFillQueryButton == null) return;
        storageFillQueryButton.tick();
        if (storageFillQueryButton.isVisible()) {
            storageFillQueryButton.updateBounds(RecipeQueryButtonAreas.absolute(recipeLayout, 0));
            int index = 1;
            if (transferButton.isVisible()) transferButton.updateBounds(RecipeQueryButtonAreas.absolute(recipeLayout, index++));
            if (bookmarkButton.isVisible()) bookmarkButton.updateBounds(RecipeQueryButtonAreas.absolute(recipeLayout, index));
        } else {
            Rect2i recipe = recipeLayout.getRect();
            Rect2i transfer = recipeLayout.getRecipeTransferButtonArea();
            transfer.setPosition(transfer.getX() + recipe.getX(), transfer.getY() + recipe.getY());
            transferButton.updateBounds(transfer);
            Rect2i bookmark = recipeLayout.getRecipeBookmarkButtonArea();
            bookmark.setPosition(bookmark.getX() + recipe.getX(), bookmark.getY() + recipe.getY());
            bookmarkButton.updateBounds(bookmark);
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void storageFillTickQueryButton(AbstractContainerMenu menu, Player player, CallbackInfo callback) {
        storageFillUpdateQueryBounds();
    }

    @Inject(method = "createUserInputHandler", at = @At("RETURN"), cancellable = true)
    private void storageFillQueryInput(CallbackInfoReturnable<IUserInputHandler> callback) {
        if (storageFillQueryButton != null) {
            callback.setReturnValue(new CombinedInputHandler("StorageFillRecipeQuery",
                storageFillQueryButton.createInputHandler(), callback.getReturnValue()));
        }
    }

    @Inject(method = "totalWidth", at = @At("RETURN"), cancellable = true)
    private void storageFillQueryWidth(CallbackInfoReturnable<Integer> callback) {
        if (storageFillQueryButton == null || !storageFillQueryButton.isAvailable()) return;
        Rect2i recipe = recipeLayout.getRect();
        Rect2i border = recipeLayout.getRectWithBorder();
        int left = recipe.getX() - border.getX();
        int width = callback.getReturnValue();
        int lastIndex = (transferButton.isVisible() ? 1 : 0) + (bookmarkButton.isVisible() ? 1 : 0);
        for (int index = 0; index <= lastIndex; index++) {
            Rect2i button = RecipeQueryButtonAreas.relative(recipeLayout, index);
            width = Math.max(width, left + button.getX() + button.getWidth());
        }
        callback.setReturnValue(width);
    }
}

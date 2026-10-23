package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.CustomTransfer;
import dev.airun.storagefill.client.JeiTransfers;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(targets = "mezz.jei.common.transfer.RecipeTransferUtil", remap = false)
public abstract class LegacyCustomTransferMixin {
    @Inject(method = "getTransferRecipeError(Lmezz/jei/api/recipe/transfer/IRecipeTransferManager;Lnet/minecraft/world/inventory/AbstractContainerMenu;Lmezz/jei/api/gui/IRecipeLayoutDrawable;Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;",
        at = @At("RETURN"))
    private static void storageFillTransferVisibility(IRecipeTransferManager manager, AbstractContainerMenu menu,
            IRecipeLayoutDrawable<?> layout, Player player, CallbackInfoReturnable<Optional<IRecipeTransferError>> callback) {
        JeiTransfers.visible(layout, callback.getReturnValue().orElse(null));
    }

    @Redirect(method = "transferRecipe(Lmezz/jei/api/recipe/transfer/IRecipeTransferManager;Lnet/minecraft/world/inventory/AbstractContainerMenu;Lmezz/jei/api/gui/IRecipeLayoutDrawable;Lnet/minecraft/world/entity/player/Player;ZZ)Ljava/util/Optional;",
        at = @At(value = "INVOKE", target = "Lmezz/jei/api/recipe/transfer/IRecipeTransferHandler;transferRecipe(Lnet/minecraft/world/inventory/AbstractContainerMenu;Ljava/lang/Object;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;"))
    private static <C extends AbstractContainerMenu, R> IRecipeTransferError storageFillCustom(
            IRecipeTransferHandler<C, R> handler, C menu, R recipe, IRecipeSlotsView views, Player player,
            boolean maximum, boolean perform) {
        return CustomTransfer.transfer(handler,
            JeiTransfers.legacyContext(menu, recipe, views, player, maximum, handler.getRecipeType(), null), perform);
    }
}

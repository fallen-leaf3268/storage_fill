package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.CustomTransfer;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.common.transfer.RecipeTransferService;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = RecipeTransferService.class, remap = false)
public abstract class CustomTransferMixin {
    @Redirect(method = "transferRecipe(Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;Lmezz/jei/api/gui/IRecipeLayoutDrawable;Lnet/minecraft/world/entity/player/Player;ZZ)Ljava/util/Optional;",
            at = @At(value = "INVOKE", target = "Lmezz/jei/api/recipe/transfer/IRecipeTransferHandler;transferRecipe(Lmezz/jei/api/recipe/transfer/IRecipeTransferContext;Z)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;"))
    private <C extends AbstractContainerMenu, R> IRecipeTransferError storageFillCustom(
            IRecipeTransferHandler<C, R> handler, IRecipeTransferContext<R, C> context, boolean perform) {
        return CustomTransfer.transfer(handler, context, perform);
    }
}

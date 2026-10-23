package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.CustomTransfer;
import dev.airun.storagefill.client.JeiTransfers;
import dev.airun.storagefill.client.ModernJeiTransfers;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.common.transfer.RecipeTransferService;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(value = RecipeTransferService.class, remap = false)
public abstract class CustomTransferMixin {
    @Inject(method = "getTransferRecipeError(Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;Lmezz/jei/api/gui/IRecipeLayoutDrawable;Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;",
        at = @At("RETURN"))
    private void storageFillTransferVisibility(AbstractContainerScreen<?> screen, IRecipeLayoutDrawable<?> layout,
            Player player, CallbackInfoReturnable<Optional<IRecipeTransferError>> callback) {
        IRecipeTransferError error = callback.getReturnValue().orElse(null);
        JeiTransfers.visible(layout, error);
    }

    @Redirect(method = "transferRecipe(Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;Lmezz/jei/api/gui/IRecipeLayoutDrawable;Lnet/minecraft/world/entity/player/Player;ZZ)Ljava/util/Optional;",
            at = @At(value = "INVOKE", target = "Lmezz/jei/api/recipe/transfer/IRecipeTransferHandler;transferRecipe(Lmezz/jei/api/recipe/transfer/IRecipeTransferContext;Z)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;"))
    private <C extends AbstractContainerMenu, R> IRecipeTransferError storageFillCustom(
            IRecipeTransferHandler<C, R> handler, IRecipeTransferContext<R, C> context, boolean perform) {
        return CustomTransfer.transfer(handler, ModernJeiTransfers.wrap(context), perform);
    }
}

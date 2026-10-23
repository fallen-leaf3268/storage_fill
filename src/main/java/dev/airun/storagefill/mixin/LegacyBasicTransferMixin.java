package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.ClientTransfer;
import dev.airun.storagefill.client.JeiTransfers;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "mezz.jei.library.transfer.BasicRecipeTransferHandler", remap = false)
public abstract class LegacyBasicTransferMixin<C extends AbstractContainerMenu, R> {
    @Shadow @Final private IRecipeTransferInfo<C, R> transferInfo;
    @Shadow @Final private IStackHelper stackHelper;
    @Shadow @Final private IRecipeTransferHandlerHelper handlerHelper;
    @Unique private boolean storageFillChecking;

    @Inject(method = "transferRecipe(Lnet/minecraft/world/inventory/AbstractContainerMenu;Ljava/lang/Object;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
        at = @At("HEAD"), cancellable = true)
    private void storageFillPending(C container, R recipe, IRecipeSlotsView views, Player player,
            boolean maximum, boolean perform, CallbackInfoReturnable<IRecipeTransferError> callback) {
        if (storageFillChecking) return;
        if (!perform && ClientTransfer.captureQuery(container, recipe, views, player, transferInfo, stackHelper)) return;
        if (ClientTransfer.isPending() || dev.airun.storagefill.client.CustomTransfer.isPending()) {
            callback.setReturnValue(ClientTransfer.pendingError());
            return;
        }
        if (maximum && perform) {
            @SuppressWarnings("unchecked")
            var self = (IRecipeTransferHandler<C, R>) (Object) this;
            var context = JeiTransfers.legacyContext(container, recipe, views, player, maximum, transferInfo.getRecipeType(), null);
            IRecipeTransferError original;
            storageFillChecking = true;
            try { original = JeiTransfers.invoke(self, context, false); }
            finally { storageFillChecking = false; }
            if (original != null && original.getType() == IRecipeTransferError.Type.INTERNAL) return;
            if (!transferInfo.canHandle(container, recipe)) return;
            if (ClientTransfer.deferMaximum(container, views,
                    resumed -> JeiTransfers.invoke(self, resumed, true), context)) {
                callback.setReturnValue(ClientTransfer.pendingError());
                return;
            }
            ClientTransfer.Result result = ClientTransfer.transfer(container, recipe, views, player, maximum, perform,
                transferInfo, stackHelper, handlerHelper, context);
            if (result.handled()) callback.setReturnValue(result.error());
        }
    }

    @Inject(method = "transferRecipe(Lnet/minecraft/world/inventory/AbstractContainerMenu;Ljava/lang/Object;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
        at = @At("RETURN"), cancellable = true)
    private void storageFill(C container, R recipe, IRecipeSlotsView views, Player player,
            boolean maximum, boolean perform, CallbackInfoReturnable<IRecipeTransferError> callback) {
        if (storageFillChecking) return;
        IRecipeTransferError original = callback.getReturnValue();
        if (original == null || original.getType() != IRecipeTransferError.Type.USER_FACING) return;
        var context = JeiTransfers.legacyContext(container, recipe, views, player, maximum, transferInfo.getRecipeType(), null);
        ClientTransfer.Result result = ClientTransfer.transfer(container, recipe, views, player, maximum, perform,
                transferInfo, stackHelper, handlerHelper, context);
        if (result.handled()) callback.setReturnValue(result.error());
    }
}

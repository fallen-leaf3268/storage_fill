package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.ClientTransfer;
import dev.airun.storagefill.client.JeiTransfers;
import dev.airun.storagefill.client.ModernJeiTransfers;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.library.transfer.BasicRecipeTransferHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BasicRecipeTransferHandler.class, remap = false)
public abstract class BasicTransferMixin<C extends AbstractContainerMenu, R> {
    @Shadow @Final private IRecipeTransferInfo<C, R> transferInfo;
    @Shadow @Final private IStackHelper stackHelper;
    @Shadow @Final private IRecipeTransferHandlerHelper handlerHelper;
    @org.spongepowered.asm.mixin.Unique private boolean storageFillChecking;

    @Inject(method = "transferRecipe(Lmezz/jei/api/recipe/transfer/IRecipeTransferContext;Z)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
            at = @At("HEAD"), cancellable = true)
    private void storageFillPending(IRecipeTransferContext<R, C> nativeContext, boolean perform,
                                    CallbackInfoReturnable<IRecipeTransferError> callback) {
        if (storageFillChecking) return;
        var context = ModernJeiTransfers.wrap(nativeContext);
        C container = context.getContainer();
        R recipe = context.getRecipe();
        IRecipeSlotsView views = context.getRecipeSlots();
        Player player = context.getPlayer();
        boolean maximum = context.isMaxTransfer();
        if (!perform && ClientTransfer.captureQuery(container, recipe, views, player, transferInfo, stackHelper)) return;
        if (ClientTransfer.isPending() || dev.airun.storagefill.client.CustomTransfer.isPending()) {
            callback.setReturnValue(ClientTransfer.pendingError());
            return;
        }
        if (maximum && perform) {
            @SuppressWarnings("unchecked")
            var self = (mezz.jei.api.recipe.transfer.IRecipeTransferHandler<C, R>) (Object) this;
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

    @Inject(method = "transferRecipe(Lmezz/jei/api/recipe/transfer/IRecipeTransferContext;Z)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
            at = @At("RETURN"), cancellable = true)
    private void storageFill(IRecipeTransferContext<R, C> nativeContext, boolean perform,
                             CallbackInfoReturnable<IRecipeTransferError> callback) {
        if (storageFillChecking) return;
        var context = ModernJeiTransfers.wrap(nativeContext);
        IRecipeTransferError original = callback.getReturnValue();
        if (original == null || original.getType() != IRecipeTransferError.Type.USER_FACING) return;
        ClientTransfer.Result result = ClientTransfer.transfer(context.getContainer(), context.getRecipe(),
                context.getRecipeSlots(), context.getPlayer(), context.isMaxTransfer(), perform,
                transferInfo, stackHelper, handlerHelper, context);
        if (result.handled()) callback.setReturnValue(result.error());
    }
}

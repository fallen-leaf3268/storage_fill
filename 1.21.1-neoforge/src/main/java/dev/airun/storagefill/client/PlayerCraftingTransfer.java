package dev.airun.storagefill.client;

import dev.airun.storagefill.mixin.PlayerRecipeTransferHandlerAccessor;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.library.transfer.BasicRecipeTransferHandler;
import mezz.jei.library.transfer.PlayerRecipeTransferHandler;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.List;

final class PlayerCraftingTransfer {
    private PlayerCraftingTransfer() { }

    static boolean supports(IRecipeTransferHandler<?, ?> handler) {
        return handler.getClass() == PlayerRecipeTransferHandler.class
            && ((PlayerRecipeTransferHandlerAccessor) handler).storageFillHandler() instanceof BasicRecipeTransferHandler<?, ?>;
    }

    @SuppressWarnings("unchecked")
    static <C extends AbstractContainerMenu, R> IRecipeTransferError transfer(
            IRecipeTransferHandler<C, R> handler, TransferContext<R, C> context, boolean perform) {
        var accessor = (PlayerRecipeTransferHandlerAccessor) handler;
        var helper = accessor.storageFillHandlerHelper();
        var source = context.getRecipeSlots();
        var inputs = source.getSlotViews(RecipeIngredientRole.INPUT);
        if (!helper.recipeTransferHasServerSupport() || inputs.size() != 9) return JeiTransfers.invoke(handler, context, perform);
        for (int i = 0; i < inputs.size(); i++) {
            if (i != 0 && i != 1 && i != 3 && i != 4 && !inputs.get(i).isEmpty()) {
                return JeiTransfers.invoke(handler, context, perform);
            }
        }
        var slots = RemoteInventory.playerRecipeSlots(context.getContainer(), context.getRecipe(), source,
            () -> helper.createRecipeSlotsView(List.of(inputs.get(0), inputs.get(1), inputs.get(3), inputs.get(4))));
        if (slots == null) return JeiTransfers.invoke(handler, context, perform);
        var basic = (IRecipeTransferHandler<C, R>) accessor.storageFillHandler();
        return JeiTransfers.invoke(basic, context.withRecipeSlots(slots), perform);
    }
}

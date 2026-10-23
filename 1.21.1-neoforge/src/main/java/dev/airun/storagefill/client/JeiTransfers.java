package dev.airun.storagefill.client;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.common.Internal;
import mezz.jei.common.transfer.RecipeTransferErrorInternal;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Optional;

public final class JeiTransfers {
    private JeiTransfers() { }

    public static <C extends AbstractContainerMenu, R> Optional<IRecipeTransferError> check(
            AbstractContainerScreen<C> screen, IRecipeLayoutDrawable<R> layout, Player player) {
        var handlers = Internal.getJeiRuntime().getRecipeTransferManager();
        Optional<IRecipeTransferHandler<C, R>> handler = handlers.getRecipeTransferHandler(screen.getMenu(), layout.getRecipeCategory());
        IRecipeTransferError error = handler.isEmpty() ? RecipeTransferErrorInternal.INSTANCE
            : CustomTransfer.transfer(handler.get(), new QueryContext<>(screen.getMenu(), layout.getRecipe(),
                layout.getRecipeSlotsView(), player, false, layout.getRecipeCategory().getRecipeType(), screen), false);
        visible(layout, error);
        return Optional.ofNullable(error);
    }

    public static void visible(IRecipeLayoutDrawable<?> layout, IRecipeTransferError error) {
        if (layout instanceof RecipeQueryLayout reservation) {
            reservation.storageFillSetTransferButtonVisible(error == null || error.getType().allowsTransfer
                || error.getType() == IRecipeTransferError.Type.USER_FACING);
        }
    }

    public static <C extends AbstractContainerMenu, R> IRecipeTransferError invoke(
            IRecipeTransferHandler<C, R> handler, TransferContext<R, C> context, boolean perform) {
        return ModernJeiTransfers.invoke(handler, context, perform);
    }

    public static boolean usesLegacyTransfer(IRecipeTransferHandler<?, ?> handler) {
        return ModernJeiTransfers.usesLegacyTransfer(handler);
    }

    private record QueryContext<R, C extends AbstractContainerMenu>(C getContainer, R getRecipe,
            IRecipeSlotsView getRecipeSlots, Player getPlayer, boolean isMaxTransfer, RecipeType<R> getRecipeType,
            AbstractContainerScreen<C> getScreen) implements TransferContext<R, C> {
        public int getTransferId() { return 0; }
        public void completeRecipeTransfer(Result result) { }
    }
}

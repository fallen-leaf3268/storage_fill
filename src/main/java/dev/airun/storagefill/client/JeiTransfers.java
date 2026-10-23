package dev.airun.storagefill.client;

import dev.airun.storagefill.JeiCompatibility;
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
    private static final ThreadLocal<TransferContext<?, ?>> LEGACY_CONTEXT = new ThreadLocal<>();

    private JeiTransfers() { }

    public static Optional<IRecipeTransferError> check(AbstractContainerScreen<?> screen,
            IRecipeLayoutDrawable<?> layout, Player player) {
        if (JeiCompatibility.CONTEXT_TRANSFER) return ModernJeiTransfers.check(screen, layout, player);
        return legacyCheck(screen, layout, player);
    }

    private static <C extends AbstractContainerMenu, R> Optional<IRecipeTransferError> legacyCheck(
            AbstractContainerScreen<C> screen, IRecipeLayoutDrawable<R> layout, Player player) {
        var handlers = Internal.getJeiRuntime().getRecipeTransferManager();
        Optional<IRecipeTransferHandler<C, R>> handler = handlers.getRecipeTransferHandler(screen.getMenu(), layout.getRecipeCategory());
        IRecipeTransferError error = handler.isEmpty() ? RecipeTransferErrorInternal.INSTANCE
            : CustomTransfer.transfer(handler.get(), legacyContext(screen.getMenu(), layout.getRecipe(),
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

    @SuppressWarnings("removal")
    public static <C extends AbstractContainerMenu, R> IRecipeTransferError invoke(
            IRecipeTransferHandler<C, R> handler, TransferContext<R, C> context, boolean perform) {
        if (JeiCompatibility.CONTEXT_TRANSFER) return ModernJeiTransfers.invoke(handler, context, perform);
        TransferContext<?, ?> previous = LEGACY_CONTEXT.get();
        LEGACY_CONTEXT.set(context);
        try {
            IRecipeTransferError error = handler.transferRecipe(context.getContainer(), context.getRecipe(),
                context.getRecipeSlots(), context.getPlayer(), context.isMaxTransfer(), perform);
            if (perform && (error == null || error.getType().allowsTransfer)) {
                context.completeRecipeTransfer(TransferContext.Result.SUCCESS);
            }
            return error;
        } finally {
            if (previous == null) LEGACY_CONTEXT.remove();
            else LEGACY_CONTEXT.set(previous);
        }
    }

    public static boolean usesLegacyTransfer(IRecipeTransferHandler<?, ?> handler) {
        return !JeiCompatibility.CONTEXT_TRANSFER || ModernJeiTransfers.usesLegacyTransfer(handler);
    }

    @SuppressWarnings("unchecked")
    public static <C extends AbstractContainerMenu, R> TransferContext<R, C> legacyContext(C menu, R recipe,
            IRecipeSlotsView views, Player player, boolean maximum, RecipeType<R> type,
            AbstractContainerScreen<C> screen) {
        TransferContext<?, ?> active = LEGACY_CONTEXT.get();
        if (active != null && active.getContainer() == menu && active.getRecipe() == recipe
                && active.getRecipeSlots() == views && active.getPlayer() == player && active.isMaxTransfer() == maximum) {
            return (TransferContext<R, C>) active;
        }
        return new LegacyContext<>(menu, recipe, views, player, maximum, type, screen);
    }

    private record LegacyContext<R, C extends AbstractContainerMenu>(C getContainer, R getRecipe,
            IRecipeSlotsView getRecipeSlots, Player getPlayer, boolean isMaxTransfer, RecipeType<R> getRecipeType,
            AbstractContainerScreen<C> getScreen) implements TransferContext<R, C> {
        public int getTransferId() { return 0; }
        public void completeRecipeTransfer(Result result) { }
    }
}

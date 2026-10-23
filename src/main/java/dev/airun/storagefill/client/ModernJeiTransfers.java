package dev.airun.storagefill.client;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

public final class ModernJeiTransfers {
    private ModernJeiTransfers() { }

    static <C extends AbstractContainerMenu, R> IRecipeTransferError invoke(
            IRecipeTransferHandler<C, R> handler, TransferContext<R, C> context, boolean perform) {
        if (context instanceof BridgedContext<R, C> bridged) return handler.transferRecipe(bridged.delegate(), perform);
        return handler.transferRecipe(new NativeContext<>(context), perform);
    }

    public static <R, C extends AbstractContainerMenu> TransferContext<R, C> wrap(IRecipeTransferContext<R, C> context) {
        if (context instanceof NativeContext<R, C> nativeContext) return nativeContext.delegate();
        return new BridgedContext<>(context);
    }

    static boolean usesLegacyTransfer(IRecipeTransferHandler<?, ?> handler) {
        try {
            return handler.getClass().getMethod("transferRecipe", IRecipeTransferContext.class, boolean.class)
                .getDeclaringClass() == IRecipeTransferHandler.class;
        } catch (NoSuchMethodException error) {
            return false;
        }
    }

    private record BridgedContext<R, C extends AbstractContainerMenu>(IRecipeTransferContext<R, C> delegate)
            implements TransferContext<R, C> {
        public int getTransferId() { return delegate.getTransferId(); }
        public void completeRecipeTransfer(Result result) {
            delegate.completeRecipeTransfer(result == Result.SUCCESS ? RecipeTransferResult.SUCCESS : RecipeTransferResult.REJECTED);
        }
        public R getRecipe() { return delegate.getRecipe(); }
        public RecipeType<R> getRecipeType() { return delegate.getRecipeType(); }
        public C getContainer() { return delegate.getContainer(); }
        public AbstractContainerScreen<C> getScreen() { return delegate.getScreen(); }
        public IRecipeSlotsView getRecipeSlots() { return delegate.getRecipeSlots(); }
        public Player getPlayer() { return delegate.getPlayer(); }
        public boolean isMaxTransfer() { return delegate.isMaxTransfer(); }
    }

    private record NativeContext<R, C extends AbstractContainerMenu>(TransferContext<R, C> delegate)
            implements IRecipeTransferContext<R, C> {
        public int getTransferId() { return delegate.getTransferId(); }
        public void completeRecipeTransfer(RecipeTransferResult result) {
            delegate.completeRecipeTransfer(result == RecipeTransferResult.SUCCESS ? TransferContext.Result.SUCCESS : TransferContext.Result.REJECTED);
        }
        public R getRecipe() { return delegate.getRecipe(); }
        public RecipeType<R> getRecipeType() { return delegate.getRecipeType(); }
        public C getContainer() { return delegate.getContainer(); }
        public AbstractContainerScreen<C> getScreen() { return delegate.getScreen(); }
        public IRecipeSlotsView getRecipeSlots() { return delegate.getRecipeSlots(); }
        public Player getPlayer() { return delegate.getPlayer(); }
        public boolean isMaxTransfer() { return delegate.isMaxTransfer(); }
    }
}

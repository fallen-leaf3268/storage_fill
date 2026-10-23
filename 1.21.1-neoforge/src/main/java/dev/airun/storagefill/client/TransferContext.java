package dev.airun.storagefill.client;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

public interface TransferContext<R, C extends AbstractContainerMenu> {
    enum Result { SUCCESS, REJECTED }

    int getTransferId();
    void completeRecipeTransfer(Result result);
    R getRecipe();
    RecipeType<R> getRecipeType();
    C getContainer();
    AbstractContainerScreen<C> getScreen();
    IRecipeSlotsView getRecipeSlots();
    Player getPlayer();
    boolean isMaxTransfer();

    default TransferContext<R, C> withRecipeSlots(IRecipeSlotsView slots) {
        TransferContext<R, C> delegate = this;
        return new TransferContext<>() {
            public int getTransferId() { return delegate.getTransferId(); }
            public void completeRecipeTransfer(Result result) { delegate.completeRecipeTransfer(result); }
            public R getRecipe() { return delegate.getRecipe(); }
            public RecipeType<R> getRecipeType() { return delegate.getRecipeType(); }
            public C getContainer() { return delegate.getContainer(); }
            public AbstractContainerScreen<C> getScreen() { return delegate.getScreen(); }
            public IRecipeSlotsView getRecipeSlots() { return slots; }
            public Player getPlayer() { return delegate.getPlayer(); }
            public boolean isMaxTransfer() { return delegate.isMaxTransfer(); }
        };
    }
}

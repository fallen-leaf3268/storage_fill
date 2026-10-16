package dev.airun.storagefill.mixin;

import mezz.jei.common.transfer.RecipeTransferService;
import mezz.jei.gui.recipes.RecipeTransferButton;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

@Mixin(value = RecipeTransferButton.class, remap = false)
public interface RecipeTransferButtonAccessor {
    @Accessor("parentScreenSupplier")
    Supplier<AbstractContainerScreen<?>> storageFillParentScreenSupplier();

    @Accessor("recipeTransferService")
    RecipeTransferService storageFillRecipeTransferService();
}

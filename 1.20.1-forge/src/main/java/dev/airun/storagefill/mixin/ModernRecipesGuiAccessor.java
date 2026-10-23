package dev.airun.storagefill.mixin;

import mezz.jei.common.transfer.RecipeTransferService;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RecipesGui.class, remap = false)
public interface ModernRecipesGuiAccessor {
    @Accessor("recipeTransferService")
    RecipeTransferService storageFillRecipeTransferService();
}

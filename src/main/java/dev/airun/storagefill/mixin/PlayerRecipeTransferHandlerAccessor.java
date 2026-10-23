package dev.airun.storagefill.mixin;

import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.library.transfer.PlayerRecipeTransferHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PlayerRecipeTransferHandler.class, remap = false)
public interface PlayerRecipeTransferHandlerAccessor {
    @Accessor("handler")
    IRecipeTransferHandler<?, ?> storageFillHandler();

    @Accessor("handlerHelper")
    IRecipeTransferHandlerHelper storageFillHandlerHelper();
}

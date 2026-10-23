package dev.airun.storagefill.mixin;

import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.gui.elements.IconButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = IconButton.class, remap = false)
public interface RecipeQueryIconButtonAccessor {
    @Accessor("controller")
    IIconButtonController storageFillController();
}

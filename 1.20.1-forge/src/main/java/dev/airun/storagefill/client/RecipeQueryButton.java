package dev.airun.storagefill.client;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;

import java.util.function.Supplier;

public final class RecipeQueryButton implements IIconButtonController {
    private final RecipeQueryButtonModel model;

    public RecipeQueryButton(IRecipeLayoutDrawable<?> layout, Supplier<AbstractContainerScreen<?>> parentScreen) {
        model = new RecipeQueryButtonModel(layout, parentScreen);
    }

    public boolean isAvailable() { return model.isAvailable(); }

    @Override
    public boolean onPress(IJeiUserInput input) {
        return isAvailable() && model.onPress(input.isSimulate());
    }

    @Override
    public void initState(IButtonState button) {
        button.setIcon(model.icon());
        updateState(button);
    }

    @Override
    public void updateState(IButtonState button) {
        boolean available = isAvailable();
        button.setVisible(available);
        button.setActive(available && model.state().status() != RemoteInventory.Status.QUERYING);
    }

    @Override
    public void getTooltips(ITooltipBuilder tooltip) { model.getTooltips(tooltip); }

    @Override
    public void drawExtras(GuiGraphics graphics, Rect2i area, int mouseX, int mouseY, float partialTicks) {
        model.drawExtras(graphics, area, mouseX, mouseY, partialTicks);
    }
}

package dev.airun.storagefill.client;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.gui.elements.GuiIconToggleButton;
import mezz.jei.gui.input.UserInput;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

public final class LegacyRecipeQueryButton extends GuiIconToggleButton {
    private final RecipeQueryButtonModel model;

    public LegacyRecipeQueryButton(IRecipeLayoutDrawable<?> layout) {
        this(new RecipeQueryButtonModel(layout, StorageFillJeiPlugin::parent));
    }

    private LegacyRecipeQueryButton(RecipeQueryButtonModel model) {
        super(model.icon(), model.icon());
        this.model = model;
        tick();
    }

    public boolean isAvailable() { return model.isAvailable(); }

    public Rect2i getArea() {
        return new Rect2i(button.getX(), button.getY(), button.getWidth(), button.getHeight());
    }

    @Override
    public void tick() {
        boolean available = isAvailable();
        button.visible = available;
        button.active = available && model.state().status() != RemoteInventory.Status.QUERYING;
    }

    @Override
    protected void getTooltips(JeiTooltip tooltip) { model.getTooltips(tooltip); }

    @Override
    protected boolean isIconToggledOn() { return false; }

    @Override
    protected boolean onMouseClicked(UserInput input) {
        return isAvailable() && model.onPress(input.isSimulate());
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (!button.visible) return;
        super.draw(graphics, mouseX, mouseY, partialTicks);
        model.drawExtras(graphics, getArea(), mouseX, mouseY, partialTicks);
    }
}

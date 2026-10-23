package dev.airun.storagefill.client;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import net.minecraft.client.renderer.Rect2i;

public final class RecipeQueryButtonAreas {
    private RecipeQueryButtonAreas() { }

    public static Rect2i relative(IRecipeLayoutDrawable<?> layout, int index) {
        Rect2i button = ((RecipeQueryLayout) layout).storageFillBaseButtonArea();
        Rect2i border = layout.getRectWithBorder();
        Rect2i recipe = layout.getRect();
        int rows = Math.max(2, (border.getHeight() + 2) / (button.getHeight() + 2));
        int x = button.getX() + index / rows * (button.getWidth() + 2);
        int y = border.getY() - recipe.getY() + border.getHeight() - button.getHeight()
            - index % rows * (button.getHeight() + 2);
        return new Rect2i(x, y, button.getWidth(), button.getHeight());
    }

    public static Rect2i absolute(IRecipeLayoutDrawable<?> layout, int index) {
        Rect2i button = relative(layout, index);
        Rect2i recipe = layout.getRect();
        button.setPosition(button.getX() + recipe.getX(), button.getY() + recipe.getY());
        return button;
    }
}

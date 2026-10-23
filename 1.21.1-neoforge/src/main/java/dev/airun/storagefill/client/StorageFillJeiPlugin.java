package dev.airun.storagefill.client;

import dev.airun.storagefill.StorageFill;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;

@JeiPlugin
public final class StorageFillJeiPlugin implements IModPlugin {
    private static IRecipesGui recipesGui;

    @Override
    public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "recipe_query"); }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        registration.addRecipeButtonFactory(new IRecipeButtonControllerFactory() {
            @Override
            public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
                if (!(layout instanceof RecipeQueryLayout reservation)) return null;
                RecipeQueryButton button = new RecipeQueryButton(layout, StorageFillJeiPlugin::parent);
                reservation.storageFillReserveQueryButtons(button::isAvailable);
                return button;
            }
        });
    }

    public static AbstractContainerScreen<?> parent() {
        if (recipesGui == null) return null;
        var parent = recipesGui.getParentScreen().orElse(Minecraft.getInstance().screen);
        return parent instanceof AbstractContainerScreen<?> container ? container : null;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) { recipesGui = runtime.getRecipesGui(); }

    @Override
    public void onRuntimeUnavailable() { recipesGui = null; }

}

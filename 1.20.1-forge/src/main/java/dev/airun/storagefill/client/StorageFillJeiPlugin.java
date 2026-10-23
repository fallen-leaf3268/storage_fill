package dev.airun.storagefill.client;

import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.JeiCompatibility;
import dev.airun.storagefill.mixin.RecipesGuiAccessor;
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
    public ResourceLocation getPluginUid() { return new ResourceLocation(StorageFill.ID, "recipe_query"); }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        if (JeiCompatibility.CONTROLLER_BUTTONS) ModernButtons.register(registration);
    }

    public static AbstractContainerScreen<?> parent() {
        if (!(recipesGui instanceof RecipesGuiAccessor accessor)) return null;
        var parent = accessor.storageFillParentScreen();
        if (parent == null) parent = Minecraft.getInstance().screen;
        return parent instanceof AbstractContainerScreen<?> container ? container : null;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) { recipesGui = runtime.getRecipesGui(); }

    @Override
    public void onRuntimeUnavailable() { recipesGui = null; }

    private static final class ModernButtons {
        private static void register(IAdvancedRegistration registration) {
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
    }
}

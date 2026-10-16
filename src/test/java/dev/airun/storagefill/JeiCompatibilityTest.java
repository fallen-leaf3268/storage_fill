package dev.airun.storagefill;

import org.junit.jupiter.api.Test;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiCompatibilityTest {
    @Test
    void acceptsCurrentJeiVersion() throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("META-INF/mods.toml")) {
            assertTrue(stream != null, "mods.toml is missing");
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("versionRange=\"[15.59.0.212,16)\""),
                "JEI 15.59.0.212 is outside the declared dependency range");
        }
    }

    @Test
    void completionWrappersForwardOnlyOnce() throws Exception {
        AtomicInteger custom = new AtomicInteger();
        IRecipeTransferContext<Object, InventoryMenu> customDelegate = context(custom);
        Class<?> customType = Class.forName("dev.airun.storagefill.client.CustomTransfer$CompletionContext");
        var customConstructor = customType.getDeclaredConstructors()[0];
        customConstructor.setAccessible(true);
        var customContext = (IRecipeTransferContext<?, ?>) customConstructor.newInstance(customDelegate, 1);
        customContext.completeRecipeTransfer(RecipeTransferResult.REJECTED);
        customContext.completeRecipeTransfer(RecipeTransferResult.SUCCESS);
        assertEquals(1, custom.get());

        AtomicInteger deferred = new AtomicInteger();
        IRecipeTransferContext<Object, InventoryMenu> deferredDelegate = context(deferred);
        Class<?> deferredType = Class.forName("dev.airun.storagefill.client.ClientTransfer$DeferredContext");
        var deferredConstructor = deferredType.getDeclaredConstructors()[0];
        deferredConstructor.setAccessible(true);
        var deferredContext = (IRecipeTransferContext<?, ?>) deferredConstructor.newInstance(deferredDelegate, 2, null, null);
        deferredContext.completeRecipeTransfer(RecipeTransferResult.REJECTED);
        deferredContext.completeRecipeTransfer(RecipeTransferResult.SUCCESS);
        assertEquals(1, deferred.get());
    }

    @Test
    void distinguishesLegacyAndContextAwareHandlers() throws Exception {
        IRecipeTransferHandler<InventoryMenu, Object> legacy = handler(false);
        IRecipeTransferHandler<InventoryMenu, Object> contextAware = handler(true);
        var method = Class.forName("dev.airun.storagefill.client.CustomTransfer")
                .getDeclaredMethod("usesLegacyTransfer", IRecipeTransferHandler.class);
        method.setAccessible(true);
        assertTrue((boolean) method.invoke(null, legacy));
        assertFalse((boolean) method.invoke(null, contextAware));
    }

    @SuppressWarnings("unchecked")
    private static IRecipeTransferContext<Object, InventoryMenu> context(AtomicInteger completions) {
        return (IRecipeTransferContext<Object, InventoryMenu>) Proxy.newProxyInstance(
                JeiCompatibilityTest.class.getClassLoader(), new Class<?>[]{IRecipeTransferContext.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("completeRecipeTransfer")) {
                        completions.incrementAndGet();
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static IRecipeTransferHandler<InventoryMenu, Object> handler(boolean contextAware) {
        class LegacyHandler implements IRecipeTransferHandler<InventoryMenu, Object> {
            public Class<? extends InventoryMenu> getContainerClass() { return InventoryMenu.class; }
            public Optional<MenuType<InventoryMenu>> getMenuType() { return Optional.empty(); }
            public RecipeType<Object> getRecipeType() { return RecipeType.create("storage_fill", "test", Object.class); }
            public IRecipeTransferError transferRecipe(InventoryMenu menu, Object recipe, IRecipeSlotsView slots,
                    Player player, boolean maximum, boolean perform) { return null; }
        }
        if (!contextAware) return new LegacyHandler();
        return new LegacyHandler() {
            public IRecipeTransferError transferRecipe(IRecipeTransferContext<Object, InventoryMenu> context,
                    boolean perform) { return null; }
        };
    }
}

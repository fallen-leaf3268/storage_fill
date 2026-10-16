package dev.airun.storagefill.storage;

import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.mixin.StorageFillMixinPlugin;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class SophisticatedCoreHookGameTests {
    @GameTest(template = "empty", batch = "storage_core_hook_load")
    public static void nativeCoreInventoryHookClassLoadsWithFtb(GameTestHelper helper) throws ReflectiveOperationException {
        if (!ModList.get().isLoaded("ftbquests") || !ModList.get().isLoaded("sophisticatedcore")) {
            StorageFill.LOGGER.warn("SKIPPED Sophisticated Core hook ABI audit: FTB Quests or Sophisticated Core is absent");
            helper.succeed();
            return;
        }
        String coreVersion = ModList.get().getModContainerById("sophisticatedcore").orElseThrow().getModInfo().getVersion().toString();
        String expected = System.getProperty("storage_fill.audit.expected_core", "");
        if (!expected.isEmpty()) {
            helper.assertTrue(coreVersion.startsWith(expected), "Core ABI audit did not load the expected actual runtime version");
            helper.assertFalse(ModList.get().isLoaded("sophisticatedbackpacks"), "Core-only ABI audit unexpectedly loaded Sophisticated Backpacks");
        }
        StorageFill.LOGGER.info("AUDIT_CORE_RUNTIME core_version={} backpacks_present={} ftb_present={}",
            coreVersion, ModList.get().isLoaded("sophisticatedbackpacks"), ModList.get().isLoaded("ftbquests"));
        boolean backpacks = ModList.get().isLoaded("sophisticatedbackpacks");
        boolean applied = new StorageFillMixinPlugin().shouldApplyMixin(
            "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler",
            "dev.airun.storagefill.mixin.SophisticatedBackpacksInsertMixin");
        helper.assertTrue(applied == backpacks, "Backpack inventory hook was not restricted to the installed backpack module");
        if (!backpacks) {
            helper.succeed();
            return;
        }
        ClassLoader loader = SophisticatedCoreHookGameTests.class.getClassLoader();
        Class<?> inventory = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler", false, loader);
        Class<?> part = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.IInventoryPartHandler", false, loader);
        Class<?> inserter = Class.forName("org.apache.commons.lang3.function.TriFunction", false, loader);
        helper.assertTrue(inventory.getDeclaredMethod("insertItemInternal", int.class, ItemStack.class, boolean.class)
            .getReturnType() == ItemStack.class
            && part.getMethod("insertItem", int.class, ItemStack.class, boolean.class, inserter)
            .getReturnType() == ItemStack.class,
            "Sophisticated Core transformed insertion hook ABI does not match the installed native classes");
        helper.succeed();
    }
}

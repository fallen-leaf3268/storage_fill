package dev.airun.storagefill;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.util.Arrays;

@Mod.EventBusSubscriber(modid = StorageFill.ID, value = Dist.CLIENT)
public final class ClientSmokeTest {
    private static boolean done;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("storage_fill.smoke") || done || event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        client.options.pauseOnLostFocus = false;
        GLFW.glfwHideWindow(client.getWindow().getWindow());
        if (client.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen && client.getOverlay() == null) {
            client.setScreen(new TitleScreen());
        }
        if (client.getOverlay() != null || (!(client.screen instanceof TitleScreen)
                && !(Boolean.getBoolean("storage_fill.integration") && client.player != null))) return;
        try {
            Class<?> handler = Class.forName("mezz.jei.library.transfer.BasicRecipeTransferHandler");
            boolean injected = Arrays.stream(handler.getDeclaredMethods()).anyMatch(method -> method.getName().contains("storageFill"));
            if (!injected) throw new AssertionError("JEI storage Mixin was not applied");
            Class<?> transfers = Class.forName("mezz.jei.common.transfer.RecipeTransferService");
            if (Arrays.stream(transfers.getDeclaredMethods()).noneMatch(method -> method.getName().contains("storageFillCustom"))) {
                throw new AssertionError("Custom JEI transfer Mixin was not applied");
            }
            verifySourceNotices();
            verifyMaximumNoticeModes();
            verifyCustomPreviews();
            StorageFill.LOGGER.info("STORAGE_FILL_CLIENT_SMOKE_PASSED");
            done = true;
            if (!Boolean.getBoolean("storage_fill.integration")) client.stop();
        } catch (ClassNotFoundException error) {
            throw new AssertionError(error);
        }
    }

    private static void verifySourceNotices() {
        for (String key : java.util.List.of("remote_fill", "source.beyonddimensions", "source.refinedstorage", "source.ae2", "source.sophisticatedbackpacks", "source_separator", "transfer_max")) {
            if (!net.minecraft.client.resources.language.I18n.exists("storage_fill." + key)) {
                throw new AssertionError("Missing tooltip translation: " + key);
            }
        }
        var plank = StackKey.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS));
        var named = plank.stack(1);
        named.setHoverName(net.minecraft.network.chat.Component.literal("different"));
        var other = StackKey.of(named);
        var view = new dev.airun.storagefill.client.RemoteInventory.View(
                java.util.List.of(plank.stack(2), plank.stack(20), other.stack(20)), java.util.List.of(1, 2, 2), 1);
        var one = java.util.List.of(new AllocationPlanner.Choice<>(plank, 1));
        if (dev.airun.storagefill.client.ClientTransfer.sourceNotice(one, java.util.Map.of(plank, 1), view) != null) {
            throw new AssertionError("Local stock must not display a storage notice");
        }
        assertNotice(one, java.util.Map.of(), view, "beyonddimensions");
        assertNotice(java.util.List.of(new AllocationPlanner.Choice<>(plank, 3)), java.util.Map.of(), view,
                "beyonddimensions", "refinedstorage");
        assertNotice(java.util.List.of(new AllocationPlanner.Choice<>(plank, 2), new AllocationPlanner.Choice<>(plank, 2)),
                java.util.Map.of(plank, 1), view, "beyonddimensions", "refinedstorage");
        assertNotice(java.util.List.of(new AllocationPlanner.Choice<>(other, 1)), java.util.Map.of(plank, 64), view,
                "refinedstorage");
        var rsOnly = new dev.airun.storagefill.client.RemoteInventory.View(
                java.util.List.of(plank.stack(3)), java.util.List.of(2), 2);
        assertNotice(one, java.util.Map.of(), rsOnly, "refinedstorage");
        var bdOnly = new dev.airun.storagefill.client.RemoteInventory.View(
                java.util.List.of(plank.stack(3)), java.util.List.of(1), 3);
        assertNotice(one, java.util.Map.of(), bdOnly, "beyonddimensions");
        if (dev.airun.storagefill.client.ClientTransfer.sourceNotice(
                java.util.List.of(new AllocationPlanner.Choice<>(plank, 4)), java.util.Map.of(), bdOnly) != null) {
            throw new AssertionError("Insufficient remote stock must not promise a fill");
        }
        var repeatedRs = new dev.airun.storagefill.client.RemoteInventory.View(
                java.util.List.of(plank.stack(1), plank.stack(1)), java.util.List.of(2, 2), 4);
        assertNotice(java.util.List.of(new AllocationPlanner.Choice<>(plank, 2)), java.util.Map.of(), repeatedRs,
                "refinedstorage");
        StorageFill.LOGGER.info("STORAGE_FILL_SOURCE_NOTICE_TESTS_PASSED");
        var all = new dev.airun.storagefill.client.RemoteInventory.View(
                java.util.List.of(plank.stack(1), plank.stack(1), plank.stack(1), plank.stack(8)),
                java.util.List.of(1, 2, 3, 4), 5);
        assertNotice(java.util.List.of(new AllocationPlanner.Choice<>(plank, 3)), java.util.Map.of(), all,
                "beyonddimensions", "refinedstorage", "ae2");
        assertNotice(java.util.List.of(new AllocationPlanner.Choice<>(plank, 4)), java.util.Map.of(), all,
                "beyonddimensions", "refinedstorage", "ae2", "sophisticatedbackpacks");
        for (int id = 3; id <= 4; id++) {
            var single = new dev.airun.storagefill.client.RemoteInventory.View(
                    java.util.List.of(plank.stack(3)), java.util.List.of(id), 6);
            assertNotice(one, java.util.Map.of(), single, id == 3 ? "ae2" : "sophisticatedbackpacks");
        }
        var networks = dev.airun.storagefill.client.ClientTransfer.networkStock(java.util.Map.of(plank, 2), all);
        if (networks.get(plank) != 5) throw new AssertionError("Backpack stock leaked into network tier");
    }

    private static void verifyMaximumNoticeModes() {
        var plank = StackKey.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS));
        var choices = java.util.List.of(new AllocationPlanner.Choice<>(plank, 1));
        var remote = new dev.airun.storagefill.client.RemoteInventory.View(java.util.List.of(plank.stack(64)), java.util.List.of(1), 20);
        var known = dev.airun.storagefill.client.ClientTransfer.sourceNotice(choices, java.util.Map.of(), remote);
        var ordinary = tooltip(known, false);
        var maximum = tooltip(known, true);
        if (ordinary.size() != 2 || !ordinary.get(0).equals(net.minecraft.network.chat.Component.translatable("jei.tooltip.transfer"))) {
            throw new AssertionError("Maximum-capable notice must retain the native transfer action and source notice");
        }
        if (maximum.size() != 2 || !maximum.get(0).equals(net.minecraft.network.chat.Component.translatable("storage_fill.transfer_max"))
                || !maximum.get(1).equals(ordinary.get(1))) {
            throw new AssertionError("Shift notice did not change its action while preserving storage sources");
        }
        var unknown = dev.airun.storagefill.client.ClientTransfer.sourceNotice(choices, java.util.Map.of(), remote, false);
        for (boolean shift : new boolean[]{false, true}) {
            var lines = tooltip(unknown, shift);
            if (lines.size() != 2 || !lines.get(0).equals(net.minecraft.network.chat.Component.translatable("jei.tooltip.transfer"))
                    || !lines.get(1).equals(ordinary.get(1))) {
                throw new AssertionError("Unknown transfer rules must retain the native action and source notice without promising a maximum");
            }
        }
        var split = new dev.airun.storagefill.client.RemoteInventory.View(
                java.util.List.of(plank.stack(1), plank.stack(63)), java.util.List.of(1, 2), 21);
        var single = dev.airun.storagefill.client.ClientTransfer.sourceNotice(choices, java.util.Map.of(), split);
        var bulk = dev.airun.storagefill.client.ClientTransfer.sourceNotice(
                java.util.List.of(new AllocationPlanner.Choice<>(plank, 64)), java.util.Map.of(), split);
        var combined = dev.airun.storagefill.client.ClientTransfer.withMaximumNotice(single, bulk);
        String rs = net.minecraft.network.chat.Component.translatable("storage_fill.source.refinedstorage").getString();
        if (tooltip(combined, false).get(1).getString().contains(rs)
                || !tooltip(combined, true).get(1).getString().contains(rs)) {
            throw new AssertionError("Shift source notice did not include the source needed by the maximum plan");
        }
        StorageFill.LOGGER.info("STORAGE_FILL_SHIFT_TOOLTIP_TESTS_PASSED");
    }

    private static java.util.List<net.minecraft.network.chat.Component> tooltip(
            mezz.jei.api.recipe.transfer.IRecipeTransferError notice, boolean shift) {
        var tooltip = new mezz.jei.common.gui.JeiTooltip();
        try {
            var method = notice.getClass().getDeclaredMethod("getTooltip", mezz.jei.api.gui.builder.ITooltipBuilder.class, boolean.class);
            method.setAccessible(true);
            method.invoke(notice, tooltip, shift);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Unable to render the selected Shift tooltip state", error);
        }
        return tooltip.toLegacyToComponents();
    }

    private static void verifyCustomPreviews() {
        var inventory = new net.minecraft.world.entity.player.Inventory(null);
        var carrot = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARROT);
        var original = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 12);
        inventory.items.set(0, original);
        var helper = (mezz.jei.api.helpers.IStackHelper) java.lang.reflect.Proxy.newProxyInstance(
                ClientSmokeTest.class.getClassLoader(), new Class<?>[]{mezz.jei.api.helpers.IStackHelper.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("isEquivalent")) return net.minecraft.world.item.ItemStack.isSameItemSameTags(
                            (net.minecraft.world.item.ItemStack) arguments[0], (net.minecraft.world.item.ItemStack) arguments[1]);
                    throw new UnsupportedOperationException(method.getName());
                });
        var missing = new mezz.jei.api.recipe.transfer.IRecipeTransferError() {
            public Type getType() { return Type.USER_FACING; }
        };
        var remote = new dev.airun.storagefill.client.RemoteInventory.View(
                java.util.List.of(carrot.copyWithCount(64)), java.util.List.of(1), 12);
        var inputs = java.util.List.of(java.util.List.of(carrot));
        java.util.function.Supplier<mezz.jei.api.recipe.transfer.IRecipeTransferError> nativeCheck = () ->
                inventory.items.stream().anyMatch(stack -> stack.is(net.minecraft.world.item.Items.CARROT)) ? null : missing;
        if (nativeCheck.get() == null) throw new AssertionError("Custom fixture must initially be missing materials");
        var result = dev.airun.storagefill.client.CustomTransfer.preview(inventory, inputs, remote, false, helper, nativeCheck);
        if (result == null || result.required().size() != 1 || result.required().get(0).getCount() != 1
                || !result.notice().getType().allowsTransfer) throw new AssertionError("Registered handler preview was not enabled");
        for (boolean shifted : new boolean[]{false, true}) {
            var lines = tooltip(result.notice(), shifted);
            if (lines.size() != 2 || !lines.get(0).equals(net.minecraft.network.chat.Component.translatable("jei.tooltip.transfer"))) {
                throw new AssertionError("Generic custom top-up must not advertise an unsupported maximum transfer");
            }
        }
        if (inventory.items.get(0) != original || original.getCount() != 12 || nativeCheck.get() == null) {
            throw new AssertionError("Preview changed the real inventory");
        }
        var shift = dev.airun.storagefill.client.CustomTransfer.preview(inventory, inputs, remote, true, helper, nativeCheck);
        if (shift == null || shift.required().get(0).getCount() != 1) throw new AssertionError("Custom preview guessed bulk consumption");
        if (dev.airun.storagefill.client.CustomTransfer.preview(inventory, inputs, remote, false, helper, () -> missing) != null) {
            throw new AssertionError("A handler that still refuses must not be enabled");
        }
        var rejecting = carrot.copy();
        rejecting.getOrCreateTag().putInt("variant", 1);
        var accepted = carrot.copy();
        accepted.getOrCreateTag().putInt("variant", 2);
        var mutableRemote = new dev.airun.storagefill.client.RemoteInventory.View(
                java.util.List.of(accepted.copyWithCount(8)), java.util.List.of(1), 22);
        var mutating = (mezz.jei.api.helpers.IStackHelper) java.lang.reflect.Proxy.newProxyInstance(
                ClientSmokeTest.class.getClassLoader(), new Class<?>[]{mezz.jei.api.helpers.IStackHelper.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("isEquivalent")) throw new UnsupportedOperationException(method.getName());
                    var option = (net.minecraft.world.item.ItemStack) arguments[0];
                    var prototype = (net.minecraft.world.item.ItemStack) arguments[1];
                    boolean matches = net.minecraft.world.item.ItemStack.isSameItemSameTags(option, prototype);
                    option.setCount(0);
                    prototype.getOrCreateTag().putBoolean("poisoned", true);
                    prototype.setCount(0);
                    return matches;
                });
        var protectedPreview = dev.airun.storagefill.client.CustomTransfer.preview(inventory,
                java.util.List.of(java.util.List.of(rejecting, accepted)), mutableRemote, false, mutating, nativeCheck);
        if (protectedPreview == null || rejecting.getCount() != 1 || accepted.getCount() != 1
                || mutableRemote.inventory().get(0).getCount() != 8
                || mutableRemote.inventory().get(0).getTag().contains("poisoned")) {
            throw new AssertionError("A mutating matcher corrupted later candidates or shared storage records");
        }
        try {
            dev.airun.storagefill.client.CustomTransfer.simulate(inventory,
                    java.util.Collections.nCopies(36, carrot), () -> {
                        inventory.items.get(0).shrink(1);
                        throw new IllegalStateException("preview failure");
                    });
            throw new AssertionError("Missing injected preview exception");
        } catch (IllegalStateException expected) {
            if (inventory.items.get(0) != original || original.getCount() != 12) throw new AssertionError("Failed preview corrupted inventory");
        }
        for (int i = 0; i < 36; i++) inventory.items.set(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
        if (dev.airun.storagefill.client.CustomTransfer.preview(inventory, inputs, remote, false, helper, nativeCheck) != null) {
            throw new AssertionError("Full inventory should not enable top-up");
        }
        StorageFill.LOGGER.info("STORAGE_FILL_CUSTOM_PREVIEW_TESTS_PASSED");
    }

    private static void assertNotice(java.util.List<AllocationPlanner.Choice<StackKey>> choices,
            java.util.Map<StackKey, Integer> local, dev.airun.storagefill.client.RemoteInventory.View view,
            String... expectedSources) {
        var notice = dev.airun.storagefill.client.ClientTransfer.sourceNotice(choices, local, view);
        if (notice == null || !notice.getType().allowsTransfer || notice.getButtonHighlightColor() != 0
                || notice.getMissingCountHint() != 0) throw new AssertionError("Notice must keep normal transfer behavior");
        var tooltip = new mezz.jei.common.gui.JeiTooltip();
        notice.getTooltip(tooltip);
        var lines = tooltip.toLegacyToComponents();
        if (lines.size() != 2 || !lines.get(0).equals(net.minecraft.network.chat.Component.translatable("jei.tooltip.transfer"))) {
            throw new AssertionError("Supported maximum transfer must retain the native tooltip and source notice");
        }
        var names = net.minecraft.network.chat.Component.empty();
        for (String source : expectedSources) {
            if (!names.getSiblings().isEmpty()) names.append(net.minecraft.network.chat.Component.translatable("storage_fill.source_separator"));
            names.append(net.minecraft.network.chat.Component.translatable("storage_fill.source." + source));
        }
        String expected = net.minecraft.network.chat.Component.translatable("storage_fill.remote_fill", names).getString();
        if (!lines.get(1).getString().equals(expected)) throw new AssertionError("Incorrect sources: " + tooltip);
    }
}

package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.TransferService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class SophisticatedBackpacksGameTests {
    @GameTest(template = "empty")
    public static void realWrapperSnapshotSimulationExtractionAndRefund(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.roundTrip(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "storage_change_bridge")
    public static void backpackNativeInsertRecordsOnlyAcceptedPerform(GameTestHelper helper) {
        if (!installed(helper)) return;
        if (!ModList.get().isLoaded("ftbquests")) {
            StorageFill.LOGGER.warn("SKIPPED backpack insertion bridge GameTest: ftbquests is absent");
            helper.succeed();
            return;
        }
        Cases.roundTrip(helper, true);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void armorProviderAndDuplicateStorageAreHandled(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.providers(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void topUpPreservesBackpackAndSnapshotCapsEachKey(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.topUpAndCaps(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedTopUpWithVoidUpgradeRefundsToPlayer(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.failedTopUpRefund(helper);
        helper.succeed();
    }

    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("sophisticatedbackpacks")) return true;
        StorageFill.LOGGER.warn("SKIPPED Sophisticated Backpacks integration GameTest: sophisticatedbackpacks is absent");
        helper.succeed();
        return false;
    }

    private static final class Cases {
    private static void roundTrip(GameTestHelper helper) {
        roundTrip(helper, false);
    }

    private static void roundTrip(GameTestHelper helper, boolean changesOnly) {
        ServerPlayer player = player(helper);
        ItemStack backpack = backpack();
        var wrapper = wrapper(backpack);
        ItemStack first = tagged(1, 130);
        ItemStack second = tagged(2, 17);
        insertAll(wrapper.getInventoryHandler(), first);
        insertAll(wrapper.getInventoryHandler(), second);
        player.getInventory().setItem(0, backpack);
        List<StorageSource> sources = new ArrayList<>();
        SophisticatedBackpacksSource.find(player, sources);
        helper.assertTrue(sources.size() == 1, "Expected one carried backpack source");
        StorageSource source = sources.get(0);
        if (changesOnly) {
            var handler = wrapper.getInventoryHandler();
            StorageChanges.clear();
            helper.assertTrue(handler.insertItem(5, new ItemStack(Items.LAPIS_LAZULI, 13), true).isEmpty(),
                "Backpack native simulation did not accept the bridge test material");
            helper.assertFalse(StorageChanges.hasChanges(), "Backpack native simulation recorded an insertion change");
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                helper.assertFalse(handler.getStackInSlot(slot).is(Items.LAPIS_LAZULI),
                    "Backpack native insertion simulation changed materials");
            }
            helper.assertTrue(handler.insertItem(5, new ItemStack(Items.LAPIS_LAZULI, 13), false).isEmpty(),
                "Backpack native insertion did not accept the bridge test material");
            StorageIntegrationGameTests.assertRecordedChange(helper, source, Items.LAPIS_LAZULI);
            int stored = 0;
            int occupiedSlot = -1;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack present = handler.getStackInSlot(slot);
                if (present.is(Items.LAPIS_LAZULI)) {
                    stored += present.getCount();
                    occupiedSlot = slot;
                }
            }
            helper.assertTrue(stored == 13 && occupiedSlot >= 0,
                "Backpack native insertion bridge did not preserve the accepted quantity");
            helper.assertTrue(handler.insertItemOnlyToSlot(occupiedSlot, new ItemStack(Items.COAL), false).getCount() == 1,
                "Backpack bridge rejection fixture unexpectedly accepted a different item");
            helper.assertFalse(StorageChanges.hasChanges(), "Rejected backpack insertion recorded a storage change");
            return;
        }
        java.util.List<StorageSource> rediscovered = new java.util.ArrayList<>();
        SophisticatedBackpacksSource.find(player, rediscovered);
        helper.assertTrue(source.identity().equals(rediscovered.get(0).identity()), "Backpack identity changed on discovery");
        java.util.concurrent.atomic.AtomicInteger originalNotifications = new java.util.concurrent.atomic.AtomicInteger();
        wrapper.getInventoryHandler().addListener(ignored -> originalNotifications.incrementAndGet());
        helper.assertTrue(source.watch(ignored -> {}) == null, "Backpack source installed an unremovable callback");
        wrapper.getInventoryHandler().triggerOnChangeListeners(0);
        helper.assertTrue(originalNotifications.get() == 1, "Backpack source replaced original listeners");
        helper.assertTrue(source.sourceId() == StorageSource.SOPHISTICATED_BACKPACKS, "Wrong source id");
        StorageIntegrationGameTests.assertStreamingPreview(helper, source, 147);
        helper.assertTrue(count(source, tagged(1, 1)) == 130 && count(source, tagged(2, 1)) == 17,
            "Snapshot lost large count or NBT distinction");
        helper.assertTrue(source.extract(tagged(1, 1), 70, true).getCount() == 70
            && count(source, tagged(1, 1)) == 130, "Simulation changed contents");
        ItemStack taken = source.extract(tagged(1, 1), 70, false);
        helper.assertTrue(taken.getCount() == 70 && count(source, tagged(1, 1)) == 60,
            "Actual extraction returned wrong count");
        helper.assertTrue(source.extract(tagged(3, 1), 1, false).isEmpty(), "Extraction ignored NBT");
        ItemStack remainder = source.insert(taken);
        helper.assertTrue(remainder.getCount() == 70 && count(source, tagged(1, 1)) == 60,
            "Source refund unexpectedly wrote into backpack contents");
        insertAll(wrapper.getInventoryHandler(), remainder);
    }

    private static void providers(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack backpack = backpack();
        var wrapper = wrapper(backpack);
        insertAll(wrapper.getInventoryHandler(), new ItemStack(Items.EMERALD, 9));
        ItemStack duplicate = backpack.copy();
        player.getInventory().setItem(0, backpack);
        player.setItemSlot(EquipmentSlot.CHEST, duplicate);
        List<StorageSource> sources = new ArrayList<>();
        SophisticatedBackpacksSource.find(player, sources);
        helper.assertTrue(sources.size() == 1, "Duplicate contents UUID was counted twice");
        helper.assertTrue(count(sources.get(0), new ItemStack(Items.EMERALD)) == 9,
            "Duplicate backpack inflated snapshot contents");
        player.getInventory().setItem(0, ItemStack.EMPTY);
        helper.assertTrue(count(sources.get(0), new ItemStack(Items.EMERALD)) == 9,
            "Armor provider backpack was not revalidated");
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        helper.assertTrue(sources.get(0).snapshot(Set.of(Items.EMERALD)).isEmpty()
            && sources.get(0).extract(new ItemStack(Items.EMERALD), 1, false).isEmpty(),
            "Removed backpack remained accessible");
        StorageIntegrationGameTests.assertUnavailablePreview(helper, sources.get(0));
    }

    private static void topUpAndCaps(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack backpack = backpack();
        var wrapper = wrapper(backpack);
        wrapper.getInventoryHandler().setStackInSlot(0, tagged(1, StorageSources.MAX_COUNT));
        wrapper.getInventoryHandler().setStackInSlot(1, new ItemStack(Items.EMERALD, 9));
        wrapper.getInventoryHandler().setStackInSlot(2, tagged(1, 17));
        player.getInventory().setItem(0, backpack);
        List<StorageSource> sources = new ArrayList<>();
        SophisticatedBackpacksSource.find(player, sources);
        helper.assertTrue(sources.size() == 1 && count(sources.get(0), tagged(1, 1)) == StorageSources.MAX_COUNT
            && count(sources.get(0), new ItemStack(Items.EMERALD)) == 9,
            "Per-key snapshot cap hid a later candidate");
        StorageIntegrationGameTests.assertStreamingPreview(helper, sources.get(0), StorageSources.MAX_COUNT + 9);
        helper.assertTrue(TransferService.topUp(player, player.containerMenu.containerId,
            player.containerMenu.getStateId(), List.of(tagged(1, 2))), "Backpack top-up failed");
        helper.assertTrue(player.getInventory().getItem(0) == backpack,
            "Top-up replaced the carried backpack stack");
        int carried = player.getInventory().items.stream().filter(stack -> ItemStack.isSameItemSameTags(stack, tagged(1, 1)))
            .mapToInt(ItemStack::getCount).sum();
        helper.assertTrue(carried == 2 && wrapper.getInventoryHandler().getStackInSlot(0).getCount() == StorageSources.MAX_COUNT - 2,
            "Top-up lost or duplicated backpack contents");
    }

    private static void failedTopUpRefund(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack backpack = backpack();
        var wrapper = wrapper(backpack);
        insertAll(wrapper.getInventoryHandler(), tagged(4, 5));
        boolean previous = net.p3pp3rf1y.sophisticatedbackpacks.Config.SERVER.voidUpgrade.voidAlwaysEnabled.get();
        net.p3pp3rf1y.sophisticatedbackpacks.Config.SERVER.voidUpgrade.voidAlwaysEnabled.set(true);
        try {
            wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(ModItems.VOID_UPGRADE.get()));
            var voidWrapper = wrapper.getUpgradeHandler().getTypeWrappers(
                net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeItem.TYPE).get(0);
            voidWrapper.setVoidType(net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidType.ALWAYS);
            voidWrapper.setShouldWorkdInGUI(false);
            voidWrapper.getFilterLogic().setAllowList(true);
            voidWrapper.getFilterLogic().setMatchNbt(false);
            voidWrapper.getFilterLogic().getFilterHandler().setStackInSlot(0, new ItemStack(Items.DIAMOND));
            int beforeVoidInsertion = wrapper.getInventoryHandler().getStackInSlot(0).getCount();
            helper.assertTrue(voidWrapper.getVoidType() == net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidType.ALWAYS
                && voidWrapper.getFilterLogic().matchesFilter(tagged(4, 1)), "Void ALWAYS fixture configuration failed");
            helper.assertTrue(wrapper.getInventoryHandler().insertItem(1, tagged(4, 1), false).isEmpty()
                && wrapper.getInventoryHandler().getStackInSlot(1).isEmpty()
                && wrapper.getInventoryHandler().getStackInSlot(0).getCount() == beforeVoidInsertion,
                "Void ALWAYS fixture did not swallow matching real insertion");
            player.getInventory().setItem(0, backpack);
            List<StorageSource> found = new ArrayList<>();
            SophisticatedBackpacksSource.find(player, found);
            helper.assertTrue(found.get(0).insert(tagged(4, 1)).getCount() == 1
                && wrapper.getInventoryHandler().getStackInSlot(0).getCount() == beforeVoidInsertion,
                "Source insert exposed refund to void upgrade");
            wrapper.setInventorySlotChangeHandler(() -> {
                player.containerMenu.incrementStateId();
                throw new IllegalStateException("Injected write-after-callback failure");
            });
            int state = player.containerMenu.getStateId();
            helper.assertFalse(TransferService.topUp(player, player.containerMenu.containerId, state,
                List.of(tagged(4, 2))), "Changed menu state accepted top-up");
            int carried = player.getInventory().items.stream().filter(stack -> ItemStack.isSameItemSameTags(stack, tagged(4, 1)))
                .mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(player.getInventory().getItem(0) == backpack && carried == 2
                && wrapper.getInventoryHandler().getStackInSlot(0).getCount() == 3,
                "Write-after-throw refund was swallowed or duplicated");
        } finally {
            net.p3pp3rf1y.sophisticatedbackpacks.Config.SERVER.voidUpgrade.voidAlwaysEnabled.set(previous);
        }
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "sb-source-test"));
    }

    private static ItemStack backpack() {
        return new ItemStack(ModItems.BACKPACK.get());
    }

    private static net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper wrapper(ItemStack backpack) {
        return backpack.getCapability(CapabilityBackpackWrapper.getCapabilityInstance()).orElseThrow(IllegalStateException::new);
    }

    private static ItemStack tagged(int variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        stack.getOrCreateTag().putInt("sb_test_variant", variant);
        return stack;
    }

    private static void insertAll(net.minecraftforge.items.IItemHandler handler, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, false);
        }
        if (!remaining.isEmpty()) throw new AssertionError("Backpack setup did not fit");
    }

    private static int count(StorageSource source, ItemStack prototype) {
        return source.snapshot(Set.of(prototype.getItem())).stream()
            .filter(stack -> ItemStack.isSameItemSameTags(stack, prototype))
            .mapToInt(ItemStack::getCount).sum();
    }
    }
}

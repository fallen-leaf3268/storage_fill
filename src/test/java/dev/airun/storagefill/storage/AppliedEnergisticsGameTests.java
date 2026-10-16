package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.TransferService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class AppliedEnergisticsGameTests {
    @GameTest(template = "empty")
    public static void aeUnboundAndUnloadedWirelessRejected(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.invalidBindings(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void aeRealWirelessNetworkRoundTripAndRange(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.realNetwork(helper);
    }

    @GameTest(template = "empty", batch = "storage_change_bridge", timeoutTicks = 180)
    public static void aeNativeInsertRecordsOnlyAcceptedModulate(GameTestHelper helper) {
        if (!installed(helper)) return;
        if (!ModList.get().isLoaded("ftbquests")) {
            StorageFill.LOGGER.warn("SKIPPED AE2 insertion bridge GameTest: ftbquests is absent");
            helper.succeed();
            return;
        }
        Cases.realNetwork(helper, true);
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void aeTwoTerminalsCombinePowerForOneTargetedWithdrawal(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.splitPower(helper, 64, true);
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void aeTwoTerminalsDoNotCountOneInventoryTwice(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.splitPower(helper, 48, false);
    }

    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("ae2")) return true;
        StorageFill.LOGGER.warn("SKIPPED AE2 integration GameTest: ae2 is absent");
        helper.succeed();
        return false;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "ae-storage-it"));
    }

    private static List<StorageSource> sources(ServerPlayer player) {
        return StorageSources.find(player).stream().filter(source -> source.sourceId() == StorageSource.APPLIED_ENERGISTICS).toList();
    }

    private static StorageSource onlySource(GameTestHelper helper, ServerPlayer player) {
        List<StorageSource> sources = sources(player);
        helper.assertTrue(sources.size() == 1, "Expected one AE2 grid despite duplicate wireless terminals, got " + sources.size());
        return sources.get(0);
    }

    private static int count(StorageSource source, ItemStack prototype) {
        return source.snapshot(Set.of(prototype.getItem())).stream()
            .filter(stack -> ItemStack.isSameItemSameTags(stack, prototype)).mapToInt(ItemStack::getCount).sum();
    }

    private static ItemStack tagged(int variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        stack.getOrCreateTag().putInt("ae_storage_variant", variant);
        return stack;
    }

    private static final class Cases {
        private static ItemStack item(String name) {
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", name));
            if (item == null || item == Items.AIR) throw new AssertionError("Missing AE2 item " + name);
            return new ItemStack(item);
        }

        private static Block block(String name) {
            var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("ae2", name));
            if (block == null || block == Blocks.AIR) throw new AssertionError("Missing AE2 block " + name);
            return block;
        }

        private static void charge(ItemStack terminal) {
            ((appeng.items.tools.powered.WirelessTerminalItem) terminal.getItem()).injectAEPower(terminal,
                Double.MAX_VALUE, appeng.api.config.Actionable.MODULATE);
        }

        private static void invalidBindings(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            ItemStack terminal = item("wireless_terminal");
            player.getInventory().setItem(0, terminal);
            helper.assertTrue(sources(player).isEmpty(), "Unbound AE2 terminal exposed storage");
            charge(terminal);
            helper.assertTrue(sources(player).isEmpty(), "Charged unbound AE2 terminal exposed storage");
            BlockPos unloaded = new BlockPos(16000000, 80, 16000000);
            helper.assertTrue(!helper.getLevel().hasChunkAt(unloaded), "AE2 unloaded fixture chunk was unexpectedly present");
            appeng.items.tools.powered.WirelessTerminalItem.LINKABLE_HANDLER.link(terminal,
                GlobalPos.of(helper.getLevel().dimension(), unloaded));
            helper.assertTrue(sources(player).isEmpty(), "AE2 unloaded linked network exposed storage");
            helper.assertTrue(!helper.getLevel().hasChunkAt(unloaded), "AE2 discovery loaded a remote chunk");
            appeng.items.tools.powered.WirelessTerminalItem.LINKABLE_HANDLER.link(terminal,
                GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 1, 1))));
            helper.assertTrue(sources(player).isEmpty(), "AE2 binding without a real access point exposed storage");
        }

        private static void splitPower(GameTestHelper helper, int stored, boolean expectedSuccess) {
            BlockPos energyPos = new BlockPos(1, 1, 1);
            BlockPos accessPos = new BlockPos(1, 1, 0);
            helper.setBlock(energyPos, block("creative_energy_cell"));
            var accessState = block("wireless_access_point").defaultBlockState();
            helper.setBlock(accessPos, appeng.api.orientation.IOrientationStrategy.get(accessState)
                .setFacing(accessState, Direction.NORTH));
            helper.runAfterDelay(60, () -> {
                var access = (appeng.blockentity.networking.WirelessAccessPointBlockEntity)
                    helper.getLevel().getBlockEntity(helper.absolutePos(accessPos));
                helper.assertTrue(access != null && access.isActive(), "AE2 split-power access point unavailable");
                var grid = access.getGrid();
                var cell = appeng.api.storage.StorageCells.getCellInventory(item("item_storage_cell_1k"), () -> {});
                helper.assertTrue(cell != null, "AE2 split-power cell unavailable");
                appeng.api.storage.IStorageProvider provider = mounts -> mounts.mount(cell, 0);
                grid.getStorageService().addGlobalStorageProvider(provider);
                try {
                    ServerPlayer player = player(helper);
                    BlockPos absolute = helper.absolutePos(accessPos);
                    player.setPos(absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ() + 0.5);
                    ItemStack prototype = tagged(10, 1);
                    var key = appeng.api.stacks.AEItemKey.of(prototype);
                    var storage = grid.getStorageService().getInventory();
                    var actionSource = appeng.api.networking.security.IActionSource.ofPlayer(player);
                    helper.assertTrue(storage.insert(key, stored, appeng.api.config.Actionable.MODULATE, actionSource) == stored,
                        "AE2 split-power inventory seed failed");
                    ItemStack first = item("wireless_terminal");
                    ItemStack second = item("wireless_terminal");
                    var terminalItem = (appeng.items.tools.powered.WirelessTerminalItem) first.getItem();
                    for (ItemStack terminal : List.of(first, second)) {
                        appeng.items.tools.powered.WirelessTerminalItem.LINKABLE_HANDLER.link(terminal,
                            GlobalPos.of(helper.getLevel().dimension(), absolute));
                        terminalItem.injectAEPower(terminal, 32.0 / key.getAmountPerOperation(), appeng.api.config.Actionable.MODULATE);
                    }
                    player.getInventory().setItem(0, first);
                    player.getInventory().setItem(1, second);
                    double before = terminalItem.getAECurrentPower(first) + terminalItem.getAECurrentPower(second);
                    StorageSource source = onlySource(helper, player);
                    helper.assertTrue(source.extract(prototype, 64, true).getCount() == Math.min(stored, 64),
                        "AE2 targeted simulation did not combine terminal power or counted storage twice");
                    helper.assertTrue(terminalItem.getAECurrentPower(first) + terminalItem.getAECurrentPower(second) == before,
                        "AE2 combined simulation consumed terminal energy");
                    boolean success = TransferService.topUp(player, player.containerMenu.containerId,
                        player.containerMenu.getStateId(), List.of(prototype.copyWithCount(64)));
                    helper.assertTrue(success == expectedSuccess, "AE2 split-power top-up returned the wrong result");
                    int carried = player.getInventory().items.stream()
                        .filter(stack -> ItemStack.isSameItemSameTags(stack, prototype)).mapToInt(ItemStack::getCount).sum();
                    long remaining = storage.extract(key, 1000, appeng.api.config.Actionable.SIMULATE, actionSource);
                    helper.assertTrue(carried == (expectedSuccess ? 64 : 0) && remaining + carried == stored,
                        "AE2 split-power top-up lost or duplicated material");
                    double spent = before - terminalItem.getAECurrentPower(first) - terminalItem.getAECurrentPower(second);
                    helper.assertTrue(Math.abs(spent - (expectedSuccess ? 64.0 / key.getAmountPerOperation() : 0)) < 0.001,
                        "AE2 combined terminal power was charged incorrectly");
                    helper.assertTrue(player.getInventory().getItem(0) == first && player.getInventory().getItem(1) == second,
                        "AE2 combined top-up replaced terminal identities");
                    helper.succeed();
                } finally {
                    grid.getStorageService().removeGlobalStorageProvider(provider);
                }
            });
        }

        private static void realNetwork(GameTestHelper helper) {
            realNetwork(helper, false);
        }

        private static void realNetwork(GameTestHelper helper, boolean changesOnly) {
            BlockPos energyPos = new BlockPos(1, 1, 1);
            BlockPos accessPos = new BlockPos(1, 1, 0);
            helper.setBlock(energyPos, block("creative_energy_cell"));
            var accessState = block("wireless_access_point").defaultBlockState();
            accessState = appeng.api.orientation.IOrientationStrategy.get(accessState).setFacing(accessState, Direction.NORTH);
            helper.setBlock(accessPos, accessState);
            helper.runAfterDelay(60, () -> {
                var access = (appeng.blockentity.networking.WirelessAccessPointBlockEntity)
                    helper.getLevel().getBlockEntity(helper.absolutePos(accessPos));
                helper.assertTrue(access != null && access.isActive(), "Real AE2 wireless access point did not become active");
                var grid = access.getGrid();
                helper.assertTrue(grid != null && grid.getEnergyService().isNetworkPowered(), "Real AE2 grid is not powered");
                ItemStack cellStack = item("item_storage_cell_1k");
                var cell = appeng.api.storage.StorageCells.getCellInventory(cellStack, () -> {});
                helper.assertTrue(cell != null, "Real AE2 1k item cell did not create MEStorage");
                appeng.api.storage.IStorageProvider provider = mounts -> mounts.mount(cell, 0);
                grid.getStorageService().addGlobalStorageProvider(provider);
                var storage = grid.getStorageService().getInventory();
                ServerPlayer player = player(helper);
                var actionSource = appeng.api.networking.security.IActionSource.ofPlayer(player);
                var key = appeng.api.stacks.AEItemKey.of(tagged(7, 1));
                var variant = appeng.api.stacks.AEItemKey.of(tagged(8, 1));
                helper.assertTrue(storage.insert(key, 128, appeng.api.config.Actionable.MODULATE, actionSource) == 128
                    && storage.insert(variant, 16, appeng.api.config.Actionable.MODULATE, actionSource) == 16,
                    "Real AE2 mounted cell did not accept test materials");
                BlockPos absolute = helper.absolutePos(accessPos);
                player.setPos(absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ() + 0.5);
                ItemStack terminal = item("wireless_terminal");
                ItemStack craftingTerminal = item("wireless_crafting_terminal");
                appeng.items.tools.powered.WirelessTerminalItem.LINKABLE_HANDLER.link(terminal,
                    GlobalPos.of(helper.getLevel().dimension(), absolute));
                appeng.items.tools.powered.WirelessTerminalItem.LINKABLE_HANDLER.link(craftingTerminal,
                    GlobalPos.of(helper.getLevel().dimension(), absolute));
                charge(terminal);
                charge(craftingTerminal);
                var terminalItem = (appeng.items.tools.powered.WirelessTerminalItem) terminal.getItem();
                var craftingItem = (appeng.items.tools.powered.WirelessTerminalItem) craftingTerminal.getItem();
                player.getInventory().setItem(0, terminal);
                player.getInventory().setItem(1, craftingTerminal);
                var originalMenu = player.containerMenu;
                StorageSource source = onlySource(helper, player);
                helper.assertTrue(source.identity().equals(onlySource(helper, player).identity()), "AE2 source identity changed on discovery");
                if (changesOnly) {
                    try {
                        var material = appeng.api.stacks.AEItemKey.of(Items.LAPIS_LAZULI);
                        StorageChanges.clear();
                        helper.assertTrue(storage.insert(material, 13, appeng.api.config.Actionable.SIMULATE, actionSource) == 13,
                            "AE2 native simulation did not accept the bridge test material");
                        helper.assertFalse(StorageChanges.hasChanges(), "AE2 native simulation recorded an insertion change");
                        helper.assertTrue(storage.extract(material, 13, appeng.api.config.Actionable.SIMULATE, actionSource) == 0,
                            "AE2 native insertion simulation changed stored materials");
                        helper.assertTrue(storage.insert(material, 13, appeng.api.config.Actionable.MODULATE, actionSource) == 13,
                            "AE2 native insertion did not accept the bridge test material");
                        StorageIntegrationGameTests.assertRecordedChange(helper, source, Items.LAPIS_LAZULI);
                        helper.assertTrue(storage.extract(material, 13, appeng.api.config.Actionable.SIMULATE, actionSource) == 13,
                            "AE2 native insertion bridge did not preserve the accepted quantity");
                    } finally {
                        grid.getStorageService().removeGlobalStorageProvider(provider);
                    }
                    helper.succeed();
                    return;
                }
                try {
                    var dirty = appeng.me.service.StorageService.class.getDeclaredField("cachedStacksNeedUpdate");
                    dirty.setAccessible(true);
                    grid.getStorageService().invalidateCache();
                    double countPower = terminalItem.getAECurrentPower(terminal) + craftingItem.getAECurrentPower(craftingTerminal);
                    var prepared = source.prepareCount(tagged(7, 1), stack -> ItemStack.isSameItemSameTags(stack, tagged(7, 1)));
                    helper.assertTrue(dirty.getBoolean(grid.getStorageService()) && prepared.count(64) == 64
                        && dirty.getBoolean(grid.getStorageService()),
                        "AE2 sufficient exact prepared count rebuilt the full native warehouse cache");
                    helper.assertTrue(prepared.count(200) == 128 && !dirty.getBoolean(grid.getStorageService()),
                        "AE2 partial exact prepared count skipped its native fallback or mixed NBT variants");
                    helper.assertTrue(source.prepareCount(tagged(7, 1), stack -> true).count(144) == 144,
                        "AE2 prepared fallback omitted other matching variants or counted its exact key twice");
                    helper.assertTrue(terminalItem.getAECurrentPower(terminal) + craftingItem.getAECurrentPower(craftingTerminal) == countPower
                        && storage.extract(key, 1000, appeng.api.config.Actionable.SIMULATE, actionSource) == 128,
                        "AE2 exact prepared counting consumed terminal energy or stored materials");
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("AE2 exact-count regression could not inspect the native cache state", failure);
                }
                helper.assertTrue(source.watch(ignored -> {}) == null, "AE2 source installed a full-network watcher");
                helper.assertTrue(source.preview(Set.of(Items.DIAMOND)).size() == 2, "AE2 cached preview omitted NBT variants");
                ItemStack replacement = terminal.copy();
                player.getInventory().setItem(0, ItemStack.EMPTY);
                player.getInventory().setItem(1, ItemStack.EMPTY);
                player.getInventory().setItem(8, replacement);
                helper.assertTrue(count(source, tagged(7, 1)) == 128, "Persistent AE2 source lost moved replacement terminal");
                player.getInventory().setItem(8, ItemStack.EMPTY);
                player.getInventory().setItem(0, terminal);
                player.getInventory().setItem(1, craftingTerminal);
                helper.assertTrue(count(source, tagged(7, 1)) == 128 && count(source, tagged(8, 1)) == 16,
                    "AE2 snapshot merged NBT or duplicated network counts");
                double before = terminalItem.getAECurrentPower(terminal) + craftingItem.getAECurrentPower(craftingTerminal);
                StorageIntegrationGameTests.assertStreamingPreview(helper, source, 144);
                helper.assertTrue(count(source, tagged(7, 1)) == 128 && count(source, tagged(8, 1)) == 16
                    && terminalItem.getAECurrentPower(terminal) + craftingItem.getAECurrentPower(craftingTerminal) == before,
                    "AE2 streaming preview consumed materials or terminal energy");
                helper.assertTrue(source.extract(tagged(7, 1), 32, true).getCount() == 32 && count(source, tagged(7, 1)) == 128,
                    "AE2 simulation failed or changed storage");
                helper.assertTrue(source.extract(tagged(9, 1), 1, false).isEmpty(), "AE2 extraction ignored NBT");
                helper.assertTrue(terminalItem.getAECurrentPower(terminal) + craftingItem.getAECurrentPower(craftingTerminal) == before,
                    "AE2 snapshot, simulation, or failed extraction consumed terminal energy");
                ItemStack first = source.extract(tagged(7, 1), 32, false);
                ItemStack second = source.extract(tagged(7, 1), 8, false);
                helper.assertTrue(first.getCount() == 32 && second.getCount() == 8 && count(source, tagged(7, 1)) == 88,
                    "AE2 actual extraction has incorrect counts");
                double after = terminalItem.getAECurrentPower(terminal) + craftingItem.getAECurrentPower(craftingTerminal);
                helper.assertTrue(Math.abs(before - after - 40.0 / key.getAmountPerOperation()) < 0.001,
                    "AE2 actual extraction did not charge native terminal energy");
                terminalItem.extractAEPower(terminal, Double.MAX_VALUE, appeng.api.config.Actionable.MODULATE);
                craftingItem.extractAEPower(craftingTerminal, Double.MAX_VALUE, appeng.api.config.Actionable.MODULATE);
                helper.assertTrue(sources(player).isEmpty() && source.extract(tagged(7, 1), 1, false).isEmpty(),
                    "Depleted AE2 terminals still allowed extraction");
                StorageIntegrationGameTests.assertUnavailablePreview(helper, source);
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> true, Long.MAX_VALUE) == 0,
                    "AE2 count accepted depleted wireless terminals");
                helper.assertTrue(source.insert(first).isEmpty() && source.insert(second).isEmpty(), "AE2 depleted-terminal rollback failed");
                helper.assertTrue(storage.extract(key, 1000, appeng.api.config.Actionable.SIMULATE, actionSource) == 128,
                    "AE2 rollback lost or duplicated materials");
                source.finish(0);
                charge(terminal);
                source = onlySource(helper, player);
                var carrotKey = appeng.api.stacks.AEItemKey.of(Items.CARROT);
                helper.assertTrue(storage.insert(carrotKey, 9, appeng.api.config.Actionable.MODULATE, actionSource) == 9,
                    "AE2 top-up fixture did not seed nine carrots");
                double topUpPower = terminalItem.getAECurrentPower(terminal);
                helper.assertTrue(TransferService.topUp(player, player.containerMenu.containerId,
                    player.containerMenu.getStateId(), List.of(new ItemStack(Items.CARROT, 3))),
                    "Public TransferService.topUp did not discover and consume real AE2 storage");
                helper.assertTrue(player.getInventory().getItem(0) == terminal && player.getInventory().getItem(1) == craftingTerminal,
                    "AE2 top-up replaced carried terminal object identities");
                helper.assertTrue(Math.abs(topUpPower - terminalItem.getAECurrentPower(terminal)
                    - 3.0 / carrotKey.getAmountPerOperation()) < 0.001, "AE2 top-up erased or bypassed terminal energy deduction");
                int localCarrots = player.getInventory().items.stream().filter(stack -> stack.is(Items.CARROT))
                    .mapToInt(ItemStack::getCount).sum();
                helper.assertTrue(localCarrots == 3 && storage.extract(carrotKey, 99,
                    appeng.api.config.Actionable.SIMULATE, actionSource) == 6,
                    "AE2 top-up did not conserve nine carrots between backpack and network");
                double suppliedPower = terminalItem.getAECurrentPower(terminal);
                helper.assertTrue(TransferService.topUp(player, player.containerMenu.containerId,
                    player.containerMenu.getStateId(), List.of(new ItemStack(Items.CARROT, 3))),
                    "Already-satisfied AE2 top-up unexpectedly failed");
                helper.assertTrue(terminalItem.getAECurrentPower(terminal) == suppliedPower
                    && storage.extract(carrotKey, 99, appeng.api.config.Actionable.SIMULATE, actionSource) == 6,
                    "Already-satisfied AE2 top-up withdrew extra materials or energy");
                helper.assertTrue(source.insert(tagged(7, 2)).isEmpty() && count(source, tagged(7, 1)) == 130,
                    "AE2 ordinary powered insertion failed");
                helper.assertTrue(source.insert(tagged(7, 6000)).isEmpty(), "AE2 cap fixture insertion failed");
                helper.assertTrue(count(source, tagged(7, 1)) == StorageSources.MAX_COUNT,
                    "AE2 snapshot count was not capped");
                double beforeCountPower = terminalItem.getAECurrentPower(terminal)
                    + craftingItem.getAECurrentPower(craftingTerminal);
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> {
                    helper.assertTrue(stack.getCount() == 1, "AE2 matcher received aggregate quantities");
                    return ItemStack.isSameItemSameTags(stack, tagged(7, 1));
                }, Long.MAX_VALUE) == 6130, "AE2 count retained JEI cap or merged NBT variants");
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> true, 6100) == 6100,
                    "AE2 count ignored the requested limit");
                helper.assertTrue(source.changeIdentity() == storage && terminalItem.getAECurrentPower(terminal)
                    + craftingItem.getAECurrentPower(craftingTerminal) == beforeCountPower
                    && storage.extract(key, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE, actionSource) == 6130,
                    "AE2 count changed storage identity, terminal energy, or stored materials");
                helper.assertTrue(player.containerMenu == originalMenu, "AE2 adapter opened or replaced a menu");
                player.setPos(absolute.getX() + 10000.5, absolute.getY() + 0.5, absolute.getZ() + 0.5);
                helper.assertTrue(sources(player).isEmpty() && source.snapshot(Set.of(Items.DIAMOND)).isEmpty()
                    && source.extract(tagged(7, 1), 1, false).isEmpty(), "AE2 source ignored wireless distance");
                StorageIntegrationGameTests.assertUnavailablePreview(helper, source);
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> true, Long.MAX_VALUE) == 0,
                    "AE2 count ignored wireless range");
                player.setPos(absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ() + 0.5);
                player.getInventory().setItem(0, ItemStack.EMPTY);
                charge(craftingTerminal);
                helper.assertTrue(count(onlySource(helper, player), tagged(7, 1)) == StorageSources.MAX_COUNT,
                    "Native AE2 wireless crafting terminal did not independently provide access");
                helper.setBlock(energyPos, Blocks.AIR);
                helper.runAfterDelay(30, () -> {
                    helper.assertTrue(sources(player).isEmpty(), "Unpowered real AE2 network still allowed wireless access");
                    grid.getStorageService().removeGlobalStorageProvider(provider);
                    helper.succeed();
                });
            });
        }
    }
}

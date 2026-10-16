package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StorageFill;
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
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class StorageTopologyGameTests {
    @GameTest(template = "empty", batch = "storage_rs_filled_disk", timeoutTicks = 160)
    public static void rsNativeFilledDiskInsertionRecordsChangedNetwork(GameTestHelper helper) {
        if (installed(helper, "refinedstorage")) RefinedStorageCases.disk(helper);
    }

    @GameTest(template = "empty", batch = "storage_ae_filled_cell", timeoutTicks = 180)
    public static void aeNativeFilledCellMountRecordsChangedNetwork(GameTestHelper helper) {
        if (installed(helper, "ae2")) AppliedEnergisticsCases.cell(helper);
    }

    private static boolean installed(GameTestHelper helper, String modId) {
        if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded(modId)) return true;
        StorageFill.LOGGER.warn("SKIPPED storage topology GameTest: FTB Quests or {} is absent", modId);
        helper.succeed();
        return false;
    }

    private static Block block(String modId, String name) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(modId, name));
        if (block == null || block == Blocks.AIR) throw new AssertionError("Missing native storage block " + modId + ":" + name);
        return block;
    }

    private static void assertTopologyChange(GameTestHelper helper, StorageSource source) {
        StorageChanges.Batch batch = StorageChanges.drain();
        helper.assertTrue(!batch.allSources() && batch.changes().size() == 1
            && batch.changes().get(0).sourceId() == source.sourceId()
            && batch.changes().get(0).identity() == source.changeIdentity()
            && batch.changes().get(0).allItems(),
            "Native stored inventory entered the network without one coalesced topology notification");
    }

    private static final class RefinedStorageCases {
        @SuppressWarnings("unchecked")
        private static void disk(GameTestHelper helper) {
            BlockPos controller = new BlockPos(1, 1, 1);
            BlockPos transmitter = new BlockPos(2, 1, 1);
            BlockPos drivePos = new BlockPos(0, 1, 1);
            helper.setBlock(controller, block("refinedstorage", "creative_controller"));
            helper.setBlock(transmitter, block("refinedstorage", "wireless_transmitter"));
            helper.setBlock(drivePos, block("refinedstorage", "disk_drive"));
            helper.runAfterDelay(40, () -> {
                UUID diskId = null;
                try {
                    var controllerEntity = (com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity)
                        helper.getLevel().getBlockEntity(helper.absolutePos(controller));
                    var drive = (com.refinedmods.refinedstorage.blockentity.DiskDriveBlockEntity)
                        helper.getLevel().getBlockEntity(helper.absolutePos(drivePos));
                    helper.assertTrue(controllerEntity != null && drive != null, "RS topology fixture has no native controller or disk drive");
                    var network = controllerEntity.getNetwork();
                    helper.assertTrue(network.canRun() && drive.getNode().isActive(), "RS topology fixture native disk drive is inactive");
                    ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "rs-disk-topology"));
                    BlockPos absolute = helper.absolutePos(controller);
                    player.setPos(absolute.getX() + 0.5, absolute.getY() + 1.5, absolute.getZ() + 0.5);
                    ItemStack terminal = new ItemStack(com.refinedmods.refinedstorage.RSItems.WIRELESS_GRID.get());
                    var binding = terminal.getOrCreateTag();
                    binding.putInt("NodeX", absolute.getX());
                    binding.putInt("NodeY", absolute.getY());
                    binding.putInt("NodeZ", absolute.getZ());
                    binding.putString("Dimension", helper.getLevel().dimension().location().toString());
                    var energy = terminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(IllegalStateException::new);
                    energy.receiveEnergy(Integer.MAX_VALUE, false);
                    player.getInventory().setItem(0, terminal);
                    List<StorageSource> sources = new ArrayList<>();
                    RefinedStorageSource.find(player, sources);
                    helper.assertTrue(sources.size() == 1
                        && sources.get(0).countMatching(Items.IRON_INGOT, stack -> true, 1000) == 0,
                        "RS topology fixture did not start with one accessible empty native drive");
                    var diskItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation("refinedstorage", "1k_storage_disk"));
                    helper.assertTrue(diskItem instanceof com.refinedmods.refinedstorage.item.StorageDiskItem,
                        "RS topology fixture cannot find the native 1k storage disk item");
                    ItemStack diskStack = new ItemStack(diskItem);
                    diskItem.inventoryTick(diskStack, helper.getLevel(), player, 1, false);
                    diskId = ((com.refinedmods.refinedstorage.item.StorageDiskItem) diskItem).getId(diskStack);
                    var manager = com.refinedmods.refinedstorage.apiimpl.API.instance().getStorageDiskManager(helper.getLevel());
                    var disk = (com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk<ItemStack>) manager.getByStack(diskStack);
                    helper.assertTrue(disk != null
                        && disk.insert(new ItemStack(Items.IRON_INGOT, 64), 64, com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty(),
                        "RS topology fixture could not fill the native unmounted disk");
                    int power = energy.getEnergyStored();
                    StorageChanges.clear();
                    helper.assertTrue(drive.getNode().getDisks().insertItem(0, diskStack, false).isEmpty(),
                        "RS native disk drive rejected the filled disk");
                    helper.assertTrue(sources.get(0).countMatching(Items.IRON_INGOT, stack -> true, 1000) == 64
                        && disk.getStored() == 64 && energy.getEnergyStored() == power,
                        "RS native filled disk did not become readable without changing its contents or terminal energy");
                    assertTopologyChange(helper, sources.get(0));
                    helper.succeed();
                } finally {
                    helper.setBlock(drivePos, Blocks.AIR);
                    helper.setBlock(transmitter, Blocks.AIR);
                    helper.setBlock(controller, Blocks.AIR);
                    if (diskId != null) com.refinedmods.refinedstorage.apiimpl.API.instance()
                        .getStorageDiskManager(helper.getLevel()).remove(diskId);
                    StorageChanges.clear();
                }
            });
        }
    }

    private static final class AppliedEnergisticsCases {
        private static void cell(GameTestHelper helper) {
            BlockPos energyPos = new BlockPos(1, 1, 1);
            BlockPos accessPos = new BlockPos(1, 1, 0);
            helper.setBlock(energyPos, block("ae2", "creative_energy_cell"));
            var accessState = block("ae2", "wireless_access_point").defaultBlockState();
            helper.setBlock(accessPos, appeng.api.orientation.IOrientationStrategy.get(accessState)
                .setFacing(accessState, Direction.NORTH));
            helper.runAfterDelay(60, () -> {
                appeng.api.storage.IStorageProvider provider = null;
                appeng.api.networking.IGrid grid = null;
                try {
                    var access = (appeng.blockentity.networking.WirelessAccessPointBlockEntity)
                        helper.getLevel().getBlockEntity(helper.absolutePos(accessPos));
                    helper.assertTrue(access != null && access.isActive(), "AE2 topology fixture native access point is inactive");
                    grid = access.getGrid();
                    ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "ae-cell-topology"));
                    BlockPos absolute = helper.absolutePos(accessPos);
                    player.setPos(absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ() + 0.5);
                    var terminalItem = (appeng.items.tools.powered.WirelessTerminalItem)
                        ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", "wireless_terminal"));
                    ItemStack terminal = new ItemStack(terminalItem);
                    appeng.items.tools.powered.WirelessTerminalItem.LINKABLE_HANDLER.link(terminal,
                        GlobalPos.of(helper.getLevel().dimension(), absolute));
                    terminalItem.injectAEPower(terminal, Double.MAX_VALUE, appeng.api.config.Actionable.MODULATE);
                    player.getInventory().setItem(0, terminal);
                    List<StorageSource> sources = new ArrayList<>();
                    AppliedEnergisticsSource.find(player, sources);
                    helper.assertTrue(sources.size() == 1
                        && sources.get(0).countMatching(Items.IRON_INGOT, stack -> true, 1000) == 0,
                        "AE2 topology fixture did not start with an accessible empty native grid");
                    ItemStack cellStack = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", "item_storage_cell_1k")));
                    var cell = appeng.api.storage.StorageCells.getCellInventory(cellStack, () -> {});
                    helper.assertTrue(cell != null, "AE2 topology fixture did not create a native 1k item cell");
                    var key = appeng.api.stacks.AEItemKey.of(Items.IRON_INGOT);
                    var actionSource = appeng.api.networking.security.IActionSource.ofPlayer(player);
                    helper.assertTrue(cell.insert(key, 64, appeng.api.config.Actionable.MODULATE, actionSource) == 64,
                        "AE2 topology fixture could not fill its unmounted native cell");
                    double power = terminalItem.getAECurrentPower(terminal);
                    StorageChanges.clear();
                    provider = mounts -> mounts.mount(cell, 0);
                    grid.getStorageService().addGlobalStorageProvider(provider);
                    helper.assertTrue(sources.get(0).countMatching(Items.IRON_INGOT, stack -> true, 1000) == 64
                        && cell.extract(key, 1000, appeng.api.config.Actionable.SIMULATE, actionSource) == 64
                        && terminalItem.getAECurrentPower(terminal) == power,
                        "AE2 native filled cell mount did not become readable without changing contents or terminal energy");
                    assertTopologyChange(helper, sources.get(0));
                    helper.succeed();
                } finally {
                    if (grid != null && provider != null) grid.getStorageService().removeGlobalStorageProvider(provider);
                    helper.setBlock(accessPos, Blocks.AIR);
                    helper.setBlock(energyPos, Blocks.AIR);
                    StorageChanges.clear();
                }
            });
        }
    }
}

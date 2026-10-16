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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
public final class AppliedEnergisticsExternalDepositGameTests {
    @GameTest(template = "empty", batch = "storage_ae_external_deposit", timeoutTicks = 180)
    public static void nativeStorageBusUpdateRecordsDirectChestDeposit(GameTestHelper helper) {
        if (!ModList.get().isLoaded("ftbquests") || !ModList.get().isLoaded("ae2")) {
            StorageFill.LOGGER.warn("SKIPPED AE2 external deposit audit: FTB Quests or AE2 is absent");
            helper.succeed();
            return;
        }
        Cases.externalDeposit(helper);
    }

    private static final class Cases {
        private static Block block(String name) {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("ae2", name));
            if (block == null || block == Blocks.AIR) throw new AssertionError("Missing native AE2 block " + name);
            return block;
        }

        private static void externalDeposit(GameTestHelper helper) {
            BlockPos energyPos = new BlockPos(1, 1, 1);
            BlockPos accessPos = new BlockPos(1, 1, 0);
            BlockPos cablePos = new BlockPos(2, 1, 1);
            BlockPos chestPos = new BlockPos(3, 1, 1);
            Runnable cleanup = () -> {
                helper.setBlock(cablePos, Blocks.AIR);
                helper.setBlock(chestPos, Blocks.AIR);
                helper.setBlock(accessPos, Blocks.AIR);
                helper.setBlock(energyPos, Blocks.AIR);
                StorageChanges.clear();
            };
            try {
                helper.setBlock(energyPos, block("creative_energy_cell"));
                var accessState = block("wireless_access_point").defaultBlockState();
                helper.setBlock(accessPos, appeng.api.orientation.IOrientationStrategy.get(accessState)
                    .setFacing(accessState, Direction.NORTH));
                helper.setBlock(chestPos, Blocks.CHEST);
                var cable = appeng.api.parts.PartHelper.setPart(helper.getLevel(), helper.absolutePos(cablePos),
                    null, null, appeng.core.definitions.AEParts.GLASS_CABLE.item(appeng.api.util.AEColor.TRANSPARENT));
                var bus = appeng.api.parts.PartHelper.setPart(helper.getLevel(), helper.absolutePos(cablePos),
                    Direction.EAST, null, appeng.core.definitions.AEParts.STORAGE_BUS.asItem());
                helper.assertTrue(cable != null && bus != null, "AE2 external audit did not place a real cable and storage bus");
                helper.runAfterDelay(60, () -> {
                    try {
                        var access = (appeng.blockentity.networking.WirelessAccessPointBlockEntity)
                            helper.getLevel().getBlockEntity(helper.absolutePos(accessPos));
                        helper.assertTrue(access != null && access.isActive() && bus.isActive(),
                            "AE2 external audit wireless access point or native storage bus is inactive");
                        var grid = access.getGrid();
                        var node = bus.getActionableNode();
                        helper.assertTrue(grid != null && node != null && node.getGrid() == grid,
                            "AE2 external audit bus is not connected to the wireless terminal grid");
                        var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(chestPos));
                        helper.assertTrue(chest != null && chest.isEmpty(), "AE2 external audit native chest is unavailable or occupied");
                        ServerPlayer player = new FakePlayer(helper.getLevel(),
                            new GameProfile(UUID.randomUUID(), "ae-external"));
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
                        helper.assertTrue(sources.size() == 1, "AE2 external audit cannot access the real storage-bus grid");
                        StorageSource source = sources.get(0);
                        var storage = grid.getStorageService().getInventory();
                        var actionSource = appeng.api.networking.security.IActionSource.ofPlayer(player);
                        var control = appeng.api.stacks.AEItemKey.of(Items.LAPIS_LAZULI);
                        StorageChanges.clear();
                        helper.assertTrue(storage.insert(control, 1, appeng.api.config.Actionable.SIMULATE, actionSource) == 1
                            && !StorageChanges.hasChanges(), "AE2 external audit insertion simulation did not stay read-only");
                        helper.assertTrue(storage.insert(control, 1, appeng.api.config.Actionable.MODULATE, actionSource) == 1,
                            "AE2 external audit normal native insertion control failed");
                        StorageChanges.Batch controlChanges = StorageChanges.drain();
                        helper.assertTrue(controlChanges.changes().stream().anyMatch(change -> change.identity() == source.changeIdentity()
                            && change.sourceId() == StorageSource.APPLIED_ENERGISTICS
                            && (change.allItems() || change.items().contains(Items.LAPIS_LAZULI))),
                            "AE2 external audit normal native insertion did not produce the expected bridge event");
                        helper.assertTrue(storage.extract(control, 1, appeng.api.config.Actionable.MODULATE, actionSource) == 1,
                            "AE2 external audit could not remove its native insertion control material");
                        bus.tickingRequest(node, 1);
                        grid.getStorageService().invalidateCache();
                        helper.assertTrue(chest.isEmpty() && source.countMatching(Items.IRON_INGOT, stack -> true, 1000) == 0,
                            "AE2 external audit warehouse did not start empty");
                        double terminalPower = terminalItem.getAECurrentPower(terminal);
                        StorageChanges.clear();
                        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
                        chest.setChanged();
                        bus.tickingRequest(node, 1);
                        grid.getStorageService().invalidateCache();
                        helper.assertTrue(source.countMatching(Items.IRON_INGOT, stack -> true, 1000) == 64
                            && chest.getItem(0).getCount() == 64 && terminalItem.getAECurrentPower(terminal) == terminalPower,
                            "Direct native chest deposit was not visible and extractable through the real AE2 storage bus");
                        StorageChanges.Batch changes = StorageChanges.drain();
                        helper.assertTrue(changes.changes().stream().anyMatch(change -> change.identity() == source.changeIdentity()
                            && change.sourceId() == StorageSource.APPLIED_ENERGISTICS
                            && (change.allItems() || change.items().contains(Items.IRON_INGOT))),
                            "Native AE2 storage bus gained sixty-four extractable chest items without a deposit detection event");
                        bus.tickingRequest(node, 1);
                        helper.assertFalse(StorageChanges.hasChanges(), "Unchanged native AE2 storage bus recorded an insertion");
                        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 32));
                        chest.setChanged();
                        bus.tickingRequest(node, 1);
                        grid.getStorageService().invalidateCache();
                        helper.assertFalse(StorageChanges.hasChanges(), "Native AE2 storage-bus stock decrease recorded an insertion");
                        helper.assertTrue(source.countMatching(Items.IRON_INGOT, stack -> true, 1000) == 32
                            && chest.getItem(0).getCount() == 32 && terminalItem.getAECurrentPower(terminal) == terminalPower,
                            "AE2 external notification changed materials or terminal energy");
                        helper.succeed();
                    } finally {
                        cleanup.run();
                    }
                });
            } catch (RuntimeException | Error error) {
                cleanup.run();
                throw error;
            }
        }
    }
}

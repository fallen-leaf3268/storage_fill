package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StorageFill;
import net.minecraft.core.BlockPos;
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
public final class RefinedStorageExternalDepositGameTests {
    @GameTest(template = "empty", batch = "storage_rs_external_deposit", timeoutTicks = 160)
    public static void nativeExternalStorageUpdateRecordsDirectContainerDeposit(GameTestHelper helper) {
        if (!ModList.get().isLoaded("ftbquests") || !ModList.get().isLoaded("refinedstorage")) {
            StorageFill.LOGGER.warn("SKIPPED RS external deposit audit: FTB Quests or Refined Storage is absent");
            helper.succeed();
            return;
        }
        Cases.externalDeposit(helper);
    }

    private static final class Cases {
        private static Block block(String name) {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage", name));
            if (block == null || block == Blocks.AIR) throw new AssertionError("Missing native RS block " + name);
            return block;
        }

        private static void externalDeposit(GameTestHelper helper) {
            BlockPos controller = new BlockPos(1, 1, 1);
            BlockPos transmitter = new BlockPos(2, 1, 1);
            helper.setBlock(controller, block("creative_controller"));
            helper.setBlock(transmitter, block("wireless_transmitter"));
            helper.runAfterDelay(40, () -> {
                try {
                    var controllerEntity = (com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity)
                        helper.getLevel().getBlockEntity(helper.absolutePos(controller));
                    helper.assertTrue(controllerEntity != null, "RS external deposit audit controller missing");
                    var network = controllerEntity.getNetwork();
                    helper.assertTrue(network.canRun(), "RS external deposit audit network is not running");
                    ServerPlayer player = new FakePlayer(helper.getLevel(),
                        new GameProfile(UUID.randomUUID(), "rs-external"));
                    BlockPos absolute = helper.absolutePos(controller);
                    player.setPos(absolute.getX() + 0.5, absolute.getY() + 1.5, absolute.getZ() + 0.5);
                    ItemStack terminal = new ItemStack(com.refinedmods.refinedstorage.RSItems.WIRELESS_GRID.get());
                    var binding = terminal.getOrCreateTag();
                    binding.putInt("NodeX", absolute.getX());
                    binding.putInt("NodeY", absolute.getY());
                    binding.putInt("NodeZ", absolute.getZ());
                    binding.putString("Dimension", helper.getLevel().dimension().location().toString());
                    terminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(IllegalStateException::new)
                        .receiveEnergy(Integer.MAX_VALUE, false);
                    player.getInventory().setItem(0, terminal);
                    var handler = new net.minecraftforge.items.ItemStackHandler(1);
                    var context = new com.refinedmods.refinedstorage.api.storage.externalstorage.IExternalStorageContext() {
                        public int getPriority() { return 0; }
                        public com.refinedmods.refinedstorage.api.storage.AccessType getAccessType() {
                            return com.refinedmods.refinedstorage.api.storage.AccessType.INSERT_EXTRACT;
                        }
                        public boolean acceptsItem(ItemStack stack) { return true; }
                        public boolean acceptsFluid(net.minecraftforge.fluids.FluidStack stack) { return true; }
                    };
                    var external = new com.refinedmods.refinedstorage.apiimpl.storage.externalstorage.ItemExternalStorage(
                        context, () -> handler, false);
                    network.getItemStorageCache().getStorages().add(external);
                    external.getStacks();
                    StorageChanges.clear();
                    helper.assertTrue(handler.insertItem(0, new ItemStack(Items.IRON_INGOT, 64), false).isEmpty(),
                        "Direct external-container deposit audit did not actually insert its materials");
                    external.update(network);
                    List<StorageSource> sources = new ArrayList<>();
                    RefinedStorageSource.find(player, sources);
                    helper.assertTrue(sources.size() == 1
                        && sources.get(0).countMatching(Items.IRON_INGOT, stack -> true, 1000) == 64
                        && handler.getStackInSlot(0).getCount() == 64,
                        "Direct external deposit was not visible and extractable through the real RS source");
                    helper.assertTrue(StorageChanges.hasChanges(),
                        "Native RS external storage cache gained sixty-four extractable items without a deposit detection event");
                    StorageChanges.Batch batch = StorageChanges.drain();
                    helper.assertTrue(batch.changes().stream().anyMatch(change -> change.identity() == network
                        && change.sourceId() == StorageSource.REFINED_STORAGE
                        && (change.allItems() || change.items().contains(Items.IRON_INGOT))),
                        "Direct RS external deposit did not identify its current network and changed material");
                    external.update(network);
                    helper.assertFalse(StorageChanges.hasChanges(), "Unchanged native RS external storage recorded an insertion");
                    handler.extractItem(0, 32, false);
                    external.update(network);
                    helper.assertFalse(StorageChanges.hasChanges(), "Native RS external storage decrease recorded an insertion");
                    helper.assertTrue(handler.getStackInSlot(0).getCount() == 32
                        && sources.get(0).countMatching(Items.IRON_INGOT, stack -> true, 1000) == 32,
                        "RS external change notification modified the actual materials or current count");
                    helper.succeed();
                } finally {
                    helper.setBlock(transmitter, Blocks.AIR);
                    helper.setBlock(controller, Blocks.AIR);
                    StorageChanges.clear();
                }
            });
        }
    }
}

package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StorageFill;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
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
public final class RefinedStorageCountGameTests {
    @GameTest(template = "empty", batch = "rs_count_many_variants", timeoutTicks = 240)
    public static void nativeTenBlockCountsHandleManyVariantsWithoutChangingStock(GameTestHelper helper) {
        if (installed(helper)) Cases.run(helper, Fixture::manyVariants);
    }

    @GameTest(template = "empty", batch = "rs_count_access", timeoutTicks = 160)
    public static void nativeCountsRevalidateAccessTypeTerminalAndRange(GameTestHelper helper) {
        if (installed(helper)) Cases.run(helper, Fixture::access);
    }

    @GameTest(template = "empty", batch = "rs_count_unknown", timeoutTicks = 160)
    public static void nativeWrapperWithRestrictedCustomParentStillSimulates(GameTestHelper helper) {
        if (installed(helper)) Cases.run(helper, Fixture::unknownParent);
    }

    @GameTest(template = "empty", batch = "rs_count_external", timeoutTicks = 160)
    public static void mixedExternalVisibleButUnextractableSlotsDoNotIncreaseCounts(GameTestHelper helper) {
        if (installed(helper)) Cases.run(helper, Fixture::external);
    }

    @GameTest(template = "empty", batch = "rs_count_long", timeoutTicks = 160)
    public static void nativeLongCountsExceedSaturatedDisplayCacheWithoutOverflow(GameTestHelper helper) {
        if (installed(helper)) Cases.run(helper, Fixture::longCounts);
    }

    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("refinedstorage")) return true;
        StorageFill.LOGGER.warn("SKIPPED native RS count GameTest: Refined Storage is absent");
        helper.succeed();
        return false;
    }

    private static ItemStack tagged(int variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        stack.getOrCreateTag().putInt("rs_count_variant", variant);
        return stack;
    }

    private static final class Cases {
        private static Block block(String name) {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage", name));
            if (block == null || block == Blocks.AIR) throw new AssertionError("Missing native RS block " + name);
            return block;
        }

        private static void run(GameTestHelper helper, java.util.function.Consumer<Fixture> test) {
            BlockPos controller = new BlockPos(1, 1, 1);
            BlockPos transmitter = new BlockPos(2, 1, 1);
            List<BlockPos> positions = new ArrayList<>();
            for (int y = 0; y < 3 && positions.size() < 10; y++) {
                for (int z = 0; z < 3 && positions.size() < 10; z++) {
                    for (int x = 0; x < 3 && positions.size() < 10; x++) {
                        BlockPos position = new BlockPos(x, y, z);
                        if (!position.equals(controller) && !position.equals(transmitter)) positions.add(position);
                    }
                }
            }
            for (BlockPos position : positions) helper.setBlock(position, block("64k_storage_block"));
            helper.setBlock(controller, block("creative_controller"));
            helper.setBlock(transmitter, block("wireless_transmitter"));
            helper.runAfterDelay(40, () -> {
                try {
                    Fixture fixture = new Fixture(helper, controller, positions);
                    test.accept(fixture);
                    helper.assertTrue(fixture.terminalEnergy.getEnergyStored() == fixture.initialEnergy,
                        "Read-only native RS count consumed terminal energy");
                    helper.succeed();
                } finally {
                    helper.setBlock(transmitter, Blocks.AIR);
                    helper.setBlock(controller, Blocks.AIR);
                    for (BlockPos position : positions) helper.setBlock(position, Blocks.AIR);
                }
            });
        }
    }

    private static final class Fixture {
        private final GameTestHelper helper;
        private final com.refinedmods.refinedstorage.api.network.INetwork network;
        private final ServerPlayer player;
        private final StorageSource source;
        private final ItemStack terminal;
        private final net.minecraftforge.energy.IEnergyStorage terminalEnergy;
        private final int initialEnergy;
        private final List<com.refinedmods.refinedstorage.apiimpl.network.node.storage.StorageNetworkNode> nodes;

        private Fixture(GameTestHelper helper, BlockPos controller, List<BlockPos> positions) {
            this.helper = helper;
            var controllerEntity = (com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(controller));
            helper.assertTrue(controllerEntity != null, "Native RS count controller missing");
            network = controllerEntity.getNetwork();
            helper.assertTrue(network.canRun() && network.getItemStorageCache().getStorages().size() == 10,
                "Native RS count fixture did not attach ten active real 64k storage blocks");
            nodes = positions.stream().map(position -> ((com.refinedmods.refinedstorage.blockentity.StorageBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(position))).getNode()).toList();
            player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "rs-count"));
            BlockPos absolute = helper.absolutePos(controller);
            player.setPos(absolute.getX() + 0.5, absolute.getY() + 1.5, absolute.getZ() + 0.5);
            terminal = new ItemStack(com.refinedmods.refinedstorage.RSItems.WIRELESS_GRID.get());
            var binding = terminal.getOrCreateTag();
            binding.putInt("NodeX", absolute.getX());
            binding.putInt("NodeY", absolute.getY());
            binding.putInt("NodeZ", absolute.getZ());
            binding.putString("Dimension", helper.getLevel().dimension().location().toString());
            terminalEnergy = terminal.getCapability(ForgeCapabilities.ENERGY)
                .orElseThrow(() -> new AssertionError("Native RS terminal has no energy capability"));
            terminalEnergy.receiveEnergy(Integer.MAX_VALUE, false);
            initialEnergy = terminalEnergy.getEnergyStored();
            player.getInventory().setItem(0, terminal);
            List<StorageSource> sources = new ArrayList<>();
            RefinedStorageSource.find(player, sources);
            helper.assertTrue(sources.size() == 1, "Native RS count fixture did not discover its wireless source");
            source = sources.get(0);
        }

        private void insert(ItemStack stack) {
            helper.assertTrue(network.insertItem(stack, stack.getCount(),
                com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty(), "Native RS count seed was rejected");
        }

        private long count(long limit) { return source.countMatching(Items.DIAMOND, stack -> true, limit); }

        private void manyVariants() {
            List<Item> items = List.of(Items.DIAMOND, Items.EMERALD, Items.IRON_INGOT,
                Items.GOLD_INGOT, Items.COPPER_INGOT, Items.COAL, Items.REDSTONE, Items.LAPIS_LAZULI, Items.QUARTZ);
            for (int index = 0; index < 10_000; index++) {
                ItemStack stack = new ItemStack(items.get(index % 9), 64);
                stack.getOrCreateTag().putInt("rs_count_variant", index / 9);
                insert(stack);
            }
            helper.assertTrue(network.getItemStorageCache().getList().getStacks().size() == 10_000,
                "Native RS count fixture omitted seeded NBT keys");
            long diamonds = 1_112L * 64L;
            helper.assertTrue(count(Long.MAX_VALUE) == diamonds && count(diamonds + 1) == diamonds,
                "Native RS count omitted variants or invented stock when the requested quantity was unavailable");
            helper.assertTrue(count(6_000) == 6_000 && count(0) == 0,
                "Native RS count ignored its requested limit");
            ItemStack tail = tagged(1_111, 1);
            StorageSource.CountQuery exact = source.prepareCount(tail,
                stack -> ItemStack.isSameItemSameTags(stack, tail));
            helper.assertTrue(exact.count(Long.MAX_VALUE) == 64,
                "Native RS count did not isolate the last matching NBT variant");
            helper.assertTrue(source.countMatching(Items.DIAMOND,
                stack -> ItemStack.isSameItemSameTags(stack, tagged(2_000, 1)), Long.MAX_VALUE) == 0,
                "Native RS count invented an absent NBT variant");
            helper.assertTrue(source.countMatching(Items.NETHER_STAR, stack -> true, 64) == 0,
                "Native RS count included an unrelated item");
            helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> {
                helper.assertTrue(stack.getCount() == 1, "Native RS matcher received an aggregate quantity");
                stack.getOrCreateTag().putInt("mutated_by_matcher", 1);
                stack.setCount(0);
                return true;
            }, Long.MAX_VALUE) == diamonds, "Native RS matcher mutation changed quantity accounting");
            long total = 0;
            int keys = 0;
            for (var storage : network.getItemStorageCache().getStorages()) {
                for (ItemStack stack : storage.getStacks()) {
                    helper.assertTrue(stack.getCount() == 64 && !stack.getOrCreateTag().contains("mutated_by_matcher"),
                        "Native RS count exposed mutable real storage stacks");
                    total += stack.getCount();
                    keys++;
                }
            }
            helper.assertTrue(keys == 10_000 && total == 640_000, "Native RS count changed backed warehouse contents");
            var taken = network.extractItem(tail, 17, com.refinedmods.refinedstorage.api.util.IComparer.COMPARE_NBT,
                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            helper.assertTrue(taken.getCount() == 17 && exact.count(Long.MAX_VALUE) == 47,
                "Prepared native RS count reused a stale material quantity");
            insert(taken);
            helper.assertTrue(exact.count(Long.MAX_VALUE) == 64, "Prepared native RS count did not see restored stock");
        }

        private void access() {
            insert(new ItemStack(Items.DIAMOND, 64));
            helper.assertTrue(count(Long.MAX_VALUE) == 64, "Native RS access test seed was not countable");
            for (var node : nodes) node.setAccessType(com.refinedmods.refinedstorage.api.storage.AccessType.INSERT);
            helper.assertTrue(count(Long.MAX_VALUE) == 0, "Native RS count included insert-only storage");
            for (var node : nodes) node.setAccessType(com.refinedmods.refinedstorage.api.storage.AccessType.EXTRACT);
            helper.assertTrue(count(Long.MAX_VALUE) == 64, "Native RS count excluded extract-only storage");
            StorageSource.CountQuery prepared = source.prepareCount(new ItemStack(Items.DIAMOND), stack -> true);
            player.getInventory().setItem(0, ItemStack.EMPTY);
            helper.assertTrue(prepared.count(64) == 0, "Prepared native RS count retained a removed terminal");
            player.getInventory().setItem(0, terminal);
            player.setPos(player.getX() + 10_000, player.getY(), player.getZ());
            helper.assertTrue(prepared.count(64) == 0, "Prepared native RS count bypassed wireless range");
        }

        private void unknownParent() {
            insert(new ItemStack(Items.DIAMOND, 64));
            RestrictedDisk disk = new RestrictedDisk();
            var wrapper = new com.refinedmods.refinedstorage.apiimpl.network.node.storage.ItemStorageWrapperStorageDisk(nodes.get(0), disk);
            disk.insert(new ItemStack(Items.DIAMOND), 128, com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            network.getItemStorageCache().getStorages().add(wrapper);
            network.getItemStorageCache().add(new ItemStack(Items.DIAMOND), 128, false, false);
            helper.assertTrue(count(Long.MAX_VALUE) == 64 && disk.simulations > 0,
                "Native wrapper bypassed a third-party parent's denied extraction");
            disk.permitted = 17;
            helper.assertTrue(count(Long.MAX_VALUE) == 81 && count(70) == 70,
                "Mixed native and restricted storage was double-counted or ignored actual extraction limits");
            helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> {
                stack.getOrCreateTag().putInt("mutated_by_matcher", 1);
                stack.setCount(0);
                return true;
            }, Long.MAX_VALUE) == 81, "Mutable matcher changed the prototype used for custom storage simulation");
            helper.assertTrue(disk.getStored() == 128, "Simulated custom storage count consumed materials");
        }

        private void external() {
            insert(new ItemStack(Items.DIAMOND, 64));
            var handler = new net.minecraftforge.items.ItemStackHandler(2) {
                @Override
                public ItemStack extractItem(int slot, int amount, boolean simulate) {
                    helper.assertTrue(simulate, "External count performed an actual extraction");
                    return slot == 0 ? ItemStack.EMPTY : super.extractItem(slot, amount, true);
                }
            };
            handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 128));
            handler.setStackInSlot(1, tagged(7, 19));
            var context = new com.refinedmods.refinedstorage.api.storage.externalstorage.IExternalStorageContext() {
                public int getPriority() { return 0; }
                public com.refinedmods.refinedstorage.api.storage.AccessType getAccessType() {
                    return com.refinedmods.refinedstorage.api.storage.AccessType.INSERT_EXTRACT;
                }
                public boolean acceptsItem(ItemStack stack) { return true; }
                public boolean acceptsFluid(net.minecraftforge.fluids.FluidStack stack) { return true; }
            };
            var external = new com.refinedmods.refinedstorage.apiimpl.storage.externalstorage.ItemExternalStorage(context, () -> handler, false);
            network.getItemStorageCache().getStorages().add(external);
            for (ItemStack stack : external.getStacks()) network.getItemStorageCache().add(stack, stack.getCount(), false, false);
            helper.assertTrue(count(Long.MAX_VALUE) == 83 && count(70) == 70,
                "External display cache bypassed extraction restrictions or duplicated native disk quantities");
            helper.assertTrue(source.countMatching(Items.DIAMOND,
                stack -> ItemStack.isSameItemSameTags(stack, tagged(7, 1)), Long.MAX_VALUE) == 19,
                "Mixed external count lost exact NBT matching");
            helper.assertTrue(handler.getStackInSlot(0).getCount() == 128 && handler.getStackInSlot(1).getCount() == 19,
                "External read-only count changed slot contents");
        }

        private void longCounts() {
            for (int i = 0; i < 2; i++) {
                var disk = new com.refinedmods.refinedstorage.apiimpl.storage.disk.ItemStorageDisk(null, -1, null);
                disk.setSettings(null, () -> com.refinedmods.refinedstorage.api.storage.AccessType.INSERT_EXTRACT);
                helper.assertTrue(disk.insert(new ItemStack(Items.DIAMOND), Integer.MAX_VALUE,
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty(), "Native infinite disk seed failed");
                network.getItemStorageCache().getStorages().add(disk);
                network.getItemStorageCache().add(new ItemStack(Items.DIAMOND), Integer.MAX_VALUE, false, false);
            }
            helper.assertTrue(network.getItemStorageCache().getList().getCount(new ItemStack(Items.DIAMOND)) == Integer.MAX_VALUE,
                "Native RS display cache did not exhibit its documented per-variant integer saturation");
            helper.assertTrue(count(3_000_000_000L) == 3_000_000_000L && count(Long.MAX_VALUE) == 4_294_967_294L,
                "Native RS count overflowed or inherited the saturated display cache quantity");
            helper.assertTrue(count(64) == 64, "Native long RS count ignored a small task limit");
        }
    }

    private static final class RestrictedDisk extends com.refinedmods.refinedstorage.apiimpl.storage.disk.ItemStorageDisk {
        private int simulations;
        private int permitted;

        private RestrictedDisk() { super(null, -1, null); }

        @Override
        public ItemStack extract(ItemStack stack, int amount, int flags, com.refinedmods.refinedstorage.api.util.Action action) {
            if (action != com.refinedmods.refinedstorage.api.util.Action.SIMULATE) throw new AssertionError("Custom count performed extraction");
            simulations++;
            return permitted == 0 ? ItemStack.EMPTY : super.extract(stack, Math.min(permitted, amount), flags, action);
        }
    }
}

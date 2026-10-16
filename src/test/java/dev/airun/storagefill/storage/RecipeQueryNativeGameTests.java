package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StackKey;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class RecipeQueryNativeGameTests {
    @GameTest(template = "empty", batch = "recipe_query_native_bd", timeoutTicks = 300)
    public static void bdRecipeQueryUsesNativeKeysAndRevokedMembership(GameTestHelper helper) {
        if (installed(helper, "beyonddimensions")) BeyondDimensions.run(helper);
    }

    @GameTest(template = "empty", batch = "recipe_query_native_sb", timeoutTicks = 300)
    public static void backpackRecipeQueryReadsWholeExactStockWithoutConsuming(GameTestHelper helper) {
        if (installed(helper, "sophisticatedbackpacks")) Backpack.run(helper);
    }

    @GameTest(template = "empty", batch = "recipe_query_native_rs", timeoutTicks = 300)
    public static void rsRecipeQueryReadsNativeNbtVariantsAndPreservesTerminalEnergy(GameTestHelper helper) {
        if (installed(helper, "refinedstorage")) RefinedStorage.run(helper);
    }

    @GameTest(template = "empty", batch = "recipe_query_native_ae", timeoutTicks = 300)
    public static void aeRecipeQueryAvoidsColdEnumerationForExactAndConfirmsUnknownVariants(GameTestHelper helper) {
        if (installed(helper, "ae2")) AppliedEnergistics.run(helper);
    }

    private static boolean installed(GameTestHelper helper, String mod) {
        if (ModList.get().isLoaded(mod)) return true;
        StorageFill.LOGGER.warn("SKIPPED native recipe query GameTest: {} absent", mod);
        helper.succeed();
        return false;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "native-query"));
    }

    private static ItemStack tagged(int variant, int count) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count);
        stack.getOrCreateTag().putInt("variant", variant);
        return stack;
    }

    private static Map<StackKey, Integer> read(GameTestHelper helper, ServerPlayer player, ItemStack hint) {
        var session = RecipeQueryService.start(List.of(hint));
        Map<StackKey, Integer> result = new LinkedHashMap<>();
        try {
            for (int step = 0; step < 64; step++) {
                var batch = RecipeQueryService.next(player, session);
                helper.assertTrue(batch.stacks().size() == batch.sourceIds().size() && batch.stacks().size() <= 256,
                    "Native query returned malformed rows");
                for (ItemStack stack : batch.stacks()) {
                    StackKey key = StackKey.of(stack);
                    helper.assertTrue(!result.containsKey(key), "Single-source native query emitted a key twice");
                    result.put(key, stack.getCount());
                }
                if (batch.exhausted()) return result;
            }
            throw new AssertionError("Native recipe query did not finish within its protocol batch bound");
        } finally {
            RecipeQueryService.cancel(session);
        }
    }

    private static Block block(String mod, String name) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(mod, name));
        if (block == null || block == Blocks.AIR) throw new AssertionError("Missing native fixture block " + mod + ":" + name);
        return block;
    }

    private static final class BeyondDimensions {
        static void run(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            ServerPlayer owner = player(helper);
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.createNewNetForPlayer(owner, 1000000, 32);
            helper.assertTrue(network != null, "Native BD query fixture unavailable");
            try {
                network.addPlayer(player.getUUID());
                com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.setPrimaryNetForPlayer(player, network);
                helper.assertTrue(network.getPlayers().contains(player.getUUID())
                    && com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.getNetFromPlayer(player) == network,
                    "BD query fixture could not bind its non-owner member");
                var storage = network.getUnifiedStorage();
                ItemStack first = tagged(7, 192), second = tagged(8, 64);
                var firstKey = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(first);
                var secondKey = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(second);
                helper.assertTrue(storage.insert(firstKey, 192, false).isEmpty() && storage.insert(secondKey, 64, false).isEmpty(), "BD seed failed");
                Map<StackKey, Integer> result = read(helper, player, first);
                helper.assertTrue(result.equals(Map.of(StackKey.of(first), 192, StackKey.of(second), 64)), "BD query lost native exact or unknown NBT quantity");
                helper.assertTrue(storage.getStackByKey(firstKey).amount() == 192 && storage.getStackByKey(secondKey).amount() == 64,
                    "BD query consumed native materials");
                network.removePlayer(player.getUUID());
                helper.assertTrue(!network.getPlayers().contains(player.getUUID()), "BD query fixture did not revoke its non-owner membership");
                helper.assertTrue(read(helper, player, first).isEmpty(), "BD query bypassed revoked membership");
                helper.succeed();
            } finally { network.destroySelf(); }
        }
    }

    private static final class Backpack {
        static void run(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            ItemStack backpack = new ItemStack(net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems.BACKPACK.get());
            var wrapper = backpack.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance())
                .orElseThrow(IllegalStateException::new);
            var handler = wrapper.getInventoryHandler();
            handler.setStackInSlot(0, tagged(7, 64)); handler.setStackInSlot(1, tagged(7, 64)); handler.setStackInSlot(2, tagged(8, 1));
            player.getInventory().setItem(0, backpack);
            Map<StackKey, Integer> result = read(helper, player, tagged(7, 1));
            helper.assertTrue(result.equals(Map.of(StackKey.of(tagged(7, 1)), 128, StackKey.of(tagged(8, 1)), 1)),
                "Backpack query failed to aggregate identical slots or separated NBT variants");
            helper.assertTrue(handler.getStackInSlot(0).getCount() == 64 && handler.getStackInSlot(1).getCount() == 64
                && handler.getStackInSlot(2).getCount() == 1, "Backpack query consumed materials");
            player.getInventory().setItem(0, ItemStack.EMPTY);
            helper.assertTrue(read(helper, player, tagged(7, 1)).isEmpty(), "Backpack query retained a removed backpack");
            helper.succeed();
        }
    }

    private static final class RefinedStorage {
        static void run(GameTestHelper helper) {
            BlockPos controllerPos = new BlockPos(1, 1, 1), storagePos = new BlockPos(0, 1, 1), transmitterPos = new BlockPos(2, 1, 1);
            helper.setBlock(controllerPos, block("refinedstorage", "creative_controller"));
            helper.setBlock(storagePos, block("refinedstorage", "64k_storage_block"));
            helper.setBlock(transmitterPos, block("refinedstorage", "wireless_transmitter"));
            helper.runAfterDelay(40, () -> {
                try {
                    var controller = (com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(controllerPos));
                    helper.assertTrue(controller != null && controller.getNetwork().canRun(), "RS native network unavailable");
                    var network = controller.getNetwork();
                    helper.assertTrue(network.insertItem(tagged(7, 192), 192, com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty()
                        && network.insertItem(tagged(8, 1), 1, com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty(), "RS seed failed");
                    ServerPlayer player = player(helper);
                    BlockPos absolute = helper.absolutePos(controllerPos);
                    player.setPos(absolute.getX() + 0.5, absolute.getY() + 1.5, absolute.getZ() + 0.5);
                    ItemStack terminal = new ItemStack(com.refinedmods.refinedstorage.RSItems.WIRELESS_GRID.get());
                    var binding = terminal.getOrCreateTag();
                    binding.putInt("NodeX", absolute.getX()); binding.putInt("NodeY", absolute.getY()); binding.putInt("NodeZ", absolute.getZ());
                    binding.putString("Dimension", helper.getLevel().dimension().location().toString());
                    var energy = terminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(IllegalStateException::new);
                    energy.receiveEnergy(Integer.MAX_VALUE, false); player.getInventory().setItem(0, terminal);
                    int before = energy.getEnergyStored();
                    Map<StackKey, Integer> result = read(helper, player, tagged(7, 1));
                    helper.assertTrue(result.equals(Map.of(StackKey.of(tagged(7, 1)), 192, StackKey.of(tagged(8, 1)), 1)), "RS native query lost variants or quantity");
                    helper.assertTrue(energy.getEnergyStored() == before
                        && network.extractItem(tagged(7, 1), 192, com.refinedmods.refinedstorage.api.util.IComparer.COMPARE_NBT,
                            com.refinedmods.refinedstorage.api.util.Action.SIMULATE).getCount() == 192, "RS query consumed energy or materials");
                    player.setPos(absolute.getX() + 10000, absolute.getY(), absolute.getZ());
                    helper.assertTrue(read(helper, player, tagged(7, 1)).isEmpty(), "RS query bypassed wireless range");
                    helper.succeed();
                } finally {
                    helper.setBlock(transmitterPos, Blocks.AIR); helper.setBlock(controllerPos, Blocks.AIR); helper.setBlock(storagePos, Blocks.AIR);
                }
            });
        }
    }

    private static final class AppliedEnergistics {
        static void run(GameTestHelper helper) {
            BlockPos energyPos = new BlockPos(1, 1, 1), accessPos = new BlockPos(1, 1, 0);
            helper.setBlock(energyPos, block("ae2", "creative_energy_cell"));
            var state = block("ae2", "wireless_access_point").defaultBlockState();
            helper.setBlock(accessPos, appeng.api.orientation.IOrientationStrategy.get(state).setFacing(state, Direction.NORTH));
            helper.runAfterDelay(60, () -> {
                var access = (appeng.blockentity.networking.WirelessAccessPointBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(accessPos));
                helper.assertTrue(access != null && access.getGrid() != null, "AE native wireless grid missing");
                var grid = access.getGrid();
                ServerPlayer player = player(helper);
                ItemStack cellStack = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", "item_storage_cell_1k")));
                var cell = appeng.api.storage.StorageCells.getCellInventory(cellStack, () -> {});
                helper.assertTrue(cell != null, "AE native query fixture could not create a real 1k cell");
                var actionSource = appeng.api.networking.security.IActionSource.ofPlayer(player);
                helper.assertTrue(cell.insert(appeng.api.stacks.AEItemKey.of(tagged(7, 1)), 192, appeng.api.config.Actionable.MODULATE, actionSource) == 192
                    && cell.insert(appeng.api.stacks.AEItemKey.of(tagged(8, 1)), 1, appeng.api.config.Actionable.MODULATE, actionSource) == 1,
                    "AE native cell seed failed");
                QueryStorage storage = new QueryStorage(cell);
                appeng.api.storage.IStorageProvider provider = mounts -> mounts.mount(storage, 0);
                grid.getStorageService().addGlobalStorageProvider(provider);
                try {
                    BlockPos absolute = helper.absolutePos(accessPos);
                    player.setPos(absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ() + 0.5);
                    ItemStack terminal = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", "wireless_terminal")));
                    var item = (appeng.items.tools.powered.WirelessTerminalItem) terminal.getItem();
                    appeng.items.tools.powered.WirelessTerminalItem.LINKABLE_HANDLER.link(terminal, GlobalPos.of(helper.getLevel().dimension(), absolute));
                    item.injectAEPower(terminal, Double.MAX_VALUE, appeng.api.config.Actionable.MODULATE);
                    player.getInventory().setItem(0, terminal);
                    double before = item.getAECurrentPower(terminal);
                    grid.getStorageService().invalidateCache();
                    var session = RecipeQueryService.start(List.of(tagged(7, 1)));
                    var first = RecipeQueryService.next(player, session);
                    helper.assertTrue(first.stacks().size() == 1 && first.stacks().get(0).getCount() == 192 && storage.enumerations == 0,
                        "AE exact query did not avoid cold inventory enumeration");
                    RecipeQueryService.cancel(session);
                    Map<StackKey, Integer> result = read(helper, player, tagged(7, 1));
                    helper.assertTrue(result.equals(Map.of(StackKey.of(tagged(7, 1)), 192, StackKey.of(tagged(8, 1)), 1)) && storage.enumerations > 0,
                        "AE query did not discover an unknown native NBT variant");
                    helper.assertTrue(Double.compare(before, item.getAECurrentPower(terminal)) == 0
                        && cell.extract(appeng.api.stacks.AEItemKey.of(tagged(7, 1)), 192, appeng.api.config.Actionable.SIMULATE, actionSource) == 192,
                        "AE query consumed materials or terminal power");
                    helper.succeed();
                } finally {
                    grid.getStorageService().removeGlobalStorageProvider(provider);
                    helper.setBlock(accessPos, Blocks.AIR); helper.setBlock(energyPos, Blocks.AIR);
                }
            });
        }
    }

    private static final class QueryStorage implements appeng.api.storage.MEStorage {
        final appeng.api.storage.MEStorage delegate;
        int enumerations;
        QueryStorage(appeng.api.storage.MEStorage delegate) { this.delegate = delegate; }
        public net.minecraft.network.chat.Component getDescription() { return delegate.getDescription(); }
        public void getAvailableStacks(appeng.api.stacks.KeyCounter output) { enumerations++; delegate.getAvailableStacks(output); }
        public long extract(appeng.api.stacks.AEKey key, long amount, appeng.api.config.Actionable mode,
                            appeng.api.networking.security.IActionSource source) {
            if (mode != appeng.api.config.Actionable.SIMULATE) throw new AssertionError("Recipe query performed real extraction");
            return delegate.extract(key, amount, mode, source);
        }
    }
}

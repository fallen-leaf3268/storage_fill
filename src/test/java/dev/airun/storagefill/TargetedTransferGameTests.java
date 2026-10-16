package dev.airun.storagefill;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.storage.StorageSource;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class TargetedTransferGameTests {
    @GameTest(template = "empty")
    public static void topUpUsesOneExactSimulationAndPreservesExtractionChunks(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack red = tagged(130, "red");
        ItemStack blue = tagged(50, "blue");
        TargetedSource source = new TargetedSource(red, blue, new ItemStack(Items.IRON_INGOT, 2));
        helper.assertTrue(TransferService.topUp(player, 7, player.containerMenu.getStateId(),
            List.of(red.copy(), new ItemStack(Items.IRON_INGOT, 2)), List.of(source)), "Targeted top-up failed");
        helper.assertTrue(source.snapshotCalls == 0, "Targeted top-up read a remote snapshot");
        helper.assertTrue(source.simulated.get(StackKey.of(red)).equals(List.of(130))
            && source.simulated.get(StackKey.of(new ItemStack(Items.IRON_INGOT))).equals(List.of(2)),
            "Top-up repeated simulation or simulated stack-sized portions instead of the total demand");
        helper.assertTrue(source.performed.equals(List.of(64, 64, 2, 2)), "Top-up changed extraction chunk sizes");
        helper.assertTrue(source.count(red) == 0 && source.count(blue) == 50 && count(player, red) == 130,
            "Targeted top-up mixed NBT or failed to conserve requested material");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void repeatedSourceCannotReserveSameStockTwice(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack diamond = tagged(64, "red");
        TargetedSource source = new TargetedSource(diamond);
        TransferResult result = TransferService.topUpDetailed(player, 7, player.containerMenu.getStateId(),
            List.of(diamond.copyWithCount(130)), List.of(source, source));
        helper.assertFalse(result.success(), "One stack was repeatedly simulated into a larger reservation");
        helper.assertTrue(messageArgument(result).contains("Diamond × 66"), "Exact shortage of 66 was not reported");
        helper.assertTrue(source.snapshotCalls == 0 && source.simulated.get(StackKey.of(diamond)).equals(List.of(130))
            && source.performed.isEmpty() && source.count(diamond) == 64, "Shortage consumed or repeatedly counted stock");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void topUpSkipsUnusedSourcesAndFallsBackAfterSimulationFailure(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack diamond = tagged(2, "red");
        TargetedSource failing = new TargetedSource(diamond);
        failing.failSimulation = true;
        TargetedSource available = new TargetedSource(diamond);
        TargetedSource unused = new TargetedSource(diamond);
        helper.assertTrue(TransferService.topUp(player, 7, player.containerMenu.getStateId(),
            List.of(diamond), List.of(failing, available, unused)), "Simulation failure blocked another available source");
        helper.assertTrue(failing.count(diamond) == 2 && available.count(diamond) == 0
            && unused.simulated.isEmpty() && unused.snapshotCalls == 0, "Top-up read or consumed an unused source");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void maximumFillSimulatesSharedKeyOnceAndPreservesChunks(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack diamond = tagged(130, "red");
        TargetedSource source = new TargetedSource(diamond);
        TargetedSource unused = new TargetedSource(diamond);
        FillNetwork.Fill request = request(player, true, List.of(
            new FillNetwork.Input(1, List.of(diamond.copyWithCount(1))),
            new FillNetwork.Input(2, List.of(diamond.copyWithCount(1)))));
        helper.assertTrue(TransferService.fillDetailed(player, request, List.of(source, unused)).success(),
            "Targeted maximum fill failed");
        helper.assertTrue(source.snapshotCalls == 0 && source.simulated.get(StackKey.of(diamond)).equals(List.of(128)),
            "Maximum fill scanned remote inventory or repeated a shared-key simulation");
        helper.assertTrue(source.performed.equals(List.of(64, 64)) && source.count(diamond) == 2
            && player.containerMenu.getSlot(1).getItem().getCount() == 64
            && player.containerMenu.getSlot(2).getItem().getCount() == 64,
            "Maximum fill changed extraction chunks or complete-set quantities");
        helper.assertTrue(unused.simulated.isEmpty() && unused.snapshotCalls == 0, "Maximum fill queried a saturated lower-priority source");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void standardFillOnlyQueriesExactRequestedNbt(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack red = tagged(2, "red");
        ItemStack blue = tagged(99, "blue");
        TargetedSource source = new TargetedSource(red, blue, new ItemStack(Items.COAL, 64));
        FillNetwork.Fill request = request(player, false, List.of(new FillNetwork.Input(1, List.of(red.copyWithCount(1)))));
        helper.assertTrue(TransferService.fillDetailed(player, request, List.of(source)).success(), "Exact NBT fill failed");
        helper.assertTrue(source.snapshotCalls == 0 && source.simulated.size() == 1
            && source.simulated.get(StackKey.of(red)).equals(List.of(1)) && source.count(blue) == 99,
            "Standard fill queried unrelated items or NBT variants");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void standardFillUsesLiveLocalStockWithoutRemoteQueries(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack diamond = tagged(1, "red");
        player.getInventory().setItem(9, diamond.copy());
        TargetedSource unused = new TargetedSource(diamond);
        FillNetwork.Fill request = request(player, false, List.of(new FillNetwork.Input(1, List.of(diamond))));
        helper.assertTrue(TransferService.fillDetailed(player, request, List.of(unused)).success(), "Local-only targeted fill failed");
        helper.assertTrue(unused.snapshotCalls == 0 && unused.simulated.isEmpty() && unused.performed.isEmpty(),
            "Local-only fill accessed remote storage");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changedStandardSourceReportsDeficitAndRefundsEarlierWithdrawal(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack diamond = tagged(1, "red");
        TargetedSource first = new TargetedSource(diamond);
        TargetedSource unavailable = new TargetedSource(diamond);
        unavailable.failPerform = true;
        FillNetwork.Fill request = request(player, false, List.of(
            new FillNetwork.Input(1, List.of(diamond)), new FillNetwork.Input(2, List.of(diamond))));
        TransferResult result = TransferService.fillDetailed(player, request, List.of(first, unavailable));
        helper.assertTrue(!result.success() && messageArgument(result).contains("Diamond × 1"),
            "Changed source did not report its exact unavailable material");
        helper.assertTrue(first.count(diamond) == 1 && unavailable.count(diamond) == 1
            && player.containerMenu.getSlot(1).getItem().isEmpty() && player.containerMenu.getSlot(2).getItem().isEmpty(),
            "Changed source partially filled slots or failed to refund an earlier withdrawal");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partialTopUpExtractionReportsExactDeficitAndRefundsKnownItems(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack diamond = tagged(2, "red");
        TargetedSource source = new TargetedSource(diamond);
        source.performLimit = 1;
        TransferResult result = TransferService.topUpDetailed(player, 7, player.containerMenu.getStateId(), List.of(diamond), List.of(source));
        helper.assertTrue(!result.success() && messageArgument(result).contains("Diamond × 1"),
            "Partial extraction did not report its exact deficit");
        helper.assertTrue(source.count(diamond) == 2 && count(player, diamond) == 0,
            "Partial extraction lost or duplicated a known withdrawal");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void localPickupRevocationDuringRemoteSimulationCancelsAndRefunds(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack diamond = tagged(1, "red");
        GuardedSlot local = new GuardedSlot();
        local.set(diamond.copy());
        player.containerMenu = new GuardedMenu(player, local);
        TargetedSource source = new TargetedSource(diamond);
        source.onSimulation = () -> local.pickup = false;
        FillNetwork.Fill request = new FillNetwork.Fill(8, player.containerMenu.getStateId(), 1, List.of(
            new FillNetwork.Input(0, List.of(diamond)), new FillNetwork.Input(1, List.of(diamond))),
            IntStream.range(2, 38).toArray(), false, true);
        TransferResult result = TransferService.fillDetailed(player, request, List.of(source));
        helper.assertFalse(result.success(), "Revoked local pickup permission was bypassed");
        helper.assertTrue(!local.pickup && local.getItem().getCount() == 1
            && player.containerMenu.getSlot(1).getItem().isEmpty() && source.count(diamond) == 1
            && source.performed.equals(List.of(1)), "Local permission revocation lost material or failed to refund a known remote withdrawal");
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "targeted-test"));
        player.containerMenu = new CraftingMenu(7, player.getInventory());
        return player;
    }

    private static ItemStack tagged(int count, String variant) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        stack.getOrCreateTag().putString("variant", variant);
        return stack;
    }

    private static int count(ServerPlayer player, ItemStack prototype) {
        return player.getInventory().items.stream().filter(stack -> ItemStack.isSameItemSameTags(stack, prototype))
            .mapToInt(ItemStack::getCount).sum();
    }

    private static FillNetwork.Fill request(ServerPlayer player, boolean maximum, List<FillNetwork.Input> inputs) {
        return new FillNetwork.Fill(7, player.containerMenu.getStateId(), 1, inputs, IntStream.range(10, 46).toArray(), maximum, true);
    }

    private static String messageArgument(TransferResult result) {
        if (result.message().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
            && contents.getArgs().length > 0 && contents.getArgs()[0] instanceof net.minecraft.network.chat.Component component) {
            return component.getString();
        }
        return result.message().getString();
    }

    private static final class GuardedSlot extends net.minecraft.world.inventory.Slot {
        private boolean pickup = true;
        private GuardedSlot() { super(new net.minecraft.world.SimpleContainer(1), 0, 0, 0); }
        public boolean mayPickup(net.minecraft.world.entity.player.Player player) { return pickup; }
    }

    private static final class GuardedMenu extends net.minecraft.world.inventory.AbstractContainerMenu {
        private GuardedMenu(ServerPlayer player, GuardedSlot local) {
            super(null, 8);
            addSlot(local);
            addSlot(new net.minecraft.world.inventory.Slot(new net.minecraft.world.SimpleContainer(1), 0, 0, 0));
            for (int i = 0; i < 36; i++) addSlot(new net.minecraft.world.inventory.Slot(player.getInventory(), i, 0, 0));
        }
        public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) { return ItemStack.EMPTY; }
        public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
    }

    private static final class TargetedSource implements StorageSource {
        private final Map<StackKey, Integer> stored = new LinkedHashMap<>();
        private final Map<StackKey, List<Integer>> simulated = new LinkedHashMap<>();
        private final List<Integer> performed = new ArrayList<>();
        private int snapshotCalls;
        private boolean failSimulation;
        private boolean failPerform;
        private int performLimit = Integer.MAX_VALUE;
        private Runnable onSimulation = () -> {};

        private TargetedSource(ItemStack... stacks) {
            for (ItemStack stack : stacks) stored.merge(StackKey.of(stack), stack.getCount(), Integer::sum);
        }

        private int count(ItemStack stack) { return stored.getOrDefault(StackKey.of(stack), 0); }

        public List<ItemStack> snapshot(Set<Item> wanted) {
            snapshotCalls++;
            throw new IllegalStateException("Remote snapshots are unavailable for targeted transfers");
        }

        public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
            StackKey key = StackKey.of(prototype);
            if (simulate) {
                simulated.computeIfAbsent(key, ignored -> new ArrayList<>()).add(amount);
                if (failSimulation) throw new IllegalStateException("Injected targeted simulation failure");
                onSimulation.run();
            } else {
                performed.add(amount);
                if (failPerform) return ItemStack.EMPTY;
            }
            int taken = Math.min(amount, stored.getOrDefault(key, 0));
            if (!simulate) {
                taken = Math.min(taken, performLimit);
                stored.put(key, stored.getOrDefault(key, 0) - taken);
            }
            return taken <= 0 ? ItemStack.EMPTY : prototype.copyWithCount(taken);
        }

        public ItemStack insert(ItemStack stack) {
            stored.merge(StackKey.of(stack), stack.getCount(), Integer::sum);
            return ItemStack.EMPTY;
        }

        public void finish(int extracted) { }
    }
}

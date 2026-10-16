package dev.airun.storagefill;

import dev.airun.storagefill.storage.StorageSource;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.*;
import java.util.stream.IntStream;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class TransferGameTests {
    @GameTest(template = "empty")
    public static void fillsFromRemoteAndUsesInventoryFirst(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS));
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 10);
        helper.assertTrue(TransferService.fill(player, request(player, false, 2), List.of(source)), "Fill failed");
        helper.assertTrue(source.amount == 9, "Inventory was not preferred");
        helper.assertTrue(player.containerMenu.getSlot(1).getItem().getCount() == 1, "First input missing");
        helper.assertTrue(player.containerMenu.getSlot(2).getItem().getCount() == 1, "Second input missing");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void shortageDoesNotConsumeAnything(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 1);
        helper.assertFalse(TransferService.fill(player, request(player, false, 2), List.of(source)), "Shortage must fail");
        helper.assertTrue(source.amount == 1 && player.containerMenu.getSlot(1).getItem().isEmpty(), "Shortage changed inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void maximumFillsCompleteSets(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 11);
        helper.assertTrue(TransferService.fill(player, request(player, true, 2), List.of(source)), "Maximum fill failed");
        helper.assertTrue(player.containerMenu.getSlot(1).getItem().getCount() == 5, "Wrong maximum count");
        helper.assertTrue(player.containerMenu.getSlot(2).getItem().getCount() == 5 && source.amount == 1, "Unequal complete sets");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedExtractionReturnsEarlierWithdrawal(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MemorySource first = new MemorySource(Items.OAK_PLANKS, 1);
        MemorySource stale = new MemorySource(Items.OAK_PLANKS, 1);
        stale.fail = true;
        helper.assertFalse(TransferService.fill(player, request(player, false, 2), List.of(first, stale)), "Stale stock must fail");
        helper.assertTrue(first.amount == 1 && player.containerMenu.getSlot(1).getItem().isEmpty(), "Rollback lost items");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rejectsResultSlotAndStaleMenu(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 10);
        FillNetwork.Fill bad = new FillNetwork.Fill(player.containerMenu.containerId, player.containerMenu.getStateId(), 1,
                List.of(new FillNetwork.Input(0, List.of(new ItemStack(Items.OAK_PLANKS)))), new int[]{10}, false, true);
        helper.assertFalse(TransferService.fill(player, bad, List.of(source)), "Result slot accepted");
        FillNetwork.Fill old = request(player, false, 1);
        player.containerMenu = player.inventoryMenu;
        helper.assertFalse(TransferService.fill(player, old, List.of(source)), "Stale menu accepted");
        helper.assertTrue(source.amount == 10, "Invalid request consumed items");
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = new net.minecraftforge.common.util.FakePlayer(helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "storage-fill-test"));
        player.containerMenu = new CraftingMenu(7, player.getInventory());
        return player;
    }

    @GameTest(template = "empty")
    public static void independentMachineHandlersAreDistinct(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new MachineMenu(player);
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 2);
        FillNetwork.Fill packet = new FillNetwork.Fill(8, player.containerMenu.getStateId(), 1,
                List.of(new FillNetwork.Input(0, List.of(new ItemStack(Items.OAK_PLANKS))),
                        new FillNetwork.Input(1, List.of(new ItemStack(Items.OAK_PLANKS)))),
                IntStream.range(2, 38).toArray(), false, true);
        helper.assertTrue(TransferService.fill(player, packet, List.of(source)), "Independent machine handlers were treated as aliases");
        helper.assertTrue(source.amount == 0, "Machine source count incorrect");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullInventoryWithOldInputDoesNotLoseItems(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        player.containerMenu.getSlot(3).set(new ItemStack(Items.DIAMOND));
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 10);
        helper.assertFalse(TransferService.fill(player, request(player, false, 1), List.of(source)), "Full inventory should refuse displaced input");
        helper.assertTrue(source.amount == 10 && player.containerMenu.getSlot(3).getItem().is(Items.DIAMOND), "Refusal changed storage or old input");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nbtAndLargeCountsSurviveSnapshotCodec(GameTestHelper helper) {
        ItemStack first = new ItemStack(Items.DIAMOND, 4096);
        first.getOrCreateTag().putString("owner", "one");
        ItemStack other = first.copy();
        other.getOrCreateTag().putString("owner", "two");
        helper.assertFalse(StackKey.of(first).equals(StackKey.of(other)), "Different NBT was merged");
        net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            FillNetwork.Snapshot.encode(new FillNetwork.Snapshot(7, 3, List.of(first, other, first, other), List.of(1, 2, 3, 4)), buffer);
            FillNetwork.Snapshot snapshot = FillNetwork.Snapshot.decode(buffer);
            ItemStack restored = snapshot.stacks().get(0);
            helper.assertTrue(restored.getCount() == 4096 && StackKey.of(first).equals(StackKey.of(restored)), "Snapshot lost count or NBT");
            helper.assertTrue(snapshot.sourceIds().equals(List.of(1, 2, 3, 4)), "Snapshot lost storage origins");
            helper.assertTrue(StackKey.of(other).equals(StackKey.of(snapshot.stacks().get(1))), "Snapshot source order changed");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void insertCallbackFailureDoesNotDuplicate(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        FaultSlot first = new FaultSlot();
        first.failAfterSet = true;
        player.containerMenu = new FaultMenu(player, first, new FaultSlot());
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 1);
        helper.assertFalse(TransferService.fill(player, faultRequest(player, 1), List.of(source)), "Callback failure must fail transfer");
        helper.assertTrue(total(player, source) == 1, "Write-then-throw duplicated or lost material");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rollbackRemoveFailureStillRefundsOtherHoldings(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        FaultSlot first = new FaultSlot();
        first.failRemove = true;
        FaultSlot second = new FaultSlot();
        second.rejectInsert = true;
        player.containerMenu = new FaultMenu(player, first, second);
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 2);
        boolean result = false;
        try { result = TransferService.fill(player, faultRequest(player, 2), List.of(source)); }
        catch (IllegalStateException ignored) { }
        helper.assertFalse(result, "Rejected insertion must fail transfer");
        helper.assertTrue(total(player, source) == 2, "Rollback exception lost an unrelated holding");
        helper.assertTrue(source.amount == 1, "Unplaced material was not refunded");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void extractionOnTakeFailureKeepsMaterial(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        FaultSlot first = new FaultSlot();
        first.set(new ItemStack(Items.OAK_PLANKS));
        first.failOnTake = true;
        player.containerMenu = new FaultMenu(player, first, new FaultSlot());
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 0);
        helper.assertFalse(TransferService.fill(player, faultRequest(player, 1), List.of(source)), "Take callback failure must fail transfer");
        helper.assertTrue(total(player, source) == 1, "Take-then-throw lost material");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void insertionReturnMustMatchActualSlot(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        FaultSlot first = new FaultSlot();
        first.lieAboutInsert = true;
        player.containerMenu = new FaultMenu(player, first, new FaultSlot());
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 1);
        helper.assertFalse(TransferService.fill(player, faultRequest(player, 1), List.of(source)), "Unchanged target must not count as inserted");
        helper.assertTrue(total(player, source) == 1, "Incorrect insertion return lost material");
        helper.succeed();
    }

    private static int total(ServerPlayer player, MemorySource source) {
        int total = source.amount;
        for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
            if (slot.getItem().is(Items.OAK_PLANKS)) total += slot.getItem().getCount();
        }
        return total;
    }

    private static FillNetwork.Fill faultRequest(ServerPlayer player, int count) {
        List<FillNetwork.Input> inputs = new ArrayList<>();
        for (int i = 0; i < 2; i++) inputs.add(new FillNetwork.Input(i,
                i < count ? List.of(new ItemStack(Items.OAK_PLANKS)) : List.of()));
        return new FillNetwork.Fill(9, player.containerMenu.getStateId(), 1, inputs,
                IntStream.range(2, 38).toArray(), false, true);
    }

    @GameTest(template = "empty")
    public static void localAlternativesAvoidRemoteWithdrawal(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new FaultMenu(player, new FaultSlot(), new FaultSlot());
        player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS));
        player.getInventory().setItem(1, new ItemStack(Items.BIRCH_PLANKS));
        MemorySource source = new MemorySource(Items.SPRUCE_PLANKS, 1);
        var request = new FillNetwork.Fill(9, player.containerMenu.getStateId(), 1, List.of(
                new FillNetwork.Input(0, List.of(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.BIRCH_PLANKS))),
                new FillNetwork.Input(1, List.of(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.SPRUCE_PLANKS)))),
                IntStream.range(2, 38).toArray(), false, true);
        helper.assertTrue(TransferService.fill(player, request, List.of(source)), "Local alternatives should fill");
        helper.assertTrue(source.amount == 1, "Remote material was used despite sufficient local alternatives");
        helper.assertTrue(player.containerMenu.getSlot(0).getItem().is(Items.BIRCH_PLANKS)
                && player.containerMenu.getSlot(1).getItem().is(Items.OAK_PLANKS), "Unexpected local allocation");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void networkAlternativesAvoidBackpackWithdrawal(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new FaultMenu(player, new FaultSlot(), new FaultSlot());
        MemorySource oak = new MemorySource(Items.OAK_PLANKS, 1);
        MemorySource birch = new MemorySource(Items.BIRCH_PLANKS, 1);
        MemorySource backpack = new MemorySource(Items.SPRUCE_PLANKS, 1);
        backpack.origin = StorageSource.SOPHISTICATED_BACKPACKS;
        var request = new FillNetwork.Fill(9, player.containerMenu.getStateId(), 1, List.of(
                new FillNetwork.Input(0, List.of(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.BIRCH_PLANKS))),
                new FillNetwork.Input(1, List.of(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.SPRUCE_PLANKS)))),
                IntStream.range(2, 38).toArray(), false, true);
        helper.assertTrue(TransferService.fill(player, request, List.of(oak, birch, backpack)), "Network alternatives should fill");
        helper.assertTrue(backpack.amount == 1 && oak.amount == 0 && birch.amount == 0,
                "Backpack consumed despite sufficient network alternatives");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void backpackOnlySuppliesNetworkShortfall(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MemorySource network = new MemorySource(Items.OAK_PLANKS, 1);
        MemorySource backpack = new MemorySource(Items.OAK_PLANKS, 10);
        backpack.origin = StorageSource.SOPHISTICATED_BACKPACKS;
        helper.assertTrue(TransferService.fill(player, request(player, false, 2), List.of(network, backpack)), "Mixed fill failed");
        helper.assertTrue(network.amount == 0 && backpack.amount == 9, "Backpack did not supply only the shortfall");
        helper.succeed();
    }

    private static final class FaultSlot extends net.minecraft.world.inventory.Slot {
        boolean failAfterSet;
        boolean failRemove;
        boolean failOnTake;
        boolean rejectInsert;
        boolean lieAboutInsert;
        FaultSlot() { super(new net.minecraft.world.SimpleContainer(1), 0, 0, 0); }
        public void set(ItemStack stack) {
            super.set(stack);
            if (failAfterSet && !stack.isEmpty()) throw new IllegalStateException("Injected after-set failure");
        }
        public ItemStack remove(int count) {
            if (failRemove) throw new IllegalStateException("Injected rollback remove failure");
            return super.remove(count);
        }
        public void onTake(net.minecraft.world.entity.player.Player player, ItemStack stack) {
            super.onTake(player, stack);
            if (failOnTake) throw new IllegalStateException("Injected on-take failure");
        }
        public ItemStack safeInsert(ItemStack stack, int count) {
            if (rejectInsert) return stack;
            if (lieAboutInsert) return ItemStack.EMPTY;
            return super.safeInsert(stack, count);
        }
    }

    private static final class FaultMenu extends net.minecraft.world.inventory.AbstractContainerMenu {
        FaultMenu(ServerPlayer player, FaultSlot first, FaultSlot second) {
            super(null, 9);
            addSlot(first);
            addSlot(second);
            for (int i = 0; i < 36; i++) addSlot(new net.minecraft.world.inventory.Slot(player.getInventory(), i, 0, 0));
        }
        public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) { return ItemStack.EMPTY; }
        public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
    }

    private static final class MachineMenu extends net.minecraft.world.inventory.AbstractContainerMenu {
        MachineMenu(ServerPlayer player) {
            super(null, 8);
            addSlot(new net.minecraftforge.items.SlotItemHandler(new net.minecraftforge.items.ItemStackHandler(1), 0, 0, 0));
            addSlot(new net.minecraftforge.items.SlotItemHandler(new net.minecraftforge.items.ItemStackHandler(1), 0, 0, 0));
            for (int i = 0; i < 36; i++) addSlot(new net.minecraft.world.inventory.Slot(player.getInventory(), i, 0, 0));
        }
        public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) { return ItemStack.EMPTY; }
        public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
    }

    private static FillNetwork.Fill request(ServerPlayer player, boolean maximum, int count) {
        List<FillNetwork.Input> inputs = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            inputs.add(new FillNetwork.Input(i, i <= count ? List.of(new ItemStack(Items.OAK_PLANKS)) : List.of()));
        }
        return new FillNetwork.Fill(player.containerMenu.containerId, player.containerMenu.getStateId(), 1,
                inputs, IntStream.range(10, 46).toArray(), maximum, true);
    }

    private static final class MemorySource implements StorageSource {
        private final Item item;
        private int amount;
        private boolean fail;
        private int origin;

        private MemorySource(Item item, int amount) { this.item = item; this.amount = amount; }
        public int sourceId() { return origin; }
        public List<ItemStack> snapshot(Set<Item> wanted) { return wanted.contains(item) && amount > 0 ? List.of(new ItemStack(item, amount)) : List.of(); }
        public ItemStack extract(ItemStack prototype, int count, boolean simulate) {
            if ((!simulate && fail) || !ItemStack.isSameItemSameTags(prototype, new ItemStack(item))) return ItemStack.EMPTY;
            int taken = Math.min(count, amount);
            if (!simulate) amount -= taken;
            return new ItemStack(item, taken);
        }
        public ItemStack insert(ItemStack stack) { amount += stack.getCount(); return ItemStack.EMPTY; }
        public void finish(int extracted) { }
    }
}

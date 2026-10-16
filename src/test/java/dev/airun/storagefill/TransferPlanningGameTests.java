package dev.airun.storagefill;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.storage.StorageSource;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class TransferPlanningGameTests {
    @GameTest(template = "empty", batch = "transfer_planning")
    public static void singleStackableCandidateFillsItsFullCapacity(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new PlanningMenu(player, 1);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 64));
        boolean filled = TransferService.fill(player, request(player, List.of(new ItemStack(Items.IRON_INGOT))), List.of());
        helper.assertTrue(filled && player.containerMenu.getSlot(0).getItem().is(Items.IRON_INGOT)
            && player.containerMenu.getSlot(0).getItem().getCount() == 64,
            "A single stackable candidate did not fill sixty-four available iron ingots");
        helper.assertTrue(player.getInventory().items.get(0).isEmpty(), "Full stack transfer retained its original inventory materials");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "transfer_planning")
    public static void unselectedSwordDoesNotRestrictSelectedIronToOne(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new PlanningMenu(player, 1);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 64));
        player.getInventory().items.set(1, new ItemStack(Items.IRON_SWORD));
        boolean filled = TransferService.fill(player, request(player, alternatives()), List.of());
        helper.assertTrue(filled && player.containerMenu.getSlot(0).getItem().is(Items.IRON_INGOT)
            && player.containerMenu.getSlot(0).getItem().getCount() == 64,
            "An unselected one-item sword restricted the selected iron stack");
        helper.assertTrue(player.getInventory().items.get(0).isEmpty() && player.getInventory().items.get(1).is(Items.IRON_SWORD),
            "Mixed capacity transfer did not preserve the unselected sword");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "transfer_planning")
    public static void pooledSwordsStillFillOnlyOnePerTarget(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new PlanningMenu(player, 1);
        for (int index = 0; index < 36; index++) player.getInventory().items.set(index, new ItemStack(Items.IRON_SWORD));
        boolean filled = TransferService.fill(player, request(player, alternatives()), List.of());
        helper.assertTrue(filled && player.containerMenu.getSlot(0).getItem().is(Items.IRON_SWORD)
            && player.containerMenu.getSlot(0).getItem().getCount() == 1,
            "Pooled unstackable alternatives exceeded their own one-item target capacity");
        helper.assertTrue(player.getInventory().items.stream().mapToInt(ItemStack::getCount).sum() == 35,
            "One-sword transfer did not preserve the other thirty-five swords");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "transfer_planning")
    public static void nonCompleteMaximumUsesEachIngredientsAvailableStock(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new PlanningMenu(player, 2);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT));
        player.getInventory().items.set(1, new ItemStack(Items.GOLD_INGOT));
        ContainerSource source = new ContainerSource();
        source.storage.setItem(0, new ItemStack(Items.IRON_INGOT, 63));
        var inputs = List.of(new FillNetwork.Input(0, List.of(new ItemStack(Items.IRON_INGOT))),
            new FillNetwork.Input(1, List.of(new ItemStack(Items.GOLD_INGOT))));
        helper.assertTrue(TransferService.fill(player, multipleRequest(player, inputs, true, false), List.of(source)),
            "Non-complete maximum transfer rejected its complete initial group");
        helper.assertTrue(player.containerMenu.getSlot(0).getItem().getCount() == 64
            && player.containerMenu.getSlot(1).getItem().getCount() == 1 && source.storage.isEmpty()
            && source.withdrawals == 1 && player.getInventory().items.stream().allMatch(ItemStack::isEmpty),
            "Non-complete maximum transfer balanced unequal ingredients instead of filling sixty-four iron and one gold");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "transfer_planning")
    public static void nonCompleteMaximumSharesSixtyFiveMaterialsInSlotOrder(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new PlanningMenu(player, 2);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 64));
        player.getInventory().items.set(1, new ItemStack(Items.IRON_INGOT));
        var inputs = List.of(new FillNetwork.Input(0, List.of(new ItemStack(Items.IRON_INGOT))),
            new FillNetwork.Input(1, List.of(new ItemStack(Items.IRON_INGOT))));
        helper.assertTrue(TransferService.fill(player, multipleRequest(player, inputs, true, false), List.of()),
            "Non-complete maximum transfer rejected sixty-five shared materials");
        helper.assertTrue(player.containerMenu.getSlot(0).getItem().getCount() == 33
            && player.containerMenu.getSlot(1).getItem().getCount() == 32
            && player.getInventory().items.stream().allMatch(ItemStack::isEmpty),
            "Shared materials did not follow the native per-slot round order");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "transfer_planning")
    public static void nonCompleteNormalClickStillTransfersOnlyOneGroup(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new PlanningMenu(player, 2);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 64));
        player.getInventory().items.set(1, new ItemStack(Items.GOLD_INGOT, 64));
        var inputs = List.of(new FillNetwork.Input(0, List.of(new ItemStack(Items.IRON_INGOT))),
            new FillNetwork.Input(1, List.of(new ItemStack(Items.GOLD_INGOT))));
        helper.assertTrue(TransferService.fill(player, multipleRequest(player, inputs, false, false), List.of()),
            "Non-complete normal click refused one complete group");
        helper.assertTrue(player.containerMenu.getSlot(0).getItem().getCount() == 1
            && player.containerMenu.getSlot(1).getItem().getCount() == 1
            && player.getInventory().items.get(0).getCount() == 63 && player.getInventory().items.get(1).getCount() == 63,
            "Non-complete normal click incorrectly performed a maximum transfer");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "transfer_planning")
    public static void nonCompleteMaximumDoesNotShareAnUnstackableInputsLimit(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new PlanningMenu(player, 2);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT));
        player.getInventory().items.set(1, new ItemStack(Items.IRON_SWORD));
        ContainerSource source = new ContainerSource();
        source.storage.setItem(0, new ItemStack(Items.IRON_INGOT, 63));
        var inputs = List.of(new FillNetwork.Input(0, List.of(new ItemStack(Items.IRON_INGOT))),
            new FillNetwork.Input(1, List.of(new ItemStack(Items.IRON_SWORD))));
        helper.assertTrue(TransferService.fill(player, multipleRequest(player, inputs, true, false), List.of(source)),
            "Non-complete maximum transfer refused stackable and unstackable inputs");
        helper.assertTrue(player.containerMenu.getSlot(0).getItem().getCount() == 64
            && player.containerMenu.getSlot(1).getItem().is(Items.IRON_SWORD)
            && player.containerMenu.getSlot(1).getItem().getCount() == 1 && source.storage.isEmpty(),
            "An unstackable input restricted the targeted count or capacity of the other input");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "transfer_planning")
    public static void nonCompleteMaximumStillRejectsAStaleMissingInitialGroup(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new PlanningMenu(player, 2);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 64));
        var inputs = List.of(new FillNetwork.Input(0, List.of(new ItemStack(Items.IRON_INGOT))),
            new FillNetwork.Input(1, List.of(new ItemStack(Items.GOLD_INGOT))));
        TransferResult result = TransferService.fillDetailed(player, multipleRequest(player, inputs, true, false), List.of());
        helper.assertTrue(!result.success() && result.message().toString().contains("× 1"),
            "A stale missing initial group was partially accepted or lost its deficit explanation");
        helper.assertTrue(player.getInventory().items.get(0).getCount() == 64
            && player.containerMenu.getSlot(0).getItem().isEmpty() && player.containerMenu.getSlot(1).getItem().isEmpty(),
            "A stale missing initial group changed inventory or cleared machine inputs");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "transfer_planning")
    public static void nonCompleteMaximumUsesWholeCountedIngredientUnits(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new PlanningMenu(player, 2);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 64));
        player.getInventory().items.set(1, new ItemStack(Items.IRON_SWORD));
        var inputs = List.of(new FillNetwork.Input(0, List.of(new ItemStack(Items.IRON_INGOT, 3))),
            new FillNetwork.Input(1, List.of(new ItemStack(Items.IRON_SWORD))));
        helper.assertTrue(TransferService.fill(player, multipleRequest(player, inputs, true, false), List.of()),
            "Counted non-complete maximum transfer rejected one complete initial group");
        helper.assertTrue(player.containerMenu.getSlot(0).getItem().getCount() == 63
            && player.containerMenu.getSlot(1).getItem().getCount() == 1
            && player.getInventory().items.get(0).getCount() == 1 && player.getInventory().items.get(1).isEmpty(),
            "Counted non-complete maximum transfer exceeded its slot capacity or split a required ingredient unit");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "transfer_planning")
    public static void staleValidSnapshotReportsCurrentSharedDeficitWithoutWithdrawal(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.containerMenu = new PlanningMenu(player, 36);
        player.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 17));
        player.getInventory().items.set(1, new ItemStack(Items.GOLD_INGOT, 17));
        ContainerSource source = new ContainerSource();
        source.storage.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        Map<StackKey, Integer> local = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) local.merge(StackKey.of(stack), stack.getCount(), Integer::sum);
        }
        Map<StackKey, Integer> cachedStock = new LinkedHashMap<>(local);
        for (ItemStack stack : source.snapshot(Set.of(Items.IRON_INGOT, Items.GOLD_INGOT))) {
            cachedStock.merge(StackKey.of(stack), stack.getCount(), Integer::sum);
        }
        List<ItemStack> candidates = List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT));
        List<StackKey> keys = candidates.stream().map(StackKey::of).toList();
        List<AllocationPlanner.Need<StackKey>> needs = IntStream.range(0, 36)
            .mapToObj(index -> new AllocationPlanner.Need<>(keys, 1, 64)).toList();
        helper.assertTrue(AllocationPlanner.planByPriority(needs, cachedStock, local, cachedStock, false).isPresent(),
            "The original thirty-six-material snapshot did not allow a valid client allocation");
        int state = player.containerMenu.getStateId();
        helper.assertTrue(source.storage.removeItem(0, 2).getCount() == 2 && source.storage.isEmpty(),
            "The external withdrawal did not remove the two previously cached remote materials");
        helper.assertTrue(player.containerMenu.getStateId() == state, "External withdrawal changed the machine menu state");
        List<FillNetwork.Input> inputs = IntStream.range(0, 36).mapToObj(index -> new FillNetwork.Input(index, candidates)).toList();
        FillNetwork.Fill original = new FillNetwork.Fill(player.containerMenu.containerId, state, 1,
            inputs, IntStream.range(36, 72).toArray(), false, true, 1);
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        FillNetwork.Fill decoded;
        try {
            FillNetwork.Fill.encode(original, encoded);
            decoded = FillNetwork.Fill.decode(encoded);
            helper.assertTrue(!encoded.isReadable(), "Legal stale-snapshot payload left undecoded bytes");
        } finally {
            encoded.release();
        }
        TransferResult result = TransferService.fillDetailed(player, decoded, List.of(source));
        helper.assertFalse(result.success(), "A stale valid snapshot supplied thirty-six materials from the current thirty-four");
        helper.assertTrue(result.message().toString().contains("missing_alternatives") && result.message().toString().contains("× 2"),
            "Shared stale-snapshot shortage did not report a usable current two-item deficit");
        helper.assertTrue(source.simulations == 2 && source.withdrawals == 0 && source.storage.isEmpty(),
            "Stale-snapshot validation performed live withdrawals or repeated unnecessary target queries");
        helper.assertTrue(player.getInventory().items.get(0).getCount() == 17 && player.getInventory().items.get(1).getCount() == 17,
            "Stale-snapshot shortage changed current player materials");
        helper.assertTrue(player.containerMenu.slots.subList(0, 36).stream().allMatch(slot -> slot.getItem().isEmpty()),
            "Stale-snapshot shortage changed a machine input");
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "planning-test"));
    }

    private static List<ItemStack> alternatives() {
        return List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_SWORD));
    }

    private static FillNetwork.Fill request(ServerPlayer player, List<ItemStack> candidates) {
        return new FillNetwork.Fill(player.containerMenu.containerId, player.containerMenu.getStateId(), 1,
            List.of(new FillNetwork.Input(0, candidates)), IntStream.range(1, 37).toArray(), true, true);
    }

    private static FillNetwork.Fill multipleRequest(ServerPlayer player, List<FillNetwork.Input> inputs,
                                                   boolean maximum, boolean completeSets) {
        return new FillNetwork.Fill(player.containerMenu.containerId, player.containerMenu.getStateId(), 1,
            inputs, IntStream.range(inputs.size(), inputs.size() + 36).toArray(), maximum, completeSets);
    }

    private static final class PlanningMenu extends AbstractContainerMenu {
        private PlanningMenu(ServerPlayer player, int inputs) {
            super(null, 96);
            SimpleContainer machine = new SimpleContainer(inputs);
            for (int index = 0; index < inputs; index++) addSlot(new Slot(machine, index, 0, 0));
            for (int index = 0; index < 36; index++) addSlot(new Slot(player.getInventory(), index, 0, 0));
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

        @Override
        public boolean stillValid(Player player) { return true; }
    }

    private static final class ContainerSource implements StorageSource {
        private final SimpleContainer storage = new SimpleContainer(1);
        private int simulations;
        private int withdrawals;

        @Override
        public List<ItemStack> snapshot(Set<Item> wanted) {
            ItemStack stored = storage.getItem(0);
            return !stored.isEmpty() && wanted.contains(stored.getItem()) ? List.of(stored.copy()) : List.of();
        }

        @Override
        public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
            if (simulate) simulations++; else withdrawals++;
            ItemStack stored = storage.getItem(0);
            if (stored.isEmpty() || !StackKey.of(stored).equals(StackKey.of(prototype))) return ItemStack.EMPTY;
            int count = Math.min(amount, stored.getCount());
            return simulate ? stored.copyWithCount(count) : storage.removeItem(0, count);
        }

        @Override
        public ItemStack insert(ItemStack stack) { return storage.addItem(stack.copy()); }

        @Override
        public void finish(int extracted) { }
    }
}

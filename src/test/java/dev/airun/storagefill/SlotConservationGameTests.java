package dev.airun.storagefill;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.storage.StorageSource;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class SlotConservationGameTests {
    @GameTest(template = "empty", batch = "slot_conservation")
    public static void ordinarySlotKeepsExactlyOneWithdrawnMaterial(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Slot slot = new Slot(new SimpleContainer(1), 0, 0, 0);
        player.containerMenu = new AuditMenu(player, slot);
        MemorySource source = new MemorySource(1);
        helper.assertTrue(TransferService.fill(player, request(player), List.of(source)), "Ordinary audit slot refused one material");
        helper.assertTrue(source.amount == 0 && slot.getItem().is(Items.OAK_PLANKS) && slot.getItem().getCount() == 1,
            "Ordinary audit slot did not conserve its one material");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "slot_conservation")
    public static void lyingInsertionReturnKeepsOriginalMaterial(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Slot slot = new Slot(new SimpleContainer(1), 0, 0, 0) {
            @Override
            public ItemStack safeInsert(ItemStack stack, int count) { return ItemStack.EMPTY; }
        };
        player.containerMenu = new AuditMenu(player, slot);
        MemorySource source = new MemorySource(1);
        helper.assertFalse(TransferService.fill(player, request(player), List.of(source)), "Lying audit slot was treated as a valid insertion");
        helper.assertTrue(source.amount == 1 && slot.getItem().isEmpty(), "Lying insertion return destroyed its original material");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "slot_conservation")
    public static void synchronousConsumptionDoesNotRefundConsumedMaterial(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ConsumingSlot slot = new ConsumingSlot();
        player.containerMenu = new AuditMenu(player, slot);
        MemorySource source = new MemorySource(1);
        boolean filled = TransferService.fill(player, request(player), List.of(source));
        StorageFill.LOGGER.info("AUDIT_SYNC_CONSUME|filled={}|before_target=0|attempted=1|safe_insert_remainder={}|after_target={}|extracted={}|refunded={}|source_planks={}|produced_sticks={}",
            filled, slot.reportedRemainder, slot.getItem().getCount(), source.extracted, source.refunded, source.amount, slot.produced());
        helper.assertTrue(slot.reportedRemainder == 0 && slot.produced() == 4 && slot.getItem().isEmpty(),
            "Synchronous consumption audit did not exercise an honestly accepted and immediately consumed insertion");
        helper.assertTrue(source.amount + countPlayer(player, Items.OAK_PLANKS) == 0,
            "Synchronous consumption retained four produced sticks and refunded the consumed plank");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "slot_conservation")
    public static void synchronousRefillDuringTakeDoesNotLoseReturnedMaterial(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        RefillingSlot slot = new RefillingSlot();
        slot.set(new ItemStack(Items.OAK_PLANKS));
        player.containerMenu = new AuditMenu(player, slot);
        MemorySource source = new MemorySource(0);
        boolean filled = TransferService.fill(player, request(player), List.of(source));
        int remaining = slot.getItem().getCount() + slot.bufferCount() + source.amount + countPlayer(player, Items.OAK_PLANKS);
        StorageFill.LOGGER.info("AUDIT_SYNC_TAKE|filled={}|before_slot=1|before_buffer=1|safe_take_returned={}|after_slot={}|after_buffer={}|known_source={}|remaining_total={}",
            filled, slot.returned, slot.getItem().getCount(), slot.bufferCount(), source.amount, remaining);
        helper.assertTrue(slot.returned == 1 && slot.bufferCount() == 0,
            "Synchronous take audit did not exercise a real removal followed by a refill from another inventory");
        helper.assertTrue(remaining == 2, "Synchronous refill hid a real slot withdrawal and lost its returned material");
        helper.succeed();
    }

    private static int countPlayer(ServerPlayer player, Item item) {
        return player.getInventory().items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @GameTest(template = "empty", batch = "slot_conservation")
    public static void refillThenThrowPreservesTheRealWithdrawal(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        RefillingSlot slot = new RefillingSlot();
        slot.failAfterRefill = true;
        slot.set(new ItemStack(Items.OAK_PLANKS));
        SlotMutation.Take taken = SlotMutation.take(slot, 1, player);
        helper.assertTrue(taken.failed() && taken.stack().getCount() == 1 && slot.bufferCount() == 0,
            "Refill followed by a callback exception hid the original withdrawal");
        helper.assertTrue(taken.stack().getCount() + slot.getItem().getCount() == 2,
            "Refill exception destroyed or duplicated material");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "slot_conservation")
    public static void lyingTakeReturnCannotCreateAWithdrawal(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Slot slot = new Slot(new SimpleContainer(1), 0, 0, 0) {
            @Override
            public ItemStack safeTake(int amount, int maximum, Player player) {
                return new ItemStack(Items.OAK_PLANKS);
            }
        };
        slot.set(new ItemStack(Items.OAK_PLANKS));
        SlotMutation.Take taken = SlotMutation.take(slot, 1, player);
        helper.assertTrue(taken.failed() && taken.stack().isEmpty() && slot.getItem().getCount() == 1,
            "A fabricated take return was treated as a real withdrawal");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "slot_conservation")
    public static void fragmentedHoldingsUseOneTargetWrite(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        int[] writes = {0};
        Slot target = new Slot(new SimpleContainer(1), 0, 0, 0) {
            @Override
            public ItemStack safeInsert(ItemStack stack, int amount) {
                writes[0]++;
                return super.safeInsert(stack, amount);
            }
        };
        player.containerMenu = new AuditMenu(player, target);
        for (int index = 0; index < 36; index++) player.getInventory().items.set(index, new ItemStack(Items.OAK_PLANKS));
        FillNetwork.Fill ordinary = request(player);
        FillNetwork.Fill maximum = new FillNetwork.Fill(ordinary.menu(), ordinary.state(), ordinary.request(),
            ordinary.inputs(), ordinary.inventory(), true, true);
        helper.assertTrue(TransferService.fill(player, maximum, List.of()) && writes[0] == 1
            && target.getItem().getCount() == 36 && player.getInventory().items.stream().allMatch(ItemStack::isEmpty),
            "Fragmented materials were not conserved in one target insertion");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "slot_conservation")
    public static void coalescedRollbackKeepsEachOriginalSource(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        Slot first = new Slot(new SimpleContainer(1), 0, 0, 0);
        Slot rejecting = new Slot(new SimpleContainer(1), 0, 0, 0) {
            @Override
            public ItemStack safeInsert(ItemStack stack, int amount) { return ItemStack.EMPTY; }
        };
        player.containerMenu = new AuditMenu(player, first, rejecting);
        player.getInventory().items.set(0, new ItemStack(Items.OAK_PLANKS));
        MemorySource sourceOne = new MemorySource(1);
        MemorySource sourceTwo = new MemorySource(1);
        FillNetwork.Fill request = new FillNetwork.Fill(player.containerMenu.containerId, player.containerMenu.getStateId(), 1,
            List.of(new FillNetwork.Input(0, List.of(new ItemStack(Items.OAK_PLANKS, 2))),
                new FillNetwork.Input(1, List.of(new ItemStack(Items.OAK_PLANKS)))), IntStream.range(2, 38).toArray(), false, true);
        helper.assertFalse(TransferService.fill(player, request, List.of(sourceOne, sourceTwo)), "Refused second insertion was accepted");
        helper.assertTrue(first.getItem().isEmpty() && rejecting.getItem().isEmpty()
            && sourceOne.amount == 1 && sourceTwo.amount == 1 && countPlayer(player, Items.OAK_PLANKS) == 1,
            "Coalesced rollback lost a source identity or changed total material ownership");
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "sync-slot-audit"));
    }

    private static FillNetwork.Fill request(ServerPlayer player) {
        return new FillNetwork.Fill(player.containerMenu.containerId, player.containerMenu.getStateId(), 1,
            List.of(new FillNetwork.Input(0, List.of(new ItemStack(Items.OAK_PLANKS)))),
            IntStream.range(1, 37).toArray(), false, true);
    }

    private static final class AuditMenu extends AbstractContainerMenu {
        private AuditMenu(ServerPlayer player, Slot... targets) {
            super(null, 91);
            for (Slot target : targets) addSlot(target);
            for (int index = 0; index < 36; index++) addSlot(new Slot(player.getInventory(), index, 0, 0));
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

        @Override
        public boolean stillValid(Player player) { return true; }
    }

    private static final class ConsumingSlot extends Slot {
        private final SimpleContainer output = new SimpleContainer(1);
        private int reportedRemainder = -1;

        private ConsumingSlot() { super(new SimpleContainer(1), 0, 0, 0); }

        @Override
        public boolean mayPlace(ItemStack stack) { return stack.is(Items.OAK_PLANKS); }

        @Override
        public void set(ItemStack stack) {
            super.set(stack);
            if (!stack.isEmpty() && stack.is(Items.OAK_PLANKS)) {
                output.setItem(0, new ItemStack(Items.STICK, produced() + stack.getCount() * 4));
                container.setItem(getContainerSlot(), ItemStack.EMPTY);
            }
        }

        private int produced() { return output.getItem(0).getCount(); }

        @Override
        public ItemStack safeInsert(ItemStack stack, int count) {
            ItemStack remainder = super.safeInsert(stack, count);
            reportedRemainder = remainder.getCount();
            return remainder;
        }
    }

    private static final class RefillingSlot extends Slot {
        private final SimpleContainer buffer = new SimpleContainer(1);
        private int returned;
        private boolean failAfterRefill;

        private RefillingSlot() {
            super(new SimpleContainer(1), 0, 0, 0);
            buffer.setItem(0, new ItemStack(Items.OAK_PLANKS));
        }

        private int bufferCount() { return buffer.getItem(0).getCount(); }

        @Override
        public void onTake(Player player, ItemStack taken) {
            super.onTake(player, taken);
            returned += taken.getCount();
            if (getItem().isEmpty() && !buffer.getItem(0).isEmpty()) {
                super.set(buffer.removeItem(0, 1));
            }
            if (failAfterRefill) throw new IllegalStateException("Injected after-refill failure");
        }
    }

    private static final class MemorySource implements StorageSource {
        private int amount;
        private int extracted;
        private int refunded;

        private MemorySource(int amount) { this.amount = amount; }

        @Override
        public List<ItemStack> snapshot(Set<Item> wanted) {
            return amount > 0 && wanted.contains(Items.OAK_PLANKS) ? List.of(new ItemStack(Items.OAK_PLANKS, amount)) : List.of();
        }

        @Override
        public ItemStack extract(ItemStack prototype, int requested, boolean simulate) {
            if (!prototype.is(Items.OAK_PLANKS) || requested <= 0 || amount <= 0) return ItemStack.EMPTY;
            int count = Math.min(amount, requested);
            if (!simulate) {
                amount -= count;
                extracted += count;
            }
            return new ItemStack(Items.OAK_PLANKS, count);
        }

        @Override
        public ItemStack insert(ItemStack stack) {
            if (!stack.is(Items.OAK_PLANKS)) return stack;
            amount += stack.getCount();
            refunded += stack.getCount();
            return ItemStack.EMPTY;
        }

        @Override
        public void finish(int extracted) { }
    }
}

package dev.airun.storagefill;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class SlotMutation {
    private static final ThreadLocal<Removal> REMOVAL = new ThreadLocal<>();
    private SlotMutation() { }

    record Take(ItemStack stack, boolean failed) { }
    record Insert(int inserted, int uncertain, boolean failed) { }

    public static void observeRemoval(Slot slot, ItemStack stack) {
        Removal removal = REMOVAL.get();
        if (removal != null && removal.slot == slot && removal.stack.isEmpty() && !stack.isEmpty()) {
            removal.stack = stack.copy();
        }
    }

    static Take take(Slot slot, int amount, ServerPlayer player) {
        ItemStack before = slot.getItem().copy();
        if (before.isEmpty()) return new Take(ItemStack.EMPTY, false);
        ItemStack returned = ItemStack.EMPTY;
        boolean failed = false;
        Removal previous = REMOVAL.get();
        Removal removal = new Removal(slot);
        REMOVAL.set(removal);
        try {
            returned = slot.safeTake(amount, amount, player);
        } catch (RuntimeException | LinkageError error) {
            failed = true;
            StorageFill.LOGGER.error("Slot extraction callback failed; checking actual change", error);
        } finally {
            if (previous == null) REMOVAL.remove();
            else REMOVAL.set(previous);
        }
        ItemStack confirmed = removal.stack;
        try {
            if (!confirmed.isEmpty() && (confirmed.getCount() > amount || !same(before, confirmed))) {
                return new Take(confirmed, true);
            }
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Unable to validate observed slot extraction", error);
            return new Take(confirmed, true);
        }
        ItemStack after;
        try {
            after = slot.getItem().copy();
            if (!after.isEmpty() && !same(before, after)) {
                StorageFill.LOGGER.error("Slot changed item identity during extraction; preserving only returned items");
                return new Take(confirmed.isEmpty() ? returned.copy() : confirmed, true);
            }
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Cannot observe slot after extraction; preserving only returned items", error);
            return new Take(confirmed.isEmpty() ? returned.copy() : confirmed, true);
        }
        int removed = before.getCount() - after.getCount();
        if (!confirmed.isEmpty()) {
            try {
                boolean consistent = removed == confirmed.getCount() && returned.getCount() == confirmed.getCount()
                    && same(confirmed, returned);
                return new Take(confirmed, failed || !consistent);
            } catch (RuntimeException | LinkageError error) {
                StorageFill.LOGGER.error("Unable to validate returned slot extraction", error);
                return new Take(confirmed, true);
            }
        }
        if (removed < 0) return new Take(ItemStack.EMPTY, true);
        boolean consistent = false;
        try {
            consistent = removed <= amount && returned.getCount() == removed
                    && (returned.isEmpty() || same(before, returned));
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Unable to validate returned slot extraction", error);
        }
        return new Take(before.copyWithCount(removed), failed || !consistent);
    }

    static Insert insert(Slot slot, ItemStack stack, int amount) {
        ItemStack before = slot.getItem().copy();
        int attempted = Math.min(amount, stack.getCount());
        if (attempted <= 0 || (!before.isEmpty() && !same(before, stack))) return new Insert(0, 0, true);
        ItemStack offered = stack.copyWithCount(attempted);
        ItemStack returned = offered;
        boolean failed = false;
        try {
            returned = slot.safeInsert(offered, attempted);
        } catch (RuntimeException | LinkageError error) {
            failed = true;
            StorageFill.LOGGER.error("Slot insertion callback failed; checking actual change", error);
        }
        ItemStack after;
        try {
            after = slot.getItem().copy();
            if (!after.isEmpty() && !same(stack, after)) {
                StorageFill.LOGGER.error("Slot changed item identity during insertion; not refunding uncertain writes");
                return new Insert(0, attempted, true);
            }
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Cannot observe slot after insertion; not refunding uncertain writes", error);
            return new Insert(0, attempted, true);
        }
        int inserted = after.getCount() - before.getCount();
        if (inserted < 0 || inserted > attempted) {
            StorageFill.LOGGER.error("Slot insertion changed an unexpected number of items");
            return new Insert(Math.max(0, Math.min(attempted, inserted)), 0, true);
        }
        boolean consistent = false;
        int accepted = attempted - offered.getCount();
        int consumed = Math.max(0, Math.min(attempted - inserted, accepted - inserted));
        try {
            consistent = consumed == 0 && returned.getCount() == attempted - inserted
                    && (returned.isEmpty() || same(stack, returned));
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Unable to validate slot insertion remainder", error);
        }
        return new Insert(inserted, consumed, failed || !consistent);
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return StackKey.of(first).equals(StackKey.of(second));
    }

    private static final class Removal {
        private final Slot slot;
        private ItemStack stack = ItemStack.EMPTY;

        private Removal(Slot slot) { this.slot = slot; }
    }
}

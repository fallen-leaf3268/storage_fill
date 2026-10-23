package dev.airun.storagefill.storage;

import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.StorageFill;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.inventory.IInventoryPartHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class SophisticatedBackpacksSource implements StorageSource, NativeRecipeQuerySource {
    private final ServerPlayer player;
    private final UUID contentsUuid;
    private final Object handlerIdentity;
    private final ItemStack originalBackpack;

    private SophisticatedBackpacksSource(ServerPlayer player, UUID contentsUuid, Object handlerIdentity,
                                         ItemStack originalBackpack) {
        this.player = player;
        this.contentsUuid = contentsUuid;
        this.handlerIdentity = handlerIdentity;
        this.originalBackpack = originalBackpack;
    }

    static void find(ServerPlayer player, List<StorageSource> sources) {
        Set<UUID> uuids = new java.util.HashSet<>();
        Set<Object> handlers = Collections.newSetFromMap(new IdentityHashMap<>());
        PlayerInventoryProvider.get().runOnBackpacks(player, (backpack, ignoredName, ignoredIdentifier, ignoredSlot) -> {
            try {
                resolve(backpack).ifPresent(wrapper -> {
                    IItemHandler handler = wrapper.getInventoryHandler();
                    Optional<UUID> uuid = wrapper.getContentsUuid();
                    if (handlers.contains(handler) || uuid.filter(uuids::contains).isPresent()) return;
                    handlers.add(handler);
                    uuid.ifPresent(uuids::add);
                    sources.add(new SophisticatedBackpacksSource(player, uuid.orElse(null), handler, backpack));
                });
            } catch (RuntimeException | LinkageError ignored) { }
            return false;
        });
    }

    @Override
    public int sourceId() {
        return SOPHISTICATED_BACKPACKS;
    }

    @Override
    public Object identity() { return contentsUuid != null ? contentsUuid : handlerIdentity; }

    @Override
    public Object changeIdentity() { return handlerIdentity; }

    @Override
    public Object queryKey(ItemStack prototype) { return StackKey.of(prototype); }

    @Override
    public boolean queryUsesPosition() { return true; }

    @Override
    public Scan scanQuery(Set<Item> wanted, Set<Object> seen, int position, int scanLimit) {
        Optional<IBackpackWrapper> located = locate();
        if (wanted.isEmpty() || located.isEmpty()) return new Scan(ItemStack.EMPTY, position, 0, true);
        var handler = located.get().getInventoryHandler();
        if (Collections.disjoint(wanted, handler.getSlotTracker().getItems())) return new Scan(ItemStack.EMPTY, position, 0, true);
        int scanned = 0;
        while (position < handler.getSlots() && scanned < scanLimit) {
            ItemStack stack = handler.getStackInSlot(position++);
            scanned++;
            if (!stack.isEmpty() && wanted.contains(stack.getItem()) && !seen.contains(StackKey.of(stack)))
                return new Scan(stack.copyWithCount(1), position, scanned, false);
        }
        return new Scan(ItemStack.EMPTY, position, scanned, position >= handler.getSlots());
    }

    @Override
    public long countMatching(Item item, java.util.function.Predicate<ItemStack> matches, long limit) {
        if (item == null || limit <= 0) return 0;
        Optional<IBackpackWrapper> located = locate();
        if (located.isEmpty()) return 0;
        var handler = located.get().getInventoryHandler();
        if (!handler.getSlotTracker().getItems().contains(item)) return 0;
        long count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack present = handler.getStackInSlot(slot);
            if (present.isEmpty() || !present.is(item) || !matches.test(present.copyWithCount(1))) continue;
            int requested = (int) Math.min(limit - count, present.getCount());
            ItemStack available = handler.extractItem(slot, requested, true);
            if (!available.isEmpty() && ItemStack.isSameItemSameTags(present, available)) {
                count += Math.min(requested, available.getCount());
                if (count >= limit) break;
            }
        }
        return count;
    }

    @Override
    public List<ItemStack> snapshot(Set<Item> wanted) {
        Optional<IBackpackWrapper> located = locate();
        if (located.isEmpty()) return List.of();
        var handler = located.get().getInventoryHandler();
        if (Collections.disjoint(wanted, handler.getSlotTracker().getItems())) return List.of();
        Map<StackKey, ItemStack> result = new LinkedHashMap<>();
        try {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack present = handler.getStackInSlot(slot);
                if (present.isEmpty() || !wanted.contains(present.getItem())) continue;
                StackKey key = StackKey.of(present);
                ItemStack aggregate = result.get(key);
                int remainingLimit = StorageSources.MAX_COUNT - (aggregate == null ? 0 : aggregate.getCount());
                if (remainingLimit <= 0) continue;
                ItemStack available = handler.extractItem(slot, Math.min(present.getCount(), remainingLimit), true);
                if (available.isEmpty() || !StackKey.of(present).equals(StackKey.of(available))) continue;
                int count = Math.min(available.getCount(), remainingLimit);
                if (aggregate == null) result.put(key, available.copyWithCount(count));
                else aggregate.setCount(aggregate.getCount() + count);
            }
        } catch (RuntimeException | LinkageError ignored) { }
        return new ArrayList<>(result.values());
    }

    @Override
    public boolean visitPreview(java.util.function.Predicate<ItemStack> visitor) {
        return visitInventory(null, visitor);
    }

    @Override
    public boolean visitPreview(Set<Item> wanted, java.util.function.Predicate<ItemStack> visitor) {
        if (wanted.isEmpty()) return true;
        return visitInventory(wanted, visitor);
    }

    private boolean visitInventory(Set<Item> wanted, java.util.function.Predicate<ItemStack> visitor) {
        Optional<IBackpackWrapper> located = locate();
        if (located.isEmpty()) return true;
        var handler = located.get().getInventoryHandler();
        if (wanted != null && Collections.disjoint(wanted, handler.getSlotTracker().getItems())) return true;
        Map<StackKey, Integer> counts = new LinkedHashMap<>();
        int slots;
        try { slots = handler.getSlots(); }
        catch (RuntimeException | LinkageError error) { return false; }
        for (int slot = 0; slot < slots; slot++) {
            ItemStack available;
            try {
                ItemStack present = handler.getStackInSlot(slot);
                if (present.isEmpty() || wanted != null && !wanted.contains(present.getItem())) continue;
                StackKey key = StackKey.of(present);
                int previous = counts.getOrDefault(key, 0);
                int remaining = StorageSources.MAX_COUNT - previous;
                if (remaining <= 0) continue;
                available = handler.extractItem(slot, Math.min(present.getCount(), remaining), true);
                if (available.isEmpty() || !key.equals(StackKey.of(available))) continue;
                int count = Math.min(remaining, available.getCount());
                counts.put(key, previous + count);
                available = available.copyWithCount(count);
            } catch (RuntimeException | LinkageError error) {
                return false;
            }
            if (!visitor.test(available)) return false;
        }
        return true;
    }

    @Override
    public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
        if (prototype.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        Optional<IBackpackWrapper> located = locate();
        if (located.isEmpty()) return ItemStack.EMPTY;
        IItemHandler handler = located.get().getInventoryHandler();
        StackKey wanted = StackKey.of(prototype);
        int remaining = Math.min(amount, StorageSources.MAX_COUNT);
        ItemStack result = ItemStack.EMPTY;
        int slots;
        try { slots = handler.getSlots(); }
        catch (RuntimeException | LinkageError error) { return result; }
        for (int slot = 0; slot < slots && remaining > 0; slot++) {
            ItemStack present;
            try {
                present = handler.getStackInSlot(slot);
                if (present.isEmpty() || !present.is(prototype.getItem()) || !Objects.equals(present.getTag(), prototype.getTag())) continue;
                present = present.copy();
            } catch (RuntimeException | LinkageError error) { return result; }
            try {
                if (present.isEmpty() || !wanted.equals(StackKey.of(present))) continue;
            } catch (RuntimeException | LinkageError error) {
                return result;
            }
            ItemStack taken;
            try {
                taken = handler.extractItem(slot, remaining, simulate);
            } catch (RuntimeException | LinkageError error) {
                if (!simulate) {
                    try {
                        ItemStack after = handler.getStackInSlot(slot);
                        int changed = after.isEmpty() ? present.getCount()
                            : wanted.equals(StackKey.of(after)) ? Math.max(0, present.getCount() - after.getCount()) : 0;
                        result = append(result, present, Math.min(remaining, changed));
                    } catch (RuntimeException | LinkageError ignored) { }
                }
                return result;
            }
            if (taken.isEmpty()) continue;
            boolean matches;
            try { matches = wanted.equals(StackKey.of(taken)); }
            catch (RuntimeException | LinkageError error) {
                if (!simulate) returnUnexpected(taken);
                return result;
            }
            if (!matches) {
                if (!simulate) returnUnexpected(taken);
                return result;
            }
            if (result.isEmpty()) result = taken;
            else result.setCount(result.getCount() + taken.getCount());
            remaining -= taken.getCount();
        }
        return result;
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        return stack;
    }

    @Override
    public ItemStack insertReward(ItemStack stack) {
        if (stack.isEmpty()) return stack;
        Optional<IBackpackWrapper> located = locate();
        if (located.isEmpty()) return stack;
        var handler = located.get().getInventoryHandler();
        ItemStack remaining = stack.copy();
        StackKey wanted = StackKey.of(stack);
        int firstPass = wanted.data().contains("tag") || wanted.data().contains("ForgeCaps") ? 1 : 0;
        for (int pass = firstPass; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
                if (handler.getInventoryPartitioner().getPartBySlot(slot).getClass() != IInventoryPartHandler.Default.class
                    || !handler.isItemValid(slot, remaining, player)) continue;
                ItemStack present = handler.getStackInSlot(slot);
                if (present.isEmpty() != (pass == 1)
                    || !present.isEmpty() && (present.getItem() != stack.getItem() || present.getTag() != null)) continue;
                int previous = present.getCount();
                int amount = (int) Math.min(remaining.getCount(),
                    Math.max(0L, (long) handler.getStackLimit(slot, remaining) - previous));
                if (amount <= 0) continue;
                if (!present.isEmpty() && !wanted.equals(StackKey.of(present))) continue;
                ItemStack merged = (present.isEmpty() ? remaining : present).copyWithCount(previous + amount);
                try {
                    handler.setStackInSlot(slot, merged);
                } catch (RuntimeException | LinkageError error) {
                    try {
                        remaining.shrink(confirmedInsertion(handler.getStackInSlot(slot), wanted, previous, amount));
                    } catch (RuntimeException | LinkageError readError) {
                        error.addSuppressed(readError);
                        throw error;
                    }
                    StorageFill.LOGGER.error("Backpack reward insertion stopped after a slot callback failed", error);
                    return remaining;
                }
                int inserted = confirmedInsertion(handler.getStackInSlot(slot), wanted, previous, amount);
                remaining.shrink(inserted);
                if (inserted != amount) return remaining;
            }
        }
        return remaining;
    }

    private static int confirmedInsertion(ItemStack after, StackKey wanted, int previous, int requested) {
        if (after.isEmpty()) {
            if (previous == 0) return 0;
            throw new IllegalStateException("Backpack reward slot lost its previous contents during insertion");
        }
        int inserted = after.getCount() - previous;
        if (!wanted.equals(StackKey.of(after)) || inserted < 0 || inserted > requested)
            throw new IllegalStateException("Backpack reward insertion produced an indeterminate slot change");
        return inserted;
    }

    @Override
    public void finish(int extracted) { }

    private Optional<IBackpackWrapper> locate() {
        List<IBackpackWrapper> match = new ArrayList<>(1);
        PlayerInventoryProvider.get().runOnBackpacks(player, (backpack, ignoredName, ignoredIdentifier, ignoredSlot) -> {
            Optional<IBackpackWrapper> resolved = resolve(backpack);
            if (resolved.isEmpty()) return false;
            IBackpackWrapper wrapper = resolved.get();
            IItemHandler handler = wrapper.getInventoryHandler();
            boolean same = contentsUuid != null
                ? wrapper.getContentsUuid().filter(contentsUuid::equals).isPresent()
                : backpack == originalBackpack && handler == handlerIdentity;
            if (same) match.add(wrapper);
            return same;
        });
        return match.stream().findFirst();
    }

    private static ItemStack append(ItemStack result, ItemStack prototype, int count) {
        if (count <= 0) return result;
        if (result.isEmpty()) return prototype.copyWithCount(count);
        result.setCount(result.getCount() + count);
        return result;
    }

    private void returnUnexpected(ItemStack taken) {
        player.getInventory().add(taken);
        if (!taken.isEmpty()) player.drop(taken, false);
    }

    private static Optional<IBackpackWrapper> resolve(ItemStack backpack) {
        try {
            return backpack.getCapability(CapabilityBackpackWrapper.getCapabilityInstance()).resolve();
        } catch (RuntimeException | LinkageError error) {
            return Optional.empty();
        }
    }
}

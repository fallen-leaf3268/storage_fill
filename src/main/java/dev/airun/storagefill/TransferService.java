package dev.airun.storagefill;

import dev.airun.storagefill.storage.StorageSource;
import dev.airun.storagefill.storage.StorageSources;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.*;

public final class TransferService {
    private TransferService() { }

    public static TransferResult topUpDetailed(ServerPlayer player, int menuId, int state, List<ItemStack> required) {
        try {
            return topUpDetailed(player, menuId, state, required, StorageSources.find(player));
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Storage top-up unavailable", error);
            return failed("storage_fill.failed.extract");
        }
    }

    static TransferResult topUpDetailed(ServerPlayer player, int menuId, int state, List<ItemStack> required,
                                        List<StorageSource> sources) {
        Failure failure = new Failure();
        return topUpCore(player, menuId, state, required, sources, failure) ? TransferResult.ok() : failure.result;
    }

    public static boolean topUp(ServerPlayer player, int menuId, int state, List<ItemStack> required) {
        try {
            return topUp(player, menuId, state, required, StorageSources.find(player));
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Storage top-up unavailable", error);
            return false;
        }
    }

    static boolean topUp(ServerPlayer player, int menuId, int state, List<ItemStack> required,
                         List<StorageSource> sources) {
        return topUpCore(player, menuId, state, required, sources, new Failure());
    }

    private static boolean topUpCore(ServerPlayer player, int menuId, int state, List<ItemStack> required,
                                     List<StorageSource> sources, Failure failure) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != menuId || menu.getStateId() != state || !menu.stillValid(player)) return failure.fail("storage_fill.failed.menu_changed");
        List<ItemStack> original = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) original.add(player.getInventory().items.get(i).copy());
        Optional<InventoryTopUp.Plan> planned = InventoryTopUp.plan(original, required);
        if (planned.isEmpty()) return failure.fail("storage_fill.failed.inventory_full");
        InventoryTopUp.Plan plan = planned.get();
        List<TopUpReservation> reservations = new ArrayList<>();
        List<ItemStack> unavailable = new ArrayList<>();
        Map<StorageSource, Map<StackKey, Integer>> capacities = new IdentityHashMap<>();
        Map<StackKey, Integer> pending = new LinkedHashMap<>();
        for (ItemStack missing : plan.missing()) {
            StackKey key = StackKey.of(missing);
            pending.put(key, missing.getCount());
            int remaining = missing.getCount();
            for (StorageSource source : sources) {
                Map<StackKey, Integer> counts = capacities.computeIfAbsent(source, ignored -> new LinkedHashMap<>());
                Integer capacity = counts.get(key);
                if (capacity == null) capacity = targetedCount(source, key, missing, remaining);
                while (remaining > 0 && capacity > 0) {
                    int amount = Math.min(Math.min(remaining, capacity), Math.min(64, missing.getMaxStackSize()));
                    reservations.add(new TopUpReservation(source, key, missing.copyWithCount(1), amount));
                    remaining -= amount;
                    capacity -= amount;
                }
                counts.put(key, capacity);
                if (remaining == 0) break;
            }
            if (remaining != 0) {
                unavailable.add(missing.copyWithCount(remaining));
            }
        }
        if (!unavailable.isEmpty()) {
            finishTopUpSources(sources, Map.of(), false);
            return failure.missing(unavailable, false);
        }

        List<Holding> holdings = new ArrayList<>();
        Map<StorageSource, Integer> extracted = new IdentityHashMap<>();
        boolean committed = false;
        try {
            for (TopUpReservation reservation : reservations) {
                ItemStack taken;
                try {
                    taken = reservation.source.extract(reservation.prototype, reservation.count, false);
                } catch (RuntimeException | LinkageError error) {
                    StorageFill.LOGGER.error("Storage top-up extraction failed for one source", error);
                    return failure.fail("storage_fill.failed.extract");
                }
                if (!taken.isEmpty()) holdings.add(new Holding(reservation.source, taken.copy()));
                if (taken.isEmpty() || !reservation.key.equals(StackKey.of(taken)) || taken.getCount() != reservation.count) {
                    int confirmed = !taken.isEmpty() && reservation.key.equals(StackKey.of(taken))
                        ? Math.min(reservation.count, taken.getCount()) : 0;
                    return failure.missing(List.of(reservation.prototype.copyWithCount(
                        Math.max(1, pending.get(reservation.key) - confirmed))), false);
                }
                pending.computeIfPresent(reservation.key, (ignored, count) -> count - taken.getCount());
                extracted.merge(reservation.source, taken.getCount(), Integer::sum);
            }
            if (player.containerMenu != menu || menu.containerId != menuId || menu.getStateId() != state
                    || !menu.stillValid(player)) return failure.fail("storage_fill.failed.menu_changed");
            List<ItemStack> current = new ArrayList<>(36);
            for (int i = 0; i < 36; i++) current.add(player.getInventory().items.get(i).copy());
            Optional<InventoryTopUp.Plan> refreshed = InventoryTopUp.plan(current, required);
            if (refreshed.isEmpty()) return failure.fail("storage_fill.failed.inventory_full");
            if (!sameRequirements(plan.missing(), refreshed.get().missing())) return failure.fail("storage_fill.failed.menu_changed");
            List<TopUpSlotUpdate> updates = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                ItemStack present = player.getInventory().items.get(i);
                ItemStack replacement = refreshed.get().contents().get(i);
                if (!sameStack(present, replacement)) updates.add(new TopUpSlotUpdate(i, replacement.copy()));
            }
            for (TopUpSlotUpdate update : updates) player.getInventory().items.set(update.slot, update.stack);
            committed = true;
            if (!updates.isEmpty()) {
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastFullState();
                if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastFullState();
            }
            return true;
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Storage top-up interrupted", error);
            return failure.fail("storage_fill.failed.extract");
        } finally {
            if (!committed) for (Holding holding : holdings) refundSafely(player, holding, true);
            finishTopUpSources(sources, extracted, committed);
        }
    }

    private static int targetedCount(StorageSource source, StackKey key, ItemStack prototype, int requested) {
        int amount = Math.min(StorageSources.MAX_COUNT, requested);
        try {
            ItemStack simulated = source.extract(prototype.copyWithCount(1), amount, true);
            if (simulated.isEmpty()) return 0;
            if (!key.equals(StackKey.of(simulated)) || simulated.getCount() > amount) {
                StorageFill.LOGGER.error("Storage simulation returned unexpected materials or quantity");
                return 0;
            }
            return simulated.getCount();
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Unable to simulate requested storage material", error);
            return 0;
        }
    }

    private static boolean sameRequirements(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (!sameStack(first.get(i), second.get(i))) return false;
        return true;
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty();
        return first.getCount() == second.getCount() && StackKey.of(first).equals(StackKey.of(second));
    }

    private static void finishTopUpSources(List<StorageSource> sources, Map<StorageSource, Integer> extracted,
                                           boolean success) {
        Set<StorageSource> finished = Collections.newSetFromMap(new IdentityHashMap<>());
        for (StorageSource source : sources) {
            if (!finished.add(source)) continue;
            try { source.finish(success ? extracted.getOrDefault(source, 0) : 0); }
            catch (RuntimeException | LinkageError error) {
                StorageFill.LOGGER.error("Storage top-up cleanup failed", error);
            }
        }
    }

    public static void fill(ServerPlayer player, FillNetwork.Fill request) {
        try {
            TransferResult result = fillDetailed(player, request);
            if (!result.success()) player.displayClientMessage(result.message(), false);
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Storage fill unavailable", error);
            player.displayClientMessage(Component.translatable("storage_fill.failed.extract"), false);
        }
    }

    public static TransferResult fillDetailed(ServerPlayer player, FillNetwork.Fill request) {
        try {
            return fillDetailed(player, request, StorageSources.find(player));
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Storage fill unavailable", error);
            return failed("storage_fill.failed.extract");
        }
    }

    static TransferResult fillDetailed(ServerPlayer player, FillNetwork.Fill request, List<StorageSource> sources) {
        Failure failure = new Failure();
        return fillCore(player, request, sources, failure) ? TransferResult.ok() : failure.result;
    }

    private static TransferResult missing(List<ItemStack> stacks) {
        return missing(stacks, false);
    }

    private static TransferResult missing(List<ItemStack> stacks, boolean alternatives) {
        Component list = Component.empty();
        int shown = Math.min(8, stacks.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) list = list.copy().append(Component.literal("、"));
            String name = stacks.get(i).getHoverName().getString();
            Component display = name.length() > 128 ? Component.literal(name.substring(0, 128)) : stacks.get(i).getHoverName().copy();
            list = list.copy().append(display).append(Component.literal(" × " + stacks.get(i).getCount()));
        }
        if (stacks.size() > shown) list = list.copy().append(Component.translatable(
            "storage_fill.failed.more", stacks.size() - shown));
        return TransferResult.failure(Component.translatable(alternatives
            ? "storage_fill.failed.missing_alternatives" : "storage_fill.failed.missing", list));
    }

    private static TransferResult failed(String key) {
        return TransferResult.failure(Component.translatable(key));
    }

    static boolean fill(ServerPlayer player, FillNetwork.Fill request, List<StorageSource> sources) {
        return fillCore(player, request, sources, new Failure());
    }

    private static boolean fillCore(ServerPlayer player, FillNetwork.Fill request, List<StorageSource> sources,
                                    Failure failure) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != request.menu() || menu.getStateId() != request.state() || !menu.stillValid(player)) return failure.fail("storage_fill.failed.menu_changed");
        int[] targets = request.inputs().stream().mapToInt(FillNetwork.Input::slot).toArray();
        if (!RequestLimits.validSlots(menu.slots.size(), targets, request.inventory())) return failure.fail("storage_fill.failed.invalid");
        List<StorageSource> available = new ArrayList<>();
        List<Slot> inventory = new ArrayList<>();
        Map<Object, Set<Integer>> physicalSlots = new IdentityHashMap<>();
        for (int index : request.inventory()) {
            Slot slot = menu.getSlot(index);
            if (slot.container != player.getInventory() || !distinct(physicalSlots, slot)) return failure.fail("storage_fill.failed.invalid");
            inventory.add(slot);
            available.add(new SlotSource(slot, player));
        }
        List<AllocationPlanner.Need<StackKey>> needs = new ArrayList<>();
        List<Slot> usedTargets = new ArrayList<>();
        Set<Item> wanted = new HashSet<>();
        int candidates = 0;
        for (FillNetwork.Input input : request.inputs()) {
            Slot slot = menu.getSlot(input.slot());
            if (!distinct(physicalSlots, slot) || (!slot.getItem().isEmpty() && !slot.mayPickup(player))) return failure.fail("storage_fill.failed.invalid");
            if (!slot.getItem().isEmpty() && !slot.mayPlace(slot.getItem())) return failure.fail("storage_fill.failed.invalid");
            available.add(new SlotSource(slot, player));
            if (input.candidates().isEmpty()) continue;
            candidates += input.candidates().size();
            if (candidates > RequestLimits.MAX_CANDIDATES) return failure.fail("storage_fill.failed.invalid");
            List<StackKey> keys = new ArrayList<>();
            Map<StackKey, Integer> candidateLimits = new LinkedHashMap<>();
            int count = input.candidates().get(0).getCount(), limit = 0;
            if (count < 1 || count > 64) return failure.fail("storage_fill.failed.invalid");
            for (ItemStack stack : input.candidates()) {
                if (stack.isEmpty() || stack.getCount() != count || !slot.mayPlace(stack)) return failure.fail("storage_fill.failed.invalid");
                StackKey key = StackKey.of(stack);
                keys.add(key);
                wanted.add(stack.getItem());
                int capacity = Math.min(64, Math.min(slot.getMaxStackSize(stack), stack.getMaxStackSize()));
                candidateLimits.put(key, capacity);
                limit = Math.max(limit, capacity);
            }
            needs.add(new AllocationPlanner.Need<>(keys, count, limit, candidateLimits));
            usedTargets.add(slot);
        }
        if (needs.isEmpty()) return failure.fail("storage_fill.failed.invalid");
        int groups = request.maximum() ? 64 : 1;
        for (var need : needs) groups = Math.min(groups, need.limit() / need.count());
        Map<StackKey, Integer> limits = new LinkedHashMap<>();
        for (var need : needs) {
            for (StackKey key : new LinkedHashSet<>(need.alternatives())) {
                int candidateGroups = need.capacity(key) / need.count();
                if (!request.maximum() || request.completeSets()) candidateGroups = Math.min(groups, candidateGroups);
                int requested = candidateGroups * need.count();
                if (requested > 0) limits.merge(key, requested, (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
            }
        }
        Map<StackKey, Integer> stock = new LinkedHashMap<>();
        Map<StackKey, Integer> localStock = new LinkedHashMap<>();
        Map<StackKey, Integer> networkStock = new LinkedHashMap<>();
        for (StorageSource source : available) {
            for (ItemStack stack : source.snapshot(wanted)) {
                if (stack.isEmpty() || !wanted.contains(stack.getItem())) continue;
                StackKey key = StackKey.of(stack);
                if (!limits.containsKey(key)) continue;
                localStock.merge(key, Math.min(RequestLimits.MAX_COUNT, stack.getCount()),
                    (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
            }
        }
        stock.putAll(localStock);
        networkStock.putAll(localStock);
        Map<StorageSource, Map<StackKey, Integer>> capacities = new IdentityHashMap<>();
        AllocationPlanner.SearchBudget budget = new AllocationPlanner.SearchBudget();
        AllocationPlanner.SearchBudget localBudget = budget.child(10_000);
        Optional<List<AllocationPlanner.Choice<StackKey>>> localPlan = request.maximum()
                ? Optional.empty() : AllocationPlanner.plan(needs, localStock, false, localBudget);
        if (request.maximum() || localPlan.isEmpty()) {
            for (StorageSource source : sources) {
                if (capacities.containsKey(source)) continue;
                Map<StackKey, Integer> counts = new LinkedHashMap<>();
                capacities.put(source, counts);
                for (var entry : limits.entrySet()) {
                    int remaining = entry.getValue() - stock.getOrDefault(entry.getKey(), 0);
                    if (remaining <= 0) continue;
                    int count = targetedCount(source, entry.getKey(), entry.getKey().stack(1), remaining);
                    counts.put(entry.getKey(), count);
                    if (count == 0) continue;
                    stock.merge(entry.getKey(), count, (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
                    if (source.sourceId() != StorageSource.SOPHISTICATED_BACKPACKS) {
                        networkStock.merge(entry.getKey(), count, (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
                    }
                }
            }
        }
        available.addAll(sources);
        Optional<List<AllocationPlanner.Choice<StackKey>>> plan;
        if (!request.maximum() && localPlan.isPresent()) {
            plan = localPlan;
        } else if (!request.maximum() && !localBudget.exhausted() && stock.equals(localStock)) {
            plan = Optional.empty();
        } else {
            plan = AllocationPlanner.planByPriority(needs, stock, localStock, networkStock,
                    request.maximum(), request.completeSets(), budget, budget.remaining());
        }
        if (plan.isEmpty()) return budget.exhausted()
                ? failure.fail("storage_fill.failed.allocate") : failure.allocationMissing(needs, stock);
        List<AllocationPlanner.Choice<StackKey>> choices = plan.get();
        Map<StackKey, Integer> required = new LinkedHashMap<>();
        choices.forEach(choice -> required.merge(choice.key(), choice.count(), Integer::sum));
        if (!hasRoom(player, menu, inventory, targets, required)) return failure.fail("storage_fill.failed.inventory_full");

        List<Holding> holdings = new ArrayList<>();
        List<Holding> cleared = new ArrayList<>();
        List<Placement> placed = new ArrayList<>();
        boolean success = false;
        try {
            for (var requirement : required.entrySet()) {
                ItemStack prototype = requirement.getKey().stack(1);
                int remaining = requirement.getValue();
                for (StorageSource source : available) {
                    while (remaining > 0) {
                        int amount = Math.min(remaining, Math.min(64, prototype.getMaxStackSize()));
                        SlotMutation.Take withdrawal;
                        Map<StackKey, Integer> counts = capacities.get(source);
                        if (source instanceof SlotSource local) {
                            ItemStack simulated = source.extract(prototype, amount, true);
                            if (simulated.isEmpty()) break;
                            if (!requirement.getKey().equals(StackKey.of(simulated)) || simulated.getCount() > amount) return false;
                            amount = simulated.getCount();
                            withdrawal = SlotMutation.take(local.slot, amount, player);
                        } else {
                            int capacity = counts == null ? 0 : counts.getOrDefault(requirement.getKey(), 0);
                            if (capacity <= 0) break;
                            amount = Math.min(amount, capacity);
                            try {
                                withdrawal = new SlotMutation.Take(source.extract(prototype, amount, false), false);
                            } catch (RuntimeException | LinkageError error) {
                                StorageFill.LOGGER.error("Storage extraction failed for one source", error);
                                return failure.fail("storage_fill.failed.extract");
                            }
                        }
                        ItemStack taken = withdrawal.stack();
                        if (!taken.isEmpty()) holdings.add(new Holding(source, taken.copy()));
                        if (withdrawal.failed()) return false;
                        if (taken.isEmpty() || !requirement.getKey().equals(StackKey.of(taken)) || taken.getCount() != amount) {
                            int confirmed = !taken.isEmpty() && requirement.getKey().equals(StackKey.of(taken))
                                ? Math.min(amount, taken.getCount()) : 0;
                            return failure.missing(List.of(prototype.copyWithCount(Math.max(1, remaining - confirmed))), false);
                        }
                        remaining -= taken.getCount();
                        if (!(source instanceof SlotSource)) {
                            counts.put(requirement.getKey(), counts.get(requirement.getKey()) - taken.getCount());
                        }
                    }
                    if (remaining == 0) break;
                }
                if (remaining != 0) return failure.missing(List.of(prototype.copyWithCount(remaining)), false);
            }
            if (player.containerMenu != menu || !menu.stillValid(player)) return failure.fail("storage_fill.failed.menu_changed");
            for (int index : targets) {
                Slot slot = menu.getSlot(index);
                if (!slot.getItem().isEmpty()) {
                    int count = slot.getItem().getCount();
                    SlotMutation.Take withdrawal = SlotMutation.take(slot, count, player);
                    ItemStack taken = withdrawal.stack();
                    if (!taken.isEmpty()) cleared.add(new Holding(new SlotSource(slot, player), taken.copy()));
                    if (withdrawal.failed() || taken.getCount() != count || !slot.getItem().isEmpty()) return false;
                }
            }
            for (int i = 0; i < choices.size(); i++) {
                var choice = choices.get(i);
                Slot target = usedTargets.get(i);
                ItemStack offered = ItemStack.EMPTY;
                int availableCount = 0;
                for (Holding holding : holdings) {
                    if (holding.stack.isEmpty() || !holding.key.equals(choice.key())) continue;
                    if (offered.isEmpty()) offered = holding.stack.copyWithCount(choice.count());
                    availableCount += holding.stack.getCount();
                    if (availableCount >= choice.count()) break;
                }
                if (availableCount < choice.count()) return false;
                SlotMutation.Insert mutation = SlotMutation.insert(target, offered, choice.count());
                int retained = mutation.inserted();
                int consumed = mutation.uncertain();
                for (Holding holding : holdings) {
                    if (holding.stack.isEmpty() || !holding.key.equals(choice.key())) continue;
                    int inserted = Math.min(retained, holding.stack.getCount());
                    if (inserted > 0) {
                        holding.stack.shrink(inserted);
                        retained -= inserted;
                        placed.add(new Placement(target, holding, inserted));
                    }
                    int uncertain = Math.min(consumed, holding.stack.getCount());
                    holding.stack.shrink(uncertain);
                    consumed -= uncertain;
                    if (retained == 0 && consumed == 0) break;
                }
                if (mutation.failed() || mutation.inserted() != choice.count()) return false;
            }
            success = true;
            return true;
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Storage fill interrupted; returning known withdrawals", error);
            return false;
        } finally {
            if (!success) {
                for (int i = placed.size() - 1; i >= 0; i--) {
                    Placement placement = placed.get(i);
                    try {
                        if (!placement.slot.getItem().isEmpty() && placement.origin.key.equals(StackKey.of(placement.slot.getItem()))) {
                            ItemStack recovered = SlotMutation.take(placement.slot, placement.count, player).stack();
                            if (!recovered.isEmpty()) holdings.add(new Holding(placement.origin.source, recovered));
                        }
                    } catch (RuntimeException | LinkageError error) {
                        StorageFill.LOGGER.error("Unable to recover one placement; continuing other refunds", error);
                    }
                }
            }
            for (Holding holding : holdings) refundSafely(player, holding, true);
            for (Holding holding : cleared) refundSafely(player, holding, !success);
            for (StorageSource source : sources) {
                int used = success ? placed.stream().filter(part -> part.origin.source == source).mapToInt(Placement::count).sum() : 0;
                try { source.finish(used); }
                catch (RuntimeException | LinkageError error) { StorageFill.LOGGER.error("Storage cleanup failed", error); }
            }
            try { menu.broadcastChanges(); }
            catch (RuntimeException | LinkageError error) { StorageFill.LOGGER.error("Unable to synchronize menu after transfer", error); }
        }
    }

    private static boolean distinct(Map<Object, Set<Integer>> slots, Slot slot) {
        Object owner = slot instanceof net.minecraftforge.items.SlotItemHandler handler ? handler.getItemHandler() : slot.container;
        return slots.computeIfAbsent(owner, ignored -> new HashSet<>()).add(slot.getContainerSlot());
    }

    private static boolean hasRoom(ServerPlayer player, AbstractContainerMenu menu, List<Slot> inventory, int[] targets, Map<StackKey, Integer> required) {
        Map<StackKey, Integer> remaining = new HashMap<>(required);
        List<ItemStack> contents = new ArrayList<>();
        for (Slot slot : inventory) contents.add(afterUse(slot.getItem(), remaining, slot.mayPickup(player)));
        List<ItemStack> leftovers = new ArrayList<>();
        for (int index : targets) leftovers.add(afterUse(menu.getSlot(index).getItem(), remaining, true));
        for (ItemStack extra : leftovers) {
            for (int pass = 0; pass < 2 && !extra.isEmpty(); pass++) {
                for (int i = 0; i < contents.size() && !extra.isEmpty(); i++) {
                    Slot slot = inventory.get(i);
                    ItemStack present = contents.get(i);
                    if (!slot.mayPlace(extra) || (pass == 0 ? present.isEmpty() : !present.isEmpty())) continue;
                    if (!present.isEmpty() && !StackKey.of(present).equals(StackKey.of(extra))) continue;
                    int room = Math.min(extra.getMaxStackSize(), slot.getMaxStackSize(extra)) - present.getCount();
                    int moved = Math.min(Math.max(0, room), extra.getCount());
                    if (moved > 0) {
                        contents.set(i, extra.copyWithCount(present.getCount() + moved));
                        extra.shrink(moved);
                    }
                }
            }
            if (!extra.isEmpty()) return false;
        }
        return true;
    }

    private static ItemStack afterUse(ItemStack original, Map<StackKey, Integer> remaining, boolean allowed) {
        ItemStack copy = original.copy();
        if (!copy.isEmpty() && allowed) {
            StackKey key = StackKey.of(copy);
            int amount = Math.min(copy.getCount(), remaining.getOrDefault(key, 0));
            copy.shrink(amount);
            remaining.computeIfPresent(key, (ignored, count) -> count - amount);
        }
        return copy;
    }

    private static void refundSafely(ServerPlayer player, Holding holding, boolean originalSource) {
        try { refund(player, holding, originalSource); }
        catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Refund failed; continuing other known withdrawals", error);
        }
    }

    private static void refund(ServerPlayer player, Holding holding, boolean originalSource) {
        if (holding.stack.isEmpty()) return;
        ItemStack remainder = holding.stack;
        if (originalSource) {
            try {
                remainder = holding.source.insert(remainder);
            } catch (RuntimeException | LinkageError error) {
                StorageFill.LOGGER.error("Unable to return withdrawal to its source", error);
            }
        }
        if (!remainder.isEmpty()) {
            player.getInventory().add(remainder);
            if (!remainder.isEmpty()) player.drop(remainder, false);
        }
        holding.stack = ItemStack.EMPTY;
    }

    private static final class Failure {
        private TransferResult result = failed("storage_fill.failed.extract");

        boolean fail(String key) {
            result = failed(key);
            return false;
        }

        boolean missing(List<ItemStack> stacks, boolean alternatives) {
            result = TransferService.missing(stacks, alternatives);
            return false;
        }

        boolean allocationMissing(List<AllocationPlanner.Need<StackKey>> needs, Map<StackKey, Integer> stock) {
            Map<StackKey, Integer> remaining = new LinkedHashMap<>(stock);
            Map<StackKey, Integer> required = new LinkedHashMap<>();
            boolean alternatives = false;
            for (AllocationPlanner.Need<StackKey> need : needs) {
                alternatives |= need.alternatives().size() > 1;
                StackKey selected = null;
                int deficit = Integer.MAX_VALUE;
                for (StackKey key : need.alternatives()) {
                    if (need.capacity(key) < need.count()) continue;
                    int missing = Math.max(0, need.count() - remaining.getOrDefault(key, 0));
                    if (missing < deficit) {
                        selected = key;
                        deficit = missing;
                    }
                }
                if (selected == null) return fail("storage_fill.failed.allocate");
                required.merge(selected, need.count(), Integer::sum);
                remaining.computeIfPresent(selected, (ignored, count) -> Math.max(0, count - need.count()));
            }
            List<ItemStack> stacks = new ArrayList<>();
            for (var entry : required.entrySet()) {
                int count = entry.getValue() - stock.getOrDefault(entry.getKey(), 0);
                if (count > 0) stacks.add(entry.getKey().stack(count));
            }
            return stacks.isEmpty() ? fail("storage_fill.failed.allocate") : missing(stacks, alternatives);
        }
    }

    private static final class Holding {
        final StorageSource source;
        final StackKey key;
        ItemStack stack;
        Holding(StorageSource source, ItemStack stack) {
            this.source = source;
            this.stack = stack;
            this.key = StackKey.of(stack);
        }
    }

    private record Placement(Slot slot, Holding origin, int count) { }

    private record TopUpReservation(StorageSource source, StackKey key, ItemStack prototype, int count) { }

    private record TopUpSlotUpdate(int slot, ItemStack stack) { }

    private record SlotSource(Slot slot, ServerPlayer player) implements StorageSource {
        public List<ItemStack> snapshot(Set<Item> wanted) {
            ItemStack stack = slot.getItem();
            return !stack.isEmpty() && wanted.contains(stack.getItem()) && slot.mayPickup(player) ? List.of(stack.copy()) : List.of();
        }
        public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !StackKey.of(stack).equals(StackKey.of(prototype)) || !slot.mayPickup(player)) return ItemStack.EMPTY;
            int count = Math.min(amount, stack.getCount());
            return simulate ? stack.copyWithCount(count) : slot.safeTake(count, count, player);
        }
        public ItemStack insert(ItemStack stack) {
            SlotMutation.Insert result = SlotMutation.insert(slot, stack, stack.getCount());
            return stack.copyWithCount(stack.getCount() - result.inserted() - result.uncertain());
        }
        public void finish(int extracted) { }
    }
}

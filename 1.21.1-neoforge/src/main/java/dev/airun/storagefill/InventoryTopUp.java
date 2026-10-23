package dev.airun.storagefill;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InventoryTopUp {
    private InventoryTopUp() { }

    public static Optional<Plan> plan(List<ItemStack> inventory, List<ItemStack> required) {
        if (inventory == null || required == null || inventory.size() != 36) return Optional.empty();
        List<ItemStack> contents = copy(inventory);
        Map<StackKey, Integer> needs = new LinkedHashMap<>();
        Map<StackKey, ItemStack> prototypes = new LinkedHashMap<>();
        long total = 0;
        for (ItemStack stack : required) {
            if (stack == null || stack.isEmpty() || stack.getCount() <= 0) return Optional.empty();
            total += stack.getCount();
            if (total > 5184) return Optional.empty();
            StackKey key = StackKey.of(stack);
            prototypes.putIfAbsent(key, stack.copyWithCount(1));
            needs.merge(key, stack.getCount(), Integer::sum);
            if (needs.size() > 81) return Optional.empty();
        }
        for (ItemStack stack : contents) {
            if (!stack.isEmpty()) {
                StackKey key = StackKey.of(stack);
                needs.computeIfPresent(key, (ignored, count) -> Math.max(0, count - stack.getCount()));
            }
        }
        List<ItemStack> missing = new ArrayList<>();
        for (Map.Entry<StackKey, Integer> need : needs.entrySet()) {
            int remaining = need.getValue();
            if (remaining == 0) continue;
            missing.add(prototypes.get(need.getKey()).copyWithCount(remaining));
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int slot = 0; slot < contents.size() && remaining > 0; slot++) {
                    ItemStack present = contents.get(slot);
                    if (pass == 0 ? present.isEmpty() : !present.isEmpty()) continue;
                    if (!present.isEmpty() && !need.getKey().equals(StackKey.of(present))) continue;
                    ItemStack prototype = prototypes.get(need.getKey());
                    int current = present.isEmpty() ? 0 : present.getCount();
                    int room = Math.min(64, prototype.getMaxStackSize()) - current;
                    int moved = Math.min(Math.max(0, room), remaining);
                    if (moved > 0) {
                        contents.set(slot, prototype.copyWithCount(current + moved));
                        remaining -= moved;
                    }
                }
            }
            if (remaining > 0) return Optional.empty();
        }
        return Optional.of(new Plan(contents, missing));
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) result.add(stack == null ? ItemStack.EMPTY : stack.copy());
        return result;
    }

    public record Plan(List<ItemStack> contents, List<ItemStack> missing) {
        public Plan {
            contents = List.copyOf(copy(contents));
            missing = List.copyOf(copy(missing));
        }
    }
}

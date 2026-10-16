package dev.airun.storagefill.storage;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.Set;

public interface StorageSource {
    int BEYOND_DIMENSIONS = 1;
    int REFINED_STORAGE = 2;
    int APPLIED_ENERGISTICS = 3;
    int SOPHISTICATED_BACKPACKS = 4;
    default int sourceId() { return 0; }
    default Object identity() { return this; }
    default Object changeIdentity() { return identity(); }
    default AutoCloseable watch(java.util.function.Consumer<Item> changed) { return null; }
    List<ItemStack> snapshot(Set<Item> wanted);
    default List<ItemStack> preview(Set<Item> wanted) { return snapshot(wanted); }
    @FunctionalInterface
    interface CountQuery {
        long count(long limit);
    }
    default CountQuery prepareCount(ItemStack prototype, java.util.function.Predicate<ItemStack> matches) {
        Item item = prototype.getItem();
        return limit -> countMatching(item, matches, limit);
    }
    default CountQuery prepareStrictCount(ItemStack prototype, java.util.function.Predicate<ItemStack> matches) {
        return prepareCount(prototype, matches);
    }
    default long countMatching(Item item, java.util.function.Predicate<ItemStack> matches, long limit) {
        if (item == null || limit <= 0) return 0;
        long count = 0;
        for (ItemStack stack : preview(Set.of(item))) {
            if (!stack.isEmpty() && stack.is(item) && matches.test(stack.copyWithCount(1))) {
                count += Math.min(limit - count, stack.getCount());
                if (count >= limit) break;
            }
        }
        return count;
    }
    default boolean visitPreview(java.util.function.Predicate<ItemStack> visitor) {
        for (ItemStack stack : preview(Set.copyOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValues()))) {
            if (!stack.isEmpty() && !visitor.test(stack)) return false;
        }
        return true;
    }
    default boolean visitPreview(Set<Item> wanted, java.util.function.Predicate<ItemStack> visitor) {
        if (wanted.isEmpty()) return true;
        for (ItemStack stack : preview(wanted)) {
            if (!stack.isEmpty() && wanted.contains(stack.getItem()) && !visitor.test(stack)) return false;
        }
        return true;
    }
    ItemStack extract(ItemStack prototype, int amount, boolean simulate);
    ItemStack insert(ItemStack stack);
    default ItemStack insertReward(ItemStack stack) { return insert(stack); }
    void finish(int extracted);
}

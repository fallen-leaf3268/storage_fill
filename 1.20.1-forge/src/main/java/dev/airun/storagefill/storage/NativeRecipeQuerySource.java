package dev.airun.storagefill.storage;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.Set;

interface NativeRecipeQuerySource {
    Object queryKey(ItemStack prototype);
    Scan scanQuery(Set<Item> wanted, Set<Object> seen, int position, int scanLimit);
    default long queryRevision() { return Long.MIN_VALUE; }
    default boolean queryUsesPosition() { return false; }
    record Scan(ItemStack candidate, int position, int scanned, boolean exhausted) { }
}

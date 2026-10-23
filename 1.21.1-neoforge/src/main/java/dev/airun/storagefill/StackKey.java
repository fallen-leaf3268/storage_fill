package dev.airun.storagefill;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public record StackKey(Item item, DataComponentPatch data) {
    public static StackKey of(ItemStack stack) {
        return new StackKey(stack.getItem(), stack.getComponentsPatch());
    }

    public ItemStack stack(int count) {
        return new ItemStack(item.builtInRegistryHolder(), count, data);
    }

    public int encodedSize(HolderLookup.Provider registries) {
        return stack(1).save(registries).sizeInBytes();
    }
}

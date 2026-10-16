package dev.airun.storagefill;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public record StackKey(Item item, CompoundTag data) {
    public StackKey {
        data = data.copy();
    }

    public static StackKey of(ItemStack stack) {
        return new StackKey(stack.getItem(), stack.copyWithCount(1).save(new CompoundTag()));
    }

    public ItemStack stack(int count) {
        ItemStack result = ItemStack.of(data.copy());
        result.setCount(count);
        return result;
    }
}

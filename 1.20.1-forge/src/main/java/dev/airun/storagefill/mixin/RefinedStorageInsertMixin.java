package dev.airun.storagefill.mixin;

import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.util.Action;
import dev.airun.storagefill.storage.StorageChanges;
import dev.airun.storagefill.storage.StorageSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.refinedmods.refinedstorage.apiimpl.network.Network", remap = false)
public abstract class RefinedStorageInsertMixin {
    @Redirect(method = "insertItem(Lnet/minecraft/world/item/ItemStack;ILcom/refinedmods/refinedstorage/api/util/Action;)Lnet/minecraft/world/item/ItemStack;",
        at = @At(value = "INVOKE", target = "Lcom/refinedmods/refinedstorage/api/storage/IStorage;insert(Ljava/lang/Object;ILcom/refinedmods/refinedstorage/api/util/Action;)Ljava/lang/Object;"))
    private Object storage_fill$insert(IStorage<ItemStack> storage, Object input, int amount, Action action) {
        ItemStack stack = (ItemStack) input;
        var item = stack.getItem();
        ItemStack remainder = storage.insert(stack, amount, action);
        if (action == Action.PERFORM && amount > remainder.getCount()) {
            StorageChanges.record(StorageSource.REFINED_STORAGE, this, item);
        }
        return remainder;
    }
}

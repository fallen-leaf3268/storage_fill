package dev.airun.storagefill.mixin;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.InvalidateCause;
import dev.airun.storagefill.storage.StorageChanges;
import dev.airun.storagefill.storage.StorageSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.refinedmods.refinedstorage.apiimpl.storage.cache.ItemStorageCache", remap = false)
public abstract class RefinedStorageCacheMixin {
    @Shadow @Final private INetwork network;

    @Inject(method = "add(Lnet/minecraft/world/item/ItemStack;IZZ)V", at = @At("RETURN"))
    private void storage_fill$added(ItemStack stack, int amount, boolean rebuilding, boolean batched,
                                    CallbackInfo callback) {
        if (!rebuilding && amount > 0 && !stack.isEmpty()) {
            StorageChanges.record(StorageSource.REFINED_STORAGE, network, stack.getItem());
        }
    }

    @Inject(method = "invalidate", at = @At("RETURN"))
    private void storage_fill$rebuilt(InvalidateCause cause, CallbackInfo callback) {
        StorageChanges.record(StorageSource.REFINED_STORAGE, network, null);
    }
}

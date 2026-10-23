package dev.airun.storagefill.mixin;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import dev.airun.storagefill.storage.StorageChanges;
import dev.airun.storagefill.storage.StorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl", remap = false)
public abstract class RefinedStorageInsertMixin {
    @Inject(method = "insert", at = @At("RETURN"))
    private void storage_fill$insert(ResourceKey resource, long amount, Action action, Actor actor,
                                     CallbackInfoReturnable<Long> callback) {
        if ((Object) this instanceof StorageNetworkComponent && action == Action.EXECUTE
            && callback.getReturnValue() > 0 && resource instanceof ItemResource item)
            StorageChanges.record(StorageSource.REFINED_STORAGE, this, item.item());
    }
}

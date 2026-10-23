package dev.airun.storagefill.mixin;

import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageListener;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import dev.airun.storagefill.storage.StorageChanges;
import dev.airun.storagefill.storage.StorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Pseudo
@Mixin(targets = "com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl", remap = false)
public abstract class RefinedStorageCacheMixin {
    @Inject(method = "<init>(Lcom/refinedmods/refinedstorage/api/resource/list/MutableResourceList;Ljava/util/Set;)V", at = @At("RETURN"))
    private void storage_fill$listen(MutableResourceList list, Set<RootStorageListener> listeners, CallbackInfo callback) {
        if (!((Object) this instanceof StorageNetworkComponent storage)) return;
        storage.addListener(change -> {
            if (change.change() > 0 && change.resource() instanceof ItemResource item)
                StorageChanges.record(StorageSource.REFINED_STORAGE, this, item.item());
        });
    }

    @Inject(method = {"addSource", "removeSource"}, at = @At("RETURN"))
    private void storage_fill$topology(Storage source, CallbackInfo callback) {
        if ((Object) this instanceof StorageNetworkComponent)
            StorageChanges.record(StorageSource.REFINED_STORAGE, this, null);
    }
}

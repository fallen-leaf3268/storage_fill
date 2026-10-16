package dev.airun.storagefill.mixin;

import dev.airun.storagefill.storage.StorageChangeOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Supplier;

@Pseudo
@Mixin(targets = "appeng.me.storage.CompositeStorage", remap = false)
public abstract class AppliedEnergisticsCompositeMixin implements StorageChangeOwner {
    @Unique private Supplier<Object> storage_fill$changeOwner;

    @Override
    public void storageFillSetChangeOwner(Supplier<Object> owner) {
        storage_fill$changeOwner = owner;
    }

    @Override
    public Object storageFillChangeIdentity() {
        return storage_fill$changeOwner == null ? null : storage_fill$changeOwner.get();
    }
}

package dev.airun.storagefill.mixin;

import appeng.parts.storagebus.StorageBusPart;
import dev.airun.storagefill.storage.StorageChangeOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "appeng.parts.storagebus.StorageBusPart", remap = false)
public abstract class AppliedEnergisticsStorageBusMixin {
    @Inject(method = "updateTarget", at = @At("RETURN"))
    private void storage_fill$updateOwner(boolean forceFullUpdate, CallbackInfo callback) {
        StorageBusPart part = (StorageBusPart) (Object) this;
        if (part.getInternalHandler() instanceof StorageChangeOwner owner) {
            owner.storageFillSetChangeOwner(() -> {
                var node = part.getActionableNode();
                return node != null && node.isActive() ? node.getGrid().getStorageService().getInventory() : null;
            });
        }
    }
}

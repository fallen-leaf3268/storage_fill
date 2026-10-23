package dev.airun.storagefill.mixin;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.storage.CompositeStorage;
import dev.airun.storagefill.storage.StorageChangeOwner;
import dev.airun.storagefill.storage.StorageChanges;
import dev.airun.storagefill.storage.StorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "appeng.me.storage.CompositeStorage$InventoryCache", remap = false)
public abstract class AppliedEnergisticsCompositeCacheMixin {
    @Shadow private KeyCounter frontBuffer;
    @Shadow @Final CompositeStorage this$0;

    @Redirect(method = "update", at = @At(value = "INVOKE",
        target = "Lappeng/api/stacks/KeyCounter;get(Lappeng/api/stacks/AEKey;)J", ordinal = 0))
    private long storage_fill$compare(KeyCounter previous, AEKey key) {
        long amount = previous.get(key);
        if (key instanceof AEItemKey item && frontBuffer.get(key) > amount) {
            StorageChanges.record(StorageSource.APPLIED_ENERGISTICS,
                ((StorageChangeOwner) this$0).storageFillChangeIdentity(), item.getItem());
        }
        return amount;
    }
}

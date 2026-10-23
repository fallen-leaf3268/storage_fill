package dev.airun.storagefill.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import dev.airun.storagefill.storage.StorageChanges;
import dev.airun.storagefill.storage.StorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "appeng.me.storage.NetworkStorage", remap = false)
public abstract class AppliedEnergisticsInsertMixin {
    @Shadow private boolean mountsInUse;

    @Inject(method = "insert", at = @At("RETURN"))
    private void storage_fill$insert(AEKey key, long amount, Actionable action, IActionSource source,
                                     CallbackInfoReturnable<Long> callback) {
        if (action == Actionable.MODULATE && callback.getReturnValue() > 0 && key instanceof AEItemKey item) {
            StorageChanges.record(StorageSource.APPLIED_ENERGISTICS, this, item.getItem());
        }
    }

    @Inject(method = "mount", at = @At("RETURN"))
    private void storage_fill$mounted(int priority, MEStorage storage, CallbackInfo callback) {
        if (!mountsInUse) StorageChanges.record(StorageSource.APPLIED_ENERGISTICS, this, null);
    }
}

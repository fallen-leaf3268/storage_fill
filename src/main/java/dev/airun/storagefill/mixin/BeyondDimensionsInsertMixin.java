package dev.airun.storagefill.mixin;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.helper.UnifiedStorageBeforeInsertHandler;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import dev.airun.storagefill.storage.StorageChanges;
import dev.airun.storagefill.storage.StorageRevision;
import dev.airun.storagefill.storage.StorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage", remap = false)
public abstract class BeyondDimensionsInsertMixin implements StorageRevision {
    @Shadow @Final private DimensionsNet net;
    @Unique private long storage_fill$revision;

    @Override
    public long storageFillRevision() { return storage_fill$revision; }

    @Inject(method = "onChange", at = @At("HEAD"))
    private void storage_fill$changed(CallbackInfo callback) {
        storage_fill$revision++;
    }

    @Inject(method = "insert(Lcom/wintercogs/beyonddimensions/api/storage/key/IStackKey;JZ)Lcom/wintercogs/beyonddimensions/api/storage/key/KeyAmount;",
        at = @At(value = "RETURN", ordinal = 2), locals = LocalCapture.CAPTURE_FAILHARD)
    private void storage_fill$insert(IStackKey<?> key, long amount, boolean simulate,
                                     CallbackInfoReturnable<KeyAmount> callback, KeyAmount original,
                                     UnifiedStorageBeforeInsertHandler.BeforeInsertHandlerReturnInfo info,
                                     KeyAmount adjusted) {
        if (!simulate && net != null && adjusted.key() instanceof ItemStackKey item
            && adjusted.amount() > callback.getReturnValue().amount()) {
            StorageChanges.record(StorageSource.BEYOND_DIMENSIONS, net, item.getSource());
        }
    }
}

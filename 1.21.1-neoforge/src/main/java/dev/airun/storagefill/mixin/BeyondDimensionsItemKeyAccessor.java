package dev.airun.storagefill.mixin;

import net.minecraft.core.component.DataComponentPatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey", remap = false)
public interface BeyondDimensionsItemKeyAccessor {
    @Accessor("patch")
    DataComponentPatch storage_fill$patch();
}

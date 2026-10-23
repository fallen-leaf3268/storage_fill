package dev.airun.storagefill.mixin;

import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey", remap = false)
public interface BeyondDimensionsItemKeyAccessor {
    @Accessor("tag")
    CompoundTag storage_fill$tag();

    @Accessor("caps")
    CompoundTag storage_fill$caps();
}

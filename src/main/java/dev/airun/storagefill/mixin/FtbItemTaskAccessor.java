package dev.airun.storagefill.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.quest.task.ItemTask", remap = false)
public interface FtbItemTaskAccessor {
    @Invoker("shouldMatchNBT")
    boolean storage_fill$shouldMatchNBT();

    @Accessor("weakNBTmatch")
    boolean storage_fill$weakNBTmatch();
}

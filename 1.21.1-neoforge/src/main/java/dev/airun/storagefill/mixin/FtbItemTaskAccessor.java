package dev.airun.storagefill.mixin;

import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem.ComponentMatchType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.quest.task.ItemTask", remap = false)
public interface FtbItemTaskAccessor {
    @Accessor("matchComponents")
    ComponentMatchType storage_fill$matchComponents();
}

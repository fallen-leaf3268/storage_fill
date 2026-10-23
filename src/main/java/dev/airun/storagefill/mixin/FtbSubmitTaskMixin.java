package dev.airun.storagefill.mixin;

import dev.airun.storagefill.compat.ftb.FtbQuestSubmission;
import dev.airun.storagefill.storage.StorageSources;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.net.SubmitTaskMessage", remap = false)
public abstract class FtbSubmitTaskMixin {
    @Redirect(method = "lambda$handle$0", require = 1, at = @At(value = "INVOKE", remap = false,
        target = "Ldev/ftb/mods/ftbquests/quest/task/Task;submitTask(Ldev/ftb/mods/ftbquests/quest/TeamData;Lnet/minecraft/server/level/ServerPlayer;)V"))
    private static void storage_fill$submit(Task submittedTask, TeamData data, ServerPlayer player) {
        if (!(submittedTask instanceof ItemTask task) || !FtbQuestSubmission.supports(task)) {
            submittedTask.submitTask(data, player);
            return;
        }
        var result = FtbQuestSubmission.submit(player, data, task, () -> StorageSources.find(player));
        player.displayClientMessage(Component.translatable(result.failed() ? "storage_fill.ftb.interrupted"
            : result.remaining() > 0 ? "storage_fill.ftb.partial" : "storage_fill.ftb.submitted",
            result.submitted(), result.remaining(), task.getItemStack().getHoverName()), false);
    }
}

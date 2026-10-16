package dev.airun.storagefill.mixin;

import dev.airun.storagefill.compat.ftb.FtbQuestSubmission;
import dev.airun.storagefill.storage.StorageSources;
import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.net.SubmitTaskMessage", remap = false)
public abstract class FtbSubmitTaskMixin {
    @Shadow @Final private long taskId;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void storage_fill$submit(NetworkManager.PacketContext context, CallbackInfo callback) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return;
        TeamData data = TeamData.get(player);
        if (!(data.getFile() instanceof ServerQuestFile file)
            || !(file.getTask(taskId) instanceof ItemTask task) || !FtbQuestSubmission.supports(task)) return;
        callback.cancel();
        var result = FtbQuestSubmission.submit(player, data, task, () -> StorageSources.find(player));
        player.displayClientMessage(Component.translatable(result.failed() ? "storage_fill.ftb.interrupted"
            : result.remaining() > 0 ? "storage_fill.ftb.partial" : "storage_fill.ftb.submitted",
            result.submitted(), result.remaining(), task.getItemStack().getHoverName()), false);
    }
}

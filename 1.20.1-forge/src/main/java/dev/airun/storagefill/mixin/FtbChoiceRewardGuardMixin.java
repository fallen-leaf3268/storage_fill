package dev.airun.storagefill.mixin;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.net.ClaimChoiceRewardMessage", remap = false)
public abstract class FtbChoiceRewardGuardMixin {
    @Shadow @Final private long id;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void storage_fill$checkChoiceLedger(NetworkManager.PacketContext context, CallbackInfo callback) {
        if (!(context.getPlayer() instanceof ServerPlayer player) || ServerQuestFile.INSTANCE == null) return;
        if (!(ServerQuestFile.INSTANCE.getReward(id) instanceof ChoiceReward reward)) return;
        TeamData data = TeamData.get(player);
        if (data == null || data.isLocked() || data.isRewardBlocked(reward)
            || data.isRewardClaimed(player.getUUID(), reward)) callback.cancel();
    }

    @Redirect(method = "handle", at = @At(value = "INVOKE", remap = false,
        target = "Ldev/ftb/mods/ftbquests/quest/reward/Reward;claim(Lnet/minecraft/server/level/ServerPlayer;Z)V"))
    private void storage_fill$settleFailedChoice(Reward child, ServerPlayer player, boolean notify) {
        TeamData data = TeamData.get(player);
        Reward parent = ServerQuestFile.INSTANCE.getReward(id);
        try {
            child.claim(player, notify);
        } catch (RuntimeException | LinkageError error) {
            try {
                if (data != null && parent instanceof ChoiceReward) data.claimReward(player, parent, notify);
            } catch (RuntimeException | LinkageError settlementError) {
                error.addSuppressed(settlementError);
            }
            throw error;
        }
    }
}

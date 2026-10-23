package dev.airun.storagefill.mixin;

import dev.airun.storagefill.compat.ftb.FtbDetectionQueue;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = {
    "dev.ftb.mods.ftbquests.net.ClaimRewardMessage",
    "dev.ftb.mods.ftbquests.net.ClaimChoiceRewardMessage"
}, remap = false)
public abstract class FtbManualRewardDetectionMixin {
    @Redirect(method = "handle", require = 1, at = @At(value = "INVOKE", remap = false,
        target = "Ldev/ftb/mods/ftbquests/quest/TeamData;claimReward(Lnet/minecraft/server/level/ServerPlayer;Ldev/ftb/mods/ftbquests/quest/reward/Reward;Z)V"))
    private void storage_fill$detectAfterManualClaim(TeamData data, ServerPlayer player, Reward reward, boolean notify) {
        Quest quest = reward.getQuest();
        int completedBefore = data.getCompletionCount(quest);
        data.claimReward(player, reward, notify);
        FtbDetectionQueue.afterManualRewardClaim(player, data, quest, completedBefore);
    }
}

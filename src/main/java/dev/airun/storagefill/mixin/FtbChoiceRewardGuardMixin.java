package dev.airun.storagefill.mixin;

import dev.airun.storagefill.compat.ftb.FtbDetectionQueue;
import dev.ftb.mods.ftbquests.net.ClaimChoiceRewardMessage;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.net.ClaimChoiceRewardMessage", remap = false)
public abstract class FtbChoiceRewardGuardMixin {
    @Redirect(method = "lambda$handle$0", require = 1, at = @At(value = "INVOKE", remap = false,
        target = "Ldev/ftb/mods/ftbquests/quest/TeamData;markRewardAsClaimed(Ljava/util/UUID;Ldev/ftb/mods/ftbquests/quest/reward/Reward;J)Z"))
    private static boolean storage_fill$detectAfterChoiceLedger(TeamData data, UUID playerId, Reward reward, long when,
                                                               ChoiceReward choice, Reward parent,
                                                               ClaimChoiceRewardMessage message, ServerPlayer player,
                                                               TeamData contextData) {
        Quest quest = reward.getQuest();
        int completedBefore = data.getCompletionCount(quest);
        boolean claimed = data.markRewardAsClaimed(playerId, reward, when);
        if (claimed) FtbDetectionQueue.afterManualRewardClaim(player, data, quest, completedBefore);
        return claimed;
    }
}

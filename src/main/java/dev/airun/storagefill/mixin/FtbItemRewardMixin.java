package dev.airun.storagefill.mixin;

import dev.airun.storagefill.compat.ftb.FtbRewardRouting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.quest.reward.ItemReward", remap = false)
public abstract class FtbItemRewardMixin {
    @Redirect(method = "claim", at = @At(value = "INVOKE", remap = false,
        target = "Ldev/architectury/hooks/item/ItemStackHooks;giveItem(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V"))
    private void storage_fill$routeItemReward(ServerPlayer player, ItemStack stack) {
        FtbRewardRouting.give(player, stack);
    }
}

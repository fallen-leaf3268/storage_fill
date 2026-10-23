package dev.airun.storagefill.mixin;

import dev.airun.storagefill.client.ftb.RewardDestinationButton;
import dev.ftb.mods.ftblibrary.ui.Panel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.OtherButtonsPanelBottom", remap = false)
public abstract class FtbRewardDestinationButtonMixin {
    @Inject(method = "addWidgets", at = @At("HEAD"))
    private void storage_fill$addRewardDestination(CallbackInfo callback) {
        Panel panel = (Panel) (Object) this;
        panel.add(new RewardDestinationButton(panel));
    }
}

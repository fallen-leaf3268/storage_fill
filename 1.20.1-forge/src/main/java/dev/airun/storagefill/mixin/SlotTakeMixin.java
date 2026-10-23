package dev.airun.storagefill.mixin;

import dev.airun.storagefill.SlotMutation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

@Mixin(Slot.class)
public abstract class SlotTakeMixin {
    @Redirect(method = "safeTake", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/inventory/Slot;tryRemove(IILnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;"))
    private Optional<ItemStack> storageFill$observeRemoval(Slot slot, int amount, int maximum, Player player) {
        Optional<ItemStack> removed = slot.tryRemove(amount, maximum, player);
        SlotMutation.observeRemoval(slot, removed.orElse(ItemStack.EMPTY));
        return removed;
    }
}

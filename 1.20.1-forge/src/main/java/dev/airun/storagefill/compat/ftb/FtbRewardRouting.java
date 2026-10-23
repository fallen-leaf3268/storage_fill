package dev.airun.storagefill.compat.ftb;

import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.storage.StorageSources;
import dev.architectury.hooks.item.ItemStackHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class FtbRewardRouting {
    private FtbRewardRouting() { }

    public static void give(ServerPlayer player, ItemStack stack) {
        RewardDestination destination = RewardPreferences.get(player);
        if (destination == RewardDestination.INVENTORY || stack.isEmpty()) {
            ItemStackHooks.giveItem(player, stack);
            return;
        }
        ItemStack remaining = stack.copy();
        StackKey expected = StackKey.of(stack);
        for (var source : StorageSources.findForInsert(player, destination.id())) {
            if (remaining.isEmpty()) break;
            int requested = remaining.getCount();
            ItemStack returned;
            try {
                returned = source.insertReward(remaining.copy());
                if (returned == null || !returned.isEmpty()
                    && (returned.getCount() > requested || !expected.equals(StackKey.of(returned))))
                    throw new IllegalStateException("Reward storage returned an invalid remainder");
            } catch (RuntimeException | LinkageError error) {
                StorageFill.LOGGER.error("FTB item reward insertion into {} failed; unconfirmed items are not issued again",
                    destination, error);
                throw error;
            }
            remaining = returned;
        }
        if (!remaining.isEmpty()) ItemStackHooks.giveItem(player, remaining);
    }
}

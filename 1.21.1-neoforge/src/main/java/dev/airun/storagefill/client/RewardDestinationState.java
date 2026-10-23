package dev.airun.storagefill.client;

import dev.airun.storagefill.FillNetwork;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.compat.ftb.RewardDestination;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

@net.neoforged.fml.common.EventBusSubscriber(modid = StorageFill.ID, value = Dist.CLIENT)
public final class RewardDestinationState {
    private static RewardDestination current = RewardDestination.INVENTORY;

    private RewardDestinationState() { }

    public static RewardDestination current() { return current; }

    public static void select(RewardDestination destination) {
        if (!destination.available() || destination == current) return;
        current = destination;
        FillNetwork.sendToServer(new FillNetwork.RewardDestinationSelect(destination.id()));
    }

    public static void accept(RewardDestination destination) {
        current = destination.available() ? destination : RewardDestination.INVENTORY;
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        current = RewardDestination.INVENTORY;
    }
}

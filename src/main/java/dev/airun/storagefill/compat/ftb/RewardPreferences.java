package dev.airun.storagefill.compat.ftb;

import dev.airun.storagefill.FillNetwork;
import dev.airun.storagefill.StorageFill;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

@Mod.EventBusSubscriber(modid = StorageFill.ID)
public final class RewardPreferences {
    private static final String KEY = "storage_fill_reward_destination";

    private RewardPreferences() { }

    public static RewardDestination get(ServerPlayer player) {
        var saved = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        RewardDestination destination = RewardDestination.fromId(saved.getByte(KEY));
        return destination.available() ? destination : RewardDestination.INVENTORY;
    }

    public static void set(ServerPlayer player, RewardDestination destination) {
        var root = player.getPersistentData();
        var saved = root.getCompound(Player.PERSISTED_NBT_TAG);
        saved.putByte(KEY, (byte) (destination.available() ? destination.id() : 0));
        root.put(Player.PERSISTED_NBT_TAG, saved);
    }

    public static void sync(ServerPlayer player) {
        FillNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
            new FillNetwork.RewardDestinationSync(get(player).id()));
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (ModList.get().isLoaded("ftbquests") && event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (ModList.get().isLoaded("ftbquests") && event.getEntity() instanceof ServerPlayer player) sync(player);
    }
}

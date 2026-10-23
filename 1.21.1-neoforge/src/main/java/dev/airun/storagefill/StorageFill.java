package dev.airun.storagefill;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(StorageFill.ID)
@net.neoforged.fml.common.EventBusSubscriber(modid = StorageFill.ID)
public final class StorageFill {
    public static final String ID = "storage_fill";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StorageFill(net.neoforged.bus.api.IEventBus modBus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(
                net.neoforged.fml.config.ModConfig.Type.CLIENT, FillPriorityConfig.SPEC, ID + "-client.toml");
        modBus.addListener(FillNetwork::register);
        if (net.neoforged.fml.ModList.get().isLoaded("ftbquests")) {
            dev.airun.storagefill.compat.ftb.FtbDetectionQueue.register();
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) FillNetwork.remove(player);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        FillNetwork.clear();
    }
}

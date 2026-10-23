package dev.airun.storagefill;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(StorageFill.ID)
@Mod.EventBusSubscriber(modid = StorageFill.ID)
public final class StorageFill {
    public static final String ID = "storage_fill";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StorageFill() {
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.CLIENT, FillPriorityConfig.SPEC, ID + "-client.toml");
        FillNetwork.register();
        if (net.minecraftforge.fml.ModList.get().isLoaded("ftbquests")) {
            dev.airun.storagefill.compat.ftb.FtbDetectionQueue.register();
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void logout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) FillNetwork.remove(player);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void stopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        FillNetwork.clear();
    }
}

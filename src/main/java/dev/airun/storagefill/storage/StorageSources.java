package dev.airun.storagefill.storage;

import dev.airun.storagefill.StorageFill;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class StorageSources {
    public static final int MAX_COUNT = 5184;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private StorageSources() {
    }

    public static List<StorageSource> find(ServerPlayer player) {
        List<StorageSource> sources = new ArrayList<>();
        if (ModList.get().isLoaded("beyonddimensions")) {
            collect("beyonddimensions", sources, found -> BeyondDimensionsSource.find(player, found));
        }
        if (ModList.get().isLoaded("refinedstorage")) {
            collect("refinedstorage", sources, found -> RefinedStorageSource.find(player, found));
        }
        if (ModList.get().isLoaded("ae2")) {
            collect("ae2", sources, found -> AppliedEnergisticsSource.find(player, found));
        }
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            collect("sophisticatedbackpacks", sources, found -> SophisticatedBackpacksSource.find(player, found));
        }
        return sources;
    }

    public static List<StorageSource> findForInsert(ServerPlayer player, int sourceId) {
        List<StorageSource> sources = new ArrayList<>();
        switch (sourceId) {
            case StorageSource.BEYOND_DIMENSIONS -> {
                if (ModList.get().isLoaded("beyonddimensions"))
                    collect("beyonddimensions", sources, found -> BeyondDimensionsSource.find(player, found));
            }
            case StorageSource.REFINED_STORAGE -> {
                if (ModList.get().isLoaded("refinedstorage"))
                    collect("refinedstorage", sources, found -> RefinedStorageSource.findForInsert(player, found));
            }
            case StorageSource.APPLIED_ENERGISTICS -> {
                if (ModList.get().isLoaded("ae2"))
                    collect("ae2", sources, found -> AppliedEnergisticsSource.find(player, found));
            }
            case StorageSource.SOPHISTICATED_BACKPACKS -> {
                if (ModList.get().isLoaded("sophisticatedbackpacks"))
                    collect("sophisticatedbackpacks", sources, found -> SophisticatedBackpacksSource.find(player, found));
            }
        }
        return sources;
    }

    static void collect(String modId, List<StorageSource> sources, Consumer<List<StorageSource>> finder) {
        List<StorageSource> found = new ArrayList<>();
        try {
            finder.accept(found);
            sources.addAll(found);
        } catch (RuntimeException | LinkageError error) {
            if (WARNED.add(modId)) StorageFill.LOGGER.error("Unable to discover {} storage sources; preserving other adapters", modId, error);
        }
    }
}

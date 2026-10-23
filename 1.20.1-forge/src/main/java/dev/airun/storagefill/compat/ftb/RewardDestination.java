package dev.airun.storagefill.compat.ftb;

import dev.airun.storagefill.storage.StorageSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

public enum RewardDestination {
    INVENTORY(0, "inventory", null, "minecraft:crafting_table"),
    SOPHISTICATED_BACKPACKS(StorageSource.SOPHISTICATED_BACKPACKS, "sophisticatedbackpacks", "sophisticatedbackpacks", "sophisticatedbackpacks:backpack"),
    REFINED_STORAGE(StorageSource.REFINED_STORAGE, "refinedstorage", "refinedstorage", "refinedstorage:creative_controller"),
    APPLIED_ENERGISTICS(StorageSource.APPLIED_ENERGISTICS, "ae2", "ae2", "ae2:controller"),
    BEYOND_DIMENSIONS(StorageSource.BEYOND_DIMENSIONS, "beyonddimensions", "beyonddimensions", "beyonddimensions:net_creater");

    private final int id;
    private final String key;
    private final String modId;
    private final ResourceLocation iconId;

    RewardDestination(int id, String key, String modId, String iconId) {
        this.id = id;
        this.key = key;
        this.modId = modId;
        this.iconId = new ResourceLocation(iconId);
    }

    public int id() { return id; }

    public boolean available() { return modId == null || ModList.get().isLoaded(modId); }

    public String translationKey() { return "storage_fill.ftb.reward_destination." + key; }

    public ResourceLocation iconId() { return iconId; }

    public static RewardDestination fromId(int id) {
        return switch (id) {
            case StorageSource.BEYOND_DIMENSIONS -> BEYOND_DIMENSIONS;
            case StorageSource.REFINED_STORAGE -> REFINED_STORAGE;
            case StorageSource.APPLIED_ENERGISTICS -> APPLIED_ENERGISTICS;
            case StorageSource.SOPHISTICATED_BACKPACKS -> SOPHISTICATED_BACKPACKS;
            default -> INVENTORY;
        };
    }
}

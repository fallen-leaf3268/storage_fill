package dev.airun.storagefill.mixin;

import dev.airun.storagefill.JeiCompatibility;
import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class StorageFillMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) { }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return switch (mixinClassName) {
            case "dev.airun.storagefill.mixin.BasicTransferMixin",
                 "dev.airun.storagefill.mixin.CustomTransferMixin",
                 "dev.airun.storagefill.mixin.ModernRecipesGuiAccessor" -> JeiCompatibility.CONTEXT_TRANSFER;
            case "dev.airun.storagefill.mixin.LegacyBasicTransferMixin",
                 "dev.airun.storagefill.mixin.LegacyCustomTransferMixin" -> !JeiCompatibility.CONTEXT_TRANSFER;
            case "dev.airun.storagefill.mixin.RecipeQueryButtonsMixin",
                 "dev.airun.storagefill.mixin.RecipeQueryIconButtonAccessor",
                 "dev.airun.storagefill.mixin.RecipeQuerySideLayoutMixin" -> JeiCompatibility.CONTROLLER_BUTTONS;
            case "dev.airun.storagefill.mixin.LegacyRecipeQueryButtonsMixin",
                 "dev.airun.storagefill.mixin.LegacyRecipeQueryLayoutsMixin" -> !JeiCompatibility.CONTROLLER_BUTTONS;
            case "dev.airun.storagefill.mixin.FtbItemTaskAccessor",
                 "dev.airun.storagefill.mixin.FtbRewardDestinationButtonMixin",
                 "dev.airun.storagefill.mixin.FtbManualRewardDetectionMixin",
                 "dev.airun.storagefill.mixin.FtbManualAllRewardsDetectionMixin",
                 "dev.airun.storagefill.mixin.FtbItemRewardMixin",
                 "dev.airun.storagefill.mixin.FtbChoiceRewardGuardMixin",
                 "dev.airun.storagefill.mixin.BeyondDimensionsInsertMixin" -> loaded("ftbquests");
            case "dev.airun.storagefill.mixin.BeyondDimensionsItemKeyAccessor" -> loaded("ftbquests")
                && loaded("beyonddimensions");
            case "dev.airun.storagefill.mixin.RefinedStorageCacheMixin",
                 "dev.airun.storagefill.mixin.RefinedStorageInsertMixin" -> loaded("ftbquests") && loaded("refinedstorage");
            case "dev.airun.storagefill.mixin.AppliedEnergisticsCompositeMixin",
                 "dev.airun.storagefill.mixin.AppliedEnergisticsCompositeCacheMixin",
                 "dev.airun.storagefill.mixin.AppliedEnergisticsStorageBusMixin",
                 "dev.airun.storagefill.mixin.AppliedEnergisticsInsertMixin" -> loaded("ftbquests") && loaded("ae2");
            case "dev.airun.storagefill.mixin.SophisticatedBackpacksInsertMixin" -> loaded("ftbquests")
                && loaded("sophisticatedbackpacks");
            default -> true;
        };
    }

    private static boolean loaded(String modId) {
        return FMLLoader.getLoadingModList() != null
            && FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}

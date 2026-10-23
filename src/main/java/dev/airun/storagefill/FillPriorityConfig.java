package dev.airun.storagefill;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;

@Mod.EventBusSubscriber(modid = StorageFill.ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FillPriorityConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> SOURCE_ORDER;
    private static volatile FillPriority priority = FillPriority.DEFAULT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("recipeFill");
        SOURCE_ORDER = builder.comment(
                "JEI 填充时外部材料来源的优先顺序，越靠前越先取用。每位玩家独立生效。",
                "玩家背包和配方输入槽始终优先；仅调整以下四类外部来源。",
                "beyonddimensions=超越维度，refinedstorage=精致存储 RS，ae2=应用能源 AE2，sophisticatedbackpacks=精妙背包。",
                "未列出的来源按默认顺序追加；重复项忽略。未安装或不可访问的来源自动跳过。",
                "保存后重新查询配方生效；已查询配方沿用查询时的顺序。",
                "保留满足材料需求即停止的查询策略，不为比较所有替代物品或 NBT 扫描整个仓库。")
                .defineListAllowEmpty(List.of("sourceOrder"), FillPriority.DEFAULT_NAMES, FillPriority::validName);
        builder.pop();
        SPEC = builder.build();
    }

    private FillPriorityConfig() { }

    public static FillPriority current() { return priority; }

    @SubscribeEvent
    public static void configChanged(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            priority = FillPriority.fromNames(SOURCE_ORDER.get());
        }
    }
}

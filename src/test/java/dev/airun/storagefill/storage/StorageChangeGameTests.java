package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StorageFill;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class StorageChangeGameTests {
    @GameTest(template = "empty", batch = "storage_change_bridge")
    public static void repeatedNetworkChangesCoalesceAndDrainOnce(GameTestHelper helper) {
        if (!installed(helper)) return;
        StorageChanges.drain();
        Object identity = new Object();
        StorageChanges.record(StorageSource.BEYOND_DIMENSIONS, identity, Items.IRON_INGOT);
        StorageChanges.record(StorageSource.BEYOND_DIMENSIONS, identity, Items.IRON_INGOT);
        StorageChanges.record(StorageSource.BEYOND_DIMENSIONS, identity, Items.GOLD_INGOT);
        StorageChanges.Batch batch = StorageChanges.drain();
        helper.assertTrue(!batch.allSources() && batch.changes().size() == 1,
            "Repeated deposits to the same network were queued as separate detection jobs");
        StorageChanges.Change change = batch.changes().get(0);
        helper.assertTrue(change.sourceId() == StorageSource.BEYOND_DIMENSIONS && change.identity() == identity
            && !change.allItems() && change.items().equals(Set.of(Items.IRON_INGOT, Items.GOLD_INGOT)),
            "Coalescing duplicated or dropped changed material types");
        helper.assertTrue(!StorageChanges.hasChanges() && StorageChanges.drain().changes().isEmpty(),
            "Draining the storage change bridge retained already dispatched deposits");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "storage_change_bridge")
    public static void unknownMaterialInvalidationRemainsSingleNetworkChange(GameTestHelper helper) {
        if (!installed(helper)) return;
        StorageChanges.drain();
        Object identity = new Object();
        StorageChanges.record(StorageSource.REFINED_STORAGE, identity, Items.IRON_INGOT);
        StorageChanges.record(StorageSource.REFINED_STORAGE, identity, null);
        StorageChanges.record(StorageSource.REFINED_STORAGE, identity, Items.GOLD_INGOT);
        StorageChanges.Batch batch = StorageChanges.drain();
        helper.assertTrue(batch.changes().size() == 1 && batch.changes().get(0).allItems()
            && batch.changes().get(0).items().isEmpty(),
            "Unknown-material invalidation retained partial stale keys or expanded into multiple jobs");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "storage_change_bridge")
    public static void realBeyondDimensionsSimulationAndRejectedInsertionDoNotQueueChanges(GameTestHelper helper) {
        if (!installed(helper)) return;
        if (!ModList.get().isLoaded("beyonddimensions")) {
            StorageFill.LOGGER.warn("SKIPPED real storage insert hook GameTest: Beyond Dimensions is absent");
            helper.succeed();
            return;
        }
        BeyondDimensionsCases.insert(helper);
        helper.succeed();
    }

    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbteams")) return true;
        StorageFill.LOGGER.warn("SKIPPED storage change bridge GameTest: FTB Quests or Teams is absent");
        helper.succeed();
        return false;
    }

    private static final class BeyondDimensionsCases {
        private static void insert(GameTestHelper helper) {
            ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "storage-change"));
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(player, 16L, 1);
            helper.assertTrue(network != null, "Insert hook test failed to create a real capacity-limited network");
            try {
                var storage = network.getUnifiedStorage();
                var iron = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(new ItemStack(Items.IRON_INGOT));
                var gold = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(new ItemStack(Items.GOLD_INGOT));
                StorageChanges.drain();
                helper.assertTrue(storage.insert(iron, 16, true).isEmpty(),
                    "Real network simulation unexpectedly rejected available storage capacity");
                helper.assertTrue(!StorageChanges.hasChanges(),
                    "Storage insert simulation scheduled an automatic FTB detection");
                helper.assertTrue(storage.insert(iron, 16, false).isEmpty(), "Real network insertion failed");
                StorageChanges.Batch accepted = StorageChanges.drain();
                helper.assertTrue(accepted.changes().size() == 1 && accepted.changes().get(0).identity() == network
                    && accepted.changes().get(0).items().equals(Set.of(Items.IRON_INGOT)),
                    "Successful native network insertion did not record the actual changed material");
                helper.assertTrue(!storage.insert(iron, 1, false).isEmpty() && !storage.insert(gold, 1, false).isEmpty(),
                    "Capacity-limited network did not reject overflowing insertions");
                helper.assertTrue(!StorageChanges.hasChanges(),
                    "Failed native network insertion scheduled an automatic FTB detection");
            } finally {
                network.destroySelf();
                StorageChanges.drain();
            }
        }
    }
}

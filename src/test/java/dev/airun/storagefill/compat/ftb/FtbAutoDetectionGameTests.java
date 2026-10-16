package dev.airun.storagefill.compat.ftb;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.storage.StorageSource;
import dev.airun.storagefill.storage.StorageSources;
import dev.ftb.mods.ftblibrary.config.Tristate;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dev.ftb.mods.ftbquests.quest.reward.RewardTypes;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.event.PlayerLoggedInAfterTeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class FtbAutoDetectionGameTests {
    private static final AtomicLong IDS = new AtomicLong(0x6200000000000000L);

    @GameTest(template = "empty")
    public static void currentStoredQuantityCompletesNonConsumingTask(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.currentStoredQuantityCompletesNonConsumingTask(helper);
    }

    @GameTest(template = "empty")
    public static void fourDepositsUseCurrentWarehouseTotal(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.fourDepositsUseCurrentWarehouseTotal(helper);
    }

    @GameTest(template = "empty")
    public static void withdrawingAndRedepositingDoesNotAccumulateProgress(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.withdrawingAndRedepositingDoesNotAccumulateProgress(helper);
    }

    @GameTest(template = "empty")
    public static void taskCountsAboveJeiSnapshotLimit(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.taskCountsAboveJeiSnapshotLimit(helper);
    }

    @GameTest(template = "empty")
    public static void automaticDetectionUsesNativeFtbNbtMatching(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.automaticDetectionUsesNativeFtbNbtMatching(helper);
    }

    @GameTest(template = "empty")
    public static void uncertainStorageViewsAreNotAddedTogether(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.uncertainStorageViewsAreNotAddedTogether(helper);
    }

    @GameTest(template = "empty")
    public static void repeatedTeamDetectionDoesNotAddObserverCounts(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.repeatedTeamDetectionDoesNotAddObserverCounts(helper);
    }

    @GameTest(template = "empty")
    public static void consumingAndCraftingOnlyTasksAreNotAutomaticallyDetected(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.consumingAndCraftingOnlyTasksAreNotAutomaticallyDetected(helper);
    }

    @GameTest(template = "empty")
    public static void lockedAndDependentTasksAreNotAutomaticallyDetected(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.lockedAndDependentTasksAreNotAutomaticallyDetected(helper);
    }

    @GameTest(template = "empty")
    public static void sequentialTaskWaitsForPreviousTask(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.sequentialTaskWaitsForPreviousTask(helper);
    }

    @GameTest(template = "empty")
    public static void nativeMainInventoryIsAnIndependentReadOnlyView(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.nativeMainInventoryIsAnIndependentReadOnlyView(helper);
    }

    private static final class Cases {
    private static void currentStoredQuantityCompletesNonConsumingTask(GameTestHelper helper) {
        if (!installed(helper)) return;
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 64));
            FtbAutoDetection.detect(fixture.player, fixture.data, List.of(fixture.task), List.of(source));
            helper.assertTrue(fixture.data.getProgress(fixture.task) == 64 && fixture.data.isCompleted(fixture.task),
                "Current storage quantity did not complete the real non-consuming FTB task");
            helper.assertTrue(source.count(new ItemStack(Items.IRON_INGOT)) == 64,
                "Read-only task detection consumed stored materials");
        }
        helper.succeed();
    }

    private static void fourDepositsUseCurrentWarehouseTotal(GameTestHelper helper) {
        if (!installed(helper)) return;
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            MemorySource source = new MemorySource();
            for (int quantity = 16; quantity <= 64; quantity += 16) {
                source.set(new ItemStack(Items.IRON_INGOT), quantity);
                fixture.detect(source);
                helper.assertTrue(fixture.data.getProgress(fixture.task) == quantity,
                    "Incremental deposits credited arrival amounts instead of the actual warehouse total");
            }
            helper.assertTrue(fixture.data.isCompleted(fixture.task)
                && source.count(new ItemStack(Items.IRON_INGOT)) == 64,
                "Four retained deposits did not complete a non-consuming task without changing warehouse stock");
        }
        helper.succeed();
    }

    private static void withdrawingAndRedepositingDoesNotAccumulateProgress(GameTestHelper helper) {
        if (!installed(helper)) return;
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            MemorySource source = new MemorySource();
            for (int attempt = 0; attempt < 4; attempt++) {
                source.set(new ItemStack(Items.IRON_INGOT), 16);
                fixture.detect(source);
                source.set(new ItemStack(Items.IRON_INGOT), 0);
                fixture.detect(source);
            }
            helper.assertTrue(fixture.data.getProgress(fixture.task) == 16 && !fixture.data.isCompleted(fixture.task),
                "Repeatedly storing the same sixteen materials accumulated false task progress");
        }
        helper.succeed();
    }

    private static void taskCountsAboveJeiSnapshotLimit(GameTestHelper helper) {
        if (!installed(helper)) return;
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 7000)) {
            MemorySource source = new MemorySource();
            source.set(new ItemStack(Items.IRON_INGOT), 9000);
            fixture.detect(source);
            helper.assertTrue(fixture.data.getProgress(fixture.task) == 7000 && fixture.data.isCompleted(fixture.task)
                && source.count(new ItemStack(Items.IRON_INGOT)) == 9000,
                "FTB current-stock counting inherited the JEI snapshot quantity limit or consumed materials");
        }
        helper.succeed();
    }

    private static void automaticDetectionUsesNativeFtbNbtMatching(GameTestHelper helper) {
        if (!installed(helper)) return;
        ItemStack red = tagged("red");
        ItemStack blue = tagged("blue");
        try (Fixture fixture = new Fixture(helper, red, 8)) {
            fixture.configure("match_nbt", Tristate.TRUE);
            MemorySource source = new MemorySource(red.copyWithCount(3), blue.copyWithCount(64));
            fixture.detect(source);
            helper.assertTrue(fixture.data.getProgress(fixture.task) == 3 && !fixture.data.isCompleted(fixture.task),
                "An NBT-sensitive FTB task counted a nonmatching stored variant");
        }
        try (Fixture fixture = new Fixture(helper, red, 8)) {
            fixture.configure("match_nbt", Tristate.FALSE);
            fixture.detect(new MemorySource(blue.copyWithCount(8)));
            helper.assertTrue(fixture.data.isCompleted(fixture.task),
                "An NBT-insensitive FTB task rejected a matching item variant");
        }
        helper.succeed();
    }

    private static void uncertainStorageViewsAreNotAddedTogether(GameTestHelper helper) {
        if (!installed(helper)) return;
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            MemorySource first = new MemorySource(new ItemStack(Items.IRON_INGOT, 32));
            MemorySource second = new MemorySource(new ItemStack(Items.IRON_INGOT, 32));
            FtbAutoDetection.detect(fixture.player, fixture.data, List.of(fixture.task), List.of(first, second));
            helper.assertTrue(fixture.data.getProgress(fixture.task) == 32 && !fixture.data.isCompleted(fixture.task),
                "Unproven independent storage views were added together and could double-count a bridged warehouse");
            fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 32));
            FtbAutoDetection.detect(fixture.player, fixture.data, List.of(fixture.task), List.of(first, second));
            helper.assertTrue(fixture.data.getProgress(fixture.task) == 32,
                "Player inventory was added to an uncertain storage view that could already include the player inventory");
        }
        helper.succeed();
    }

    private static void repeatedTeamDetectionDoesNotAddObserverCounts(GameTestHelper helper) {
        if (!installed(helper)) return;
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 32));
            fixture.detect(source);
            fixture.detect(source);
            helper.assertTrue(fixture.data.getProgress(fixture.task) == 32 && !fixture.data.isCompleted(fixture.task),
                "Repeated observations added the same team's warehouse quantity more than once");
        }
        helper.succeed();
    }

    private static void consumingAndCraftingOnlyTasksAreNotAutomaticallyDetected(GameTestHelper helper) {
        if (!installed(helper)) return;
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            fixture.task.setConsumeItems(Tristate.TRUE);
            assertRejected(helper, fixture, "Automatic inventory detection accepted a consuming FTB task");
        }
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            fixture.configure("only_from_crafting", Tristate.TRUE);
            assertRejected(helper, fixture, "Warehouse stock completed a crafting-only FTB task");
        }
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            CompoundTag config = new CompoundTag();
            fixture.task.writeData(config);
            config.putBoolean("task_screen_only", true);
            fixture.task.readData(config);
            assertRejected(helper, fixture, "Warehouse stock completed a task-screen-only FTB task");
        }
        helper.succeed();
    }

    private static void lockedAndDependentTasksAreNotAutomaticallyDetected(GameTestHelper helper) {
        if (!installed(helper)) return;
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            fixture.data.setLocked(true);
            assertRejected(helper, fixture, "Automatic detection credited a locked FTB team");
            fixture.data.setLocked(false);
        }
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            Quest prerequisite = new Quest(IDS.incrementAndGet(), fixture.chapter);
            fixture.chapter.addQuest(prerequisite);
            fixture.quest.addDependency(prerequisite);
            fixture.file.refreshIDMap();
            assertRejected(helper, fixture, "Automatic detection bypassed an incomplete FTB quest dependency");
        }
        helper.succeed();
    }

    private static void sequentialTaskWaitsForPreviousTask(GameTestHelper helper) {
        if (!installed(helper)) return;
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            ItemTask previous = new ItemTask(IDS.incrementAndGet(), fixture.quest);
            previous.setStackAndCount(new ItemStack(Items.GOLD_INGOT), 1);
            previous.setConsumeItems(Tristate.FALSE);
            fixture.quest.setTaskList(List.of(previous, fixture.task));
            CompoundTag config = new CompoundTag();
            fixture.quest.writeData(config);
            Tristate.TRUE.write(config, "require_sequential_tasks");
            fixture.quest.readData(config);
            fixture.file.refreshIDMap();
            assertRejected(helper, fixture, "Automatic detection bypassed the previous sequential FTB task");
        }
        helper.succeed();
    }

    private static void nativeMainInventoryIsAnIndependentReadOnlyView(GameTestHelper helper) {
        if (!installed(helper)) return;
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            FtbAutoDetection.detect(fixture.player, fixture.data, List.of(fixture.task), List.of());
            helper.assertTrue(fixture.data.isCompleted(fixture.task)
                && fixture.player.getInventory().getItem(0).getCount() == 64,
                "Automatic detection omitted or consumed the player's native main-inventory materials");
        }
        try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64)) {
            fixture.player.getInventory().setItem(40, new ItemStack(Items.IRON_INGOT, 64));
            FtbAutoDetection.detect(fixture.player, fixture.data, List.of(fixture.task), List.of());
            helper.assertTrue(fixture.data.getProgress(fixture.task) == 0,
                "Automatic native-inventory detection counted offhand materials outside FTB's main inventory");
        }
        helper.succeed();
    }

        private static void assertRejected(GameTestHelper helper, Fixture fixture, String message) {
            MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 64));
            fixture.detect(source);
            helper.assertTrue(fixture.data.getProgress(fixture.task) == 0 && source.queries == 0, message);
        }

        private static ItemStack tagged(String variant) {
            ItemStack stack = new ItemStack(Items.DIAMOND);
            stack.getOrCreateTag().putString("ftb_variant", variant);
            return stack;
        }
    }

    @GameTest(template = "empty", batch = "ftb_auto_magnet", timeoutTicks = 100)
    public static void realNetworkMagnetDepositCompletesTaskWithoutBackpackPickup(GameTestHelper helper) {
        if (!installed(helper)) return;
        if (!ModList.get().isLoaded("beyonddimensions")) {
            StorageFill.LOGGER.warn("SKIPPED FTB network magnet GameTest: Beyond Dimensions is absent");
            helper.succeed();
            return;
        }
        BeyondDimensionsCases.magnet(helper);
    }

    @GameTest(template = "empty", batch = "ftb_auto_catchup", timeoutTicks = 100)
    public static void loginAndNewlyAccessibleContainerDetectPreexistingNetworkStock(GameTestHelper helper) {
        if (!installed(helper) || !beyondDimensions(helper)) return;
        BeyondDimensionsCases.catchup(helper);
    }

    @GameTest(template = "empty", batch = "ftb_auto_unlock", timeoutTicks = 100)
    public static void unlockingAnotherItemTaskRechecksExistingWarehouseStock(GameTestHelper helper) {
        if (!installed(helper) || !beyondDimensions(helper)) return;
        BeyondDimensionsCases.unlock(helper);
    }

    @GameTest(template = "empty", batch = "ftb_auto_repeat", timeoutTicks = 100)
    public static void repeatableAutomaticRewardDoesNotLoopWithinOneDetectionWave(GameTestHelper helper) {
        if (!installed(helper) || !beyondDimensions(helper)) return;
        BeyondDimensionsCases.repeat(helper);
    }

    @GameTest(template = "empty", batch = "ftb_auto_teams", timeoutTicks = 100)
    public static void oneSharedNetworkDepositUpdatesDifferentTeamsIndependently(GameTestHelper helper) {
        if (!installed(helper) || !beyondDimensions(helper)) return;
        BeyondDimensionsCases.differentTeams(helper);
    }

    private static boolean beyondDimensions(GameTestHelper helper) {
        if (ModList.get().isLoaded("beyonddimensions")) return true;
        StorageFill.LOGGER.warn("SKIPPED FTB automatic warehouse GameTest: Beyond Dimensions is absent");
        helper.succeed();
        return false;
    }

    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbteams")) return true;
        StorageFill.LOGGER.warn("SKIPPED FTB auto-detection GameTest: FTB Quests or Teams is absent");
        helper.succeed();
        return false;
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerQuestFile file;
        private final Chapter chapter;
        private final Quest quest;
        private final ItemTask task;
        private final ServerPlayer player;
        private final TeamData data;
        private List<ServerPlayer> onlinePlayers;
        private Map<UUID, ServerPlayer> onlineById;

        private Fixture(GameTestHelper helper, ItemStack prototype, int count) {
            file = ServerQuestFile.INSTANCE;
            helper.assertTrue(file != null, "Real FTB server quest file was not initialized");
            UUID playerId = UUID.randomUUID();
            player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(playerId, "ftb-auto"));
            player.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(),
                new Connection(PacketFlow.SERVERBOUND), player);
            TeamManagerImpl manager = (TeamManagerImpl) FTBTeamsAPI.api().getManager();
            manager.playerLoggedIn(null, playerId, player.getGameProfile().getName());
            data = TeamData.get(player);
            helper.assertTrue(data != null && data.getTeamId().equals(manager.getTeamForPlayer(player).orElseThrow().getId()),
                "Real FTB Teams player binding was not created");
            chapter = new Chapter(IDS.incrementAndGet(), file, file.getDefaultChapterGroup());
            file.getDefaultChapterGroup().addChapter(chapter);
            quest = new Quest(IDS.incrementAndGet(), chapter);
            chapter.addQuest(quest);
            task = new ItemTask(IDS.incrementAndGet(), quest);
            task.setStackAndCount(prototype, count);
            task.setConsumeItems(Tristate.FALSE);
            quest.addTask(task);
            file.refreshIDMap();
            file.clearCachedData();
        }

        public void close() {
            offline();
            file.getDefaultChapterGroup().removeChapter(chapter);
            file.refreshIDMap();
            file.clearCachedData();
        }

        @SuppressWarnings("unchecked")
        private void online() {
            if (onlinePlayers != null) return;
            try {
                PlayerList list = player.server.getPlayerList();
                var playersField = PlayerList.class.getDeclaredField("players");
                playersField.setAccessible(true);
                var byIdField = PlayerList.class.getDeclaredField("playersByUUID");
                byIdField.setAccessible(true);
                onlinePlayers = (List<ServerPlayer>) playersField.get(list);
                onlineById = (Map<UUID, ServerPlayer>) byIdField.get(list);
                onlinePlayers.add(player);
                onlineById.put(player.getUUID(), player);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Unable to register the isolated GameTest player in the real online player list", failure);
            }
        }

        private void offline() {
            if (onlinePlayers == null) return;
            onlinePlayers.remove(player);
            onlineById.remove(player.getUUID(), player);
            onlinePlayers = null;
            onlineById = null;
        }

        private void configure(String key, Tristate value) {
            CompoundTag config = new CompoundTag();
            task.writeData(config);
            value.write(config, key);
            task.readData(config);
        }

        private void detect(MemorySource source) {
            FtbAutoDetection.detect(player, data, List.of(task), List.of(source));
        }

        private ItemTask dependent(ItemStack prototype, int count) {
            Quest dependent = new Quest(IDS.incrementAndGet(), chapter);
            chapter.addQuest(dependent);
            dependent.addDependency(quest);
            ItemTask task = new ItemTask(IDS.incrementAndGet(), dependent);
            task.setStackAndCount(prototype, count);
            task.setConsumeItems(Tristate.FALSE);
            dependent.addTask(task);
            file.refreshIDMap();
            file.clearCachedData();
            return task;
        }

        private void reward(Consumer<ServerPlayer> callback) {
            quest.addReward(new CallbackReward(IDS.incrementAndGet(), quest, callback));
            file.refreshIDMap();
            file.clearCachedData();
        }
    }

    private static final class CallbackReward extends Reward {
        private final Consumer<ServerPlayer> callback;

        private CallbackReward(long id, Quest quest, Consumer<ServerPlayer> callback) {
            super(id, quest);
            this.callback = callback;
            CompoundTag config = new CompoundTag();
            Tristate.TRUE.write(config, "team_reward");
            config.putString("auto", "enabled");
            readData(config);
        }

        public RewardType getType() { return RewardTypes.ITEM; }
        public void claim(ServerPlayer player, boolean notify) { callback.accept(player); }
    }

    private static final class BeyondDimensionsCases {
        private static void magnet(GameTestHelper helper) {
            Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64);
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(fixture.player, 10000L, 64);
            helper.assertTrue(network != null, "Real Beyond Dimensions network creation failed");
            fixture.online();
            try {
                BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
                fixture.player.setPos(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
                ItemStack magnetStack = new ItemStack(
                    com.wintercogs.beyonddimensions.common.init.BDItems.NET_MAGNET_ITEM.get());
                var magnet = (com.wintercogs.beyonddimensions.common.item.NetMagnetItem) magnetStack.getItem();
                magnet.checkComponents(magnetStack);
                helper.assertTrue(com.wintercogs.beyonddimensions.common.item.NetedItem.setNet(magnetStack, fixture.player),
                    "Real network magnet did not bind to the player's primary network");
                fixture.player.getInventory().setItem(0, magnetStack);
                ItemEntity dropped = new ItemEntity(helper.getLevel(), position.getX() + 0.5,
                    position.getY() + 0.5, position.getZ() + 0.5, new ItemStack(Items.IRON_INGOT, 64));
                helper.assertTrue(helper.getLevel().addFreshEntity(dropped), "Real magnet test item entity failed to spawn");
                magnet.workContent(magnetStack, helper.getLevel(), fixture.player, 0, true);
                helper.assertTrue(dropped.isRemoved(), "Real network magnet did not collect the test item entity");
                List<StorageSource> sources = StorageSources.find(fixture.player);
                helper.assertTrue(sources.size() == 1 && sources.get(0).countMatching(Items.IRON_INGOT,
                    fixture.task::test, 64) == 64, "Real network magnet did not deposit all sixty-four materials");
                helper.assertTrue(fixture.player.getInventory().items.stream().noneMatch(stack -> stack.is(Items.IRON_INGOT)),
                    "Network magnet test unexpectedly put materials into the player's backpack");
                helper.runAtTickTime(60, () -> {
                    try {
                        helper.assertTrue(fixture.data.getProgress(fixture.task) == 64
                            && fixture.data.isCompleted(fixture.task),
                            "Successful real network magnet deposit did not trigger FTB task completion through the server tick service");
                        helper.assertTrue(sources.get(0).countMatching(Items.IRON_INGOT, fixture.task::test, 64) == 64,
                            "Automatic FTB detection consumed real network magnet materials");
                        helper.succeed();
                    } finally {
                        network.destroySelf();
                        fixture.close();
                    }
                });
            } catch (RuntimeException | AssertionError failure) {
                network.destroySelf();
                fixture.close();
                throw failure;
            }
        }

        private static void catchup(GameTestHelper helper) {
            Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64);
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(fixture.player, 10000L, 64);
            helper.assertTrue(network != null, "Login catch-up network creation failed");
            try {
                network.getUnifiedStorage().insert(new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(
                    new ItemStack(Items.IRON_INGOT)), 64, false);
                dev.airun.storagefill.storage.StorageChanges.drain();
                fixture.online();
                var team = FTBTeamsAPI.api().getManager().getTeamForPlayer(fixture.player).orElseThrow();
                TeamEvent.PLAYER_LOGGED_IN.invoker().accept(new PlayerLoggedInAfterTeamEvent(team, fixture.player));
                helper.runAtTickTime(30, () -> {
                    try {
                        helper.assertTrue(fixture.data.isCompleted(fixture.task),
                            "Login did not detect warehouse materials stored before the player was online");
                        fixture.data.setProgress(fixture.task, 0);
                        com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                            .setPrimaryNetForPlayer(fixture.player, null);
                        helper.assertTrue(StorageSources.find(fixture.player).isEmpty(),
                            "Catch-up test did not remove the player's current warehouse access");
                        MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(fixture.player, fixture.player.inventoryMenu));
                    } catch (RuntimeException | AssertionError failure) {
                        network.destroySelf();
                        fixture.close();
                        throw failure;
                    }
                });
                helper.runAtTickTime(45, () -> {
                    try {
                        helper.assertTrue(fixture.data.getProgress(fixture.task) == 0,
                            "Opening a container credited unavailable warehouse stock");
                        com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                            .setPrimaryNetForPlayer(fixture.player, network);
                        helper.assertTrue(com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                            .getNetFromPlayer(fixture.player) == network,
                            "Catch-up test did not restore access to the original warehouse");
                        MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(fixture.player, fixture.player.inventoryMenu));
                    } catch (RuntimeException | AssertionError failure) {
                        network.destroySelf();
                        fixture.close();
                        throw failure;
                    }
                });
                helper.runAtTickTime(75, () -> {
                    try {
                        helper.assertTrue(fixture.data.isCompleted(fixture.task),
                            "Opening a container after warehouse access changed did not recheck already stored materials");
                        helper.succeed();
                    } finally {
                        network.destroySelf();
                        fixture.close();
                    }
                });
            } catch (RuntimeException | AssertionError failure) {
                network.destroySelf();
                fixture.close();
                throw failure;
            }
        }

        private static void unlock(GameTestHelper helper) {
            Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64);
            ItemTask dependent = fixture.dependent(new ItemStack(Items.GOLD_INGOT), 32);
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(fixture.player, 10000L, 64);
            helper.assertTrue(network != null, "Dependency catch-up network creation failed");
            try {
                network.getUnifiedStorage().insert(new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(
                    new ItemStack(Items.GOLD_INGOT)), 32, false);
                dev.airun.storagefill.storage.StorageChanges.drain();
                fixture.online();
                network.getUnifiedStorage().insert(new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(
                    new ItemStack(Items.IRON_INGOT)), 64, false);
                helper.runAtTickTime(60, () -> {
                    try {
                        helper.assertTrue(fixture.data.isCompleted(fixture.task)
                            && fixture.data.isCompleted(dependent) && fixture.data.getProgress(dependent) == 32,
                            "Completing an iron task did not recheck preexisting gold stock for its newly unlocked dependent task");
                        helper.succeed();
                    } finally {
                        network.destroySelf();
                        fixture.close();
                    }
                });
            } catch (RuntimeException | AssertionError failure) {
                network.destroySelf();
                fixture.close();
                throw failure;
            }
        }

        private static void repeat(GameTestHelper helper) {
            Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64);
            CompoundTag config = new CompoundTag();
            fixture.quest.writeData(config);
            Tristate.TRUE.write(config, "can_repeat");
            fixture.quest.readData(config);
            AtomicInteger rewards = new AtomicInteger();
            fixture.reward(player -> rewards.incrementAndGet());
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(fixture.player, 10000L, 64);
            helper.assertTrue(network != null, "Repeatable task network creation failed");
            fixture.online();
            try {
                network.getUnifiedStorage().insert(new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(
                    new ItemStack(Items.IRON_INGOT)), 64, false);
                helper.runAtTickTime(60, () -> {
                    try {
                        helper.assertTrue(rewards.get() == 1 && fixture.data.getCompletionCount(fixture.quest) == 1
                            && fixture.data.getProgress(fixture.task) == 0 && !fixture.data.isCompleted(fixture.task),
                            "One warehouse-change wave repeatedly completed and rewarded the same resettable task");
                        helper.assertTrue(StorageSources.find(fixture.player).get(0).countMatching(Items.IRON_INGOT,
                            fixture.task::test, 64) == 64, "Repeatable non-consuming detection changed warehouse stock");
                        helper.succeed();
                    } finally {
                        network.destroySelf();
                        fixture.close();
                    }
                });
            } catch (RuntimeException | AssertionError failure) {
                network.destroySelf();
                fixture.close();
                throw failure;
            }
        }

        private static void differentTeams(GameTestHelper helper) {
            Fixture first = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 64);
            Fixture second = new Fixture(helper, new ItemStack(Items.GOLD_INGOT), 64);
            helper.assertTrue(!first.data.getTeamId().equals(second.data.getTeamId()),
                "Cross-team queue test did not create two different real FTB teams");
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(first.player, 10000L, 64);
            helper.assertTrue(network != null, "Shared-network team test failed to create a real BD network");
            try {
                network.addPlayer(second.player.getUUID());
                com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                    .setPrimaryNetForPlayer(second.player, network);
                helper.assertTrue(com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                    .getNetFromPlayer(second.player) == network,
                    "Second FTB team's player could not select the shared BD network");
                first.online();
                second.online();
                network.getUnifiedStorage().insert(new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(
                    new ItemStack(Items.IRON_INGOT)), 64, false);
                helper.runAtTickTime(60, () -> {
                    try {
                        helper.assertTrue(first.data.getProgress(first.task) == 64 && first.data.isCompleted(first.task)
                            && second.data.getProgress(first.task) == 64 && second.data.isCompleted(first.task),
                            "One network-change wave skipped the same global task ID for a second eligible FTB team");
                        helper.assertTrue(StorageSources.find(first.player).get(0).countMatching(Items.IRON_INGOT,
                            first.task::test, 64) == 64,
                            "Cross-team automatic detection consumed materials from the shared network");
                        helper.succeed();
                    } finally {
                        network.destroySelf();
                        first.close();
                        second.close();
                    }
                });
            } catch (RuntimeException | AssertionError failure) {
                network.destroySelf();
                first.close();
                second.close();
                throw failure;
            }
        }
    }

    private static final class MemorySource implements StorageSource {
        private final Map<StackKey, Long> stored = new LinkedHashMap<>();
        private int queries;

        private MemorySource(ItemStack... stacks) {
            for (ItemStack stack : stacks) stored.merge(StackKey.of(stack), (long) stack.getCount(), Long::sum);
        }

        private long count(ItemStack prototype) {
            return stored.getOrDefault(StackKey.of(prototype), 0L);
        }

        private void set(ItemStack prototype, long amount) {
            if (amount <= 0) stored.remove(StackKey.of(prototype));
            else stored.put(StackKey.of(prototype), amount);
        }

        public long countMatching(Item item, Predicate<ItemStack> matches, long limit) {
            queries++;
            long count = 0;
            for (Map.Entry<StackKey, Long> entry : stored.entrySet()) {
                if (entry.getKey().item() == item && matches.test(entry.getKey().stack(1))) {
                    count += Math.min(Math.max(0, limit - count), entry.getValue());
                    if (count >= limit) break;
                }
            }
            return count;
        }

        public List<ItemStack> snapshot(Set<Item> wanted) {
            throw new AssertionError("Read-only FTB detection requested a JEI inventory snapshot");
        }

        public List<ItemStack> preview(Set<Item> wanted) {
            throw new AssertionError("Targeted FTB detection enumerated all warehouse candidates");
        }

        public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
            throw new AssertionError("Read-only FTB detection called extraction or extraction simulation");
        }

        public ItemStack insert(ItemStack stack) {
            throw new AssertionError("Read-only FTB detection attempted to insert materials");
        }

        public void finish(int extracted) {
            throw new AssertionError("Read-only FTB detection charged storage extraction energy");
        }
    }
}

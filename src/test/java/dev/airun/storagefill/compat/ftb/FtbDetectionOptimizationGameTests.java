package dev.airun.storagefill.compat.ftb;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.storage.StorageChanges;
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
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class FtbDetectionOptimizationGameTests {
    @GameTest(template = "empty")
    public static void matchingUnchangedTasksShareOneCurrentQuantityQuery(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.sharedQuantity(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void quantityReusePreservesMaximumOfIndependentStorageViews(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.maximumViews(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void strictAndWeakNbtRulesNeverShareIncorrectQuantities(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.nbtRules(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nbtIgnoredTasksShareTheirEquivalentItemRule(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.ignoredNbt(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void separateDetectionCallsAlwaysReadCurrentQuantity(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.separateCalls(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyProgressWriteInvalidatesCachedQuantities(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.progressWrites(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void completionRewardStockMutationsInvalidateQuantities(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.rewardStock(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void freshlyUnlockedTaskReadsStockChangedByReward(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.unlockedStock(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void truncatedSmallTaskQuantityDoesNotLimitLargerTask(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.differentLimits(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardRevokedSourceIsRevalidatedBeforeNextTask(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.revokedMemory(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualBdBindingAndMembershipChangesInvalidatePreparedCount(GameTestHelper helper) {
        if (!installed(helper)) return;
        if (!ModList.get().isLoaded("beyonddimensions")) {
            StorageFill.LOGGER.warn("SKIPPED FTB optimized source access GameTest: Beyond Dimensions is absent");
            helper.succeed();
            return;
        }
        BeyondDimensionsCases.accessChange(helper, true);
        BeyondDimensionsCases.accessChange(helper, false);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void preparedMatcherSurvivesOriginalTaskRuleMutation(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.frozenMatcher(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "ftb_optimized_access_catchup", timeoutTicks = 140)
    public static void rewardChangedPrimaryNetworkProvidesNewTaskCatchup(GameTestHelper helper) {
        if (!installed(helper)) return;
        if (!ModList.get().isLoaded("beyonddimensions")) {
            StorageFill.LOGGER.warn("SKIPPED FTB optimized queue access GameTest: Beyond Dimensions is absent");
            helper.succeed();
            return;
        }
        BeyondDimensionsCases.changedPrimaryCatchup(helper);
    }

    @GameTest(template = "empty")
    public static void anotherTeamRewardInvalidatesInterleavedSessionQuantity(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.interleavedTeams(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "ftb_optimized_rule_budget", timeoutTicks = 140)
    public static void differentRuleBudgetRetainsTasksBeyondSixteen(GameTestHelper helper) {
        if (!installed(helper)) return;
        if (!ModList.get().isLoaded("beyonddimensions")) {
            StorageFill.LOGGER.warn("SKIPPED FTB optimized rule budget GameTest: Beyond Dimensions is absent");
            helper.succeed();
            return;
        }
        BeyondDimensionsCases.ruleBudget(helper);
    }

    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbteams")) return true;
        StorageFill.LOGGER.warn("SKIPPED FTB detection optimization GameTest: FTB Quests or Teams is absent");
        helper.succeed();
        return false;
    }

    private static final class Cases {
        private static void sharedQuantity(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                List<ItemTask> tasks = new ArrayList<>();
                for (int index = 0; index < 100; index++) {
                    ItemTask task = fixture.task(new ItemStack(Items.IRON_INGOT), 1000);
                    fixture.data.setProgress(task, 64);
                    tasks.add(task);
                }
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 32));
                fixture.detect(tasks, List.of(source));
                helper.assertTrue(source.queries == 1,
                    "One detection session repeatedly queried the same unchanged item matching rule");
                helper.assertTrue(tasks.stream().allMatch(task -> fixture.data.getProgress(task) == 64
                    && !fixture.data.isCompleted(task)), "Read-only quantity reuse changed existing FTB progress");
            }
        }

        private static void maximumViews(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemTask first = fixture.task(new ItemStack(Items.IRON_INGOT), 1000);
                ItemTask second = fixture.task(new ItemStack(Items.IRON_INGOT), 1000);
                fixture.data.setProgress(first, 64);
                fixture.data.setProgress(second, 64);
                MemorySource smaller = new MemorySource(new ItemStack(Items.IRON_INGOT, 32));
                MemorySource larger = new MemorySource(new ItemStack(Items.IRON_INGOT, 64));
                fixture.detect(List.of(first, second), List.of(smaller, larger));
                helper.assertTrue(smaller.queries == 1 && larger.queries == 1,
                    "Quantity reuse failed to cache each current storage view separately");
                helper.assertTrue(fixture.data.getProgress(first) == 64 && fixture.data.getProgress(second) == 64,
                    "Cached quantities added uncertain overlapping storage views instead of preserving their maximum");
            }
        }

        private static void nbtRules(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemStack red = tagged("red", false);
                ItemStack redExtra = tagged("red", true);
                ItemStack blue = tagged("blue", false);
                ItemTask strictRed = fixture.task(red, 1000);
                ItemTask strictBlue = fixture.task(blue, 1000);
                ItemTask weakRed = fixture.task(red, 1000);
                fixture.nbt(strictRed, Tristate.TRUE, false);
                fixture.nbt(strictBlue, Tristate.TRUE, false);
                fixture.nbt(weakRed, Tristate.TRUE, true);
                for (ItemTask task : List.of(strictRed, strictBlue, weakRed)) fixture.data.setProgress(task, 64);
                MemorySource source = new MemorySource(red.copyWithCount(16), redExtra.copyWithCount(32), blue.copyWithCount(8));
                fixture.detect(List.of(strictRed, strictBlue, weakRed), List.of(source));
                helper.assertTrue(source.queries == 3 && source.observed.equals(List.of(16L, 8L, 48L)),
                    "Strict NBT variants or weak NBT matching incorrectly shared a current quantity result");
            }
        }

        private static void ignoredNbt(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemTask red = fixture.task(tagged("red", false), 1000);
                ItemTask blue = fixture.task(tagged("blue", false), 1000);
                fixture.nbt(red, Tristate.FALSE, false);
                fixture.nbt(blue, Tristate.FALSE, false);
                fixture.data.setProgress(red, 64);
                fixture.data.setProgress(blue, 64);
                MemorySource source = new MemorySource(tagged("green", false).copyWithCount(32));
                fixture.detect(List.of(red, blue), List.of(source));
                helper.assertTrue(source.queries == 1,
                    "Equivalent item matching rules were separated by prototype NBT that FTB ignores");
            }
        }

        private static void separateCalls(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemTask task = fixture.task(new ItemStack(Items.IRON_INGOT), 1000);
                fixture.data.setProgress(task, 64);
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 32));
                fixture.detect(List.of(task), List.of(source));
                source.set(new ItemStack(Items.IRON_INGOT), 128);
                fixture.detect(List.of(task), List.of(source));
                helper.assertTrue(source.queries == 2 && fixture.data.getProgress(task) == 128,
                    "An independent detection call reused a previous call's stale warehouse quantity");
            }
        }

        private static void progressWrites(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemTask first = fixture.task(new ItemStack(Items.IRON_INGOT), 1000);
                ItemTask second = fixture.task(new ItemStack(Items.IRON_INGOT), 1000);
                ItemTask third = fixture.task(new ItemStack(Items.IRON_INGOT), 1000);
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 64));
                fixture.detect(List.of(first, second, third), List.of(source));
                helper.assertTrue(source.queries == 3 && fixture.data.getProgress(first) == 64
                    && fixture.data.getProgress(second) == 64 && fixture.data.getProgress(third) == 64,
                    "An FTB progress write retained quantities across a callback boundary");
            }
        }

        private static void rewardStock(GameTestHelper helper) {
            for (long afterReward : List.of(0L, 128L)) {
                try (Fixture fixture = new Fixture(helper)) {
                    ItemTask first = fixture.task(new ItemStack(Items.IRON_INGOT), 64);
                    ItemTask next = fixture.task(new ItemStack(Items.IRON_INGOT), 128);
                    MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 64));
                    fixture.reward(first, player -> source.set(new ItemStack(Items.IRON_INGOT), afterReward));
                    fixture.detect(List.of(first, next), List.of(source));
                    helper.assertTrue(fixture.data.isCompleted(first) && fixture.data.getProgress(next) == afterReward
                        && fixture.data.isCompleted(next) == (afterReward == 128),
                        "The next task counted warehouse stock from before a completion reward changed it");
                }
            }
        }

        private static void unlockedStock(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemTask first = fixture.task(new ItemStack(Items.IRON_INGOT), 64);
                ItemTask next = fixture.task(new ItemStack(Items.IRON_INGOT), 128);
                next.getQuest().addDependency(first.getQuest());
                fixture.refresh();
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 64));
                fixture.reward(first, player -> source.set(new ItemStack(Items.IRON_INGOT), 128));
                fixture.detect(List.of(first, next), List.of(source));
                helper.assertTrue(fixture.data.isCompleted(first) && fixture.data.isCompleted(next),
                    "A newly unlocked task did not use the updated current stock after its predecessor's reward");
            }
        }

        private static void differentLimits(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemTask small = fixture.task(new ItemStack(Items.IRON_INGOT), 64);
                ItemTask large = fixture.task(new ItemStack(Items.IRON_INGOT), 1000);
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 128));
                fixture.detect(List.of(small, large), List.of(source));
                helper.assertTrue(fixture.data.isCompleted(small) && fixture.data.getProgress(large) == 128
                    && !fixture.data.isCompleted(large),
                    "A quantity truncated by a sixty-four-item task was reused as a complete larger-task count");
            }
        }

        private static void revokedMemory(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemTask first = fixture.task(new ItemStack(Items.IRON_INGOT), 64);
                ItemTask next = fixture.task(new ItemStack(Items.IRON_INGOT), 128);
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 64));
                fixture.reward(first, player -> source.available = false);
                fixture.detect(List.of(first, next), List.of(source));
                helper.assertTrue(fixture.data.isCompleted(first) && fixture.data.getProgress(next) == 0
                    && source.count(new ItemStack(Items.IRON_INGOT)) == 64,
                    "A prepared unknown source exposed stock after its access was revoked by a reward callback");
            }
        }

        private static void frozenMatcher(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemStack red = tagged("red", false);
                ItemStack blue = tagged("blue", false);
                ItemTask first = fixture.task(red, 16);
                ItemTask next = fixture.task(red, 1000);
                fixture.nbt(first, Tristate.TRUE, false);
                fixture.nbt(next, Tristate.TRUE, false);
                MemorySource source = new MemorySource(red.copyWithCount(32), blue.copyWithCount(64));
                fixture.reward(first, player -> {
                    first.setStackAndCount(blue.copy(), 16);
                    CompoundTag config = new CompoundTag();
                    first.writeData(config);
                    Tristate.FALSE.write(config, "match_nbt");
                    config.putBoolean("weak_nbt_match", true);
                    first.readData(config);
                });
                fixture.detect(List.of(first, next), List.of(source));
                helper.assertTrue(fixture.data.isCompleted(first) && fixture.data.getProgress(next) == 32
                    && source.observed.equals(List.of(16L, 32L)),
                    "A reused prepared count captured the mutable originating task instead of freezing its original matching rule");
            }
        }

        private static void interleavedTeams(GameTestHelper helper) {
            try (Fixture first = new Fixture(helper); Fixture second = new Fixture(helper)) {
                helper.assertTrue(!first.data.getTeamId().equals(second.data.getTeamId()),
                    "Interleaved quantity regression did not create two distinct real FTB teams");
                ItemTask observed = first.task(new ItemStack(Items.IRON_INGOT), 1000);
                ItemTask subsequent = first.task(new ItemStack(Items.IRON_INGOT), 1000);
                ItemTask rewarding = second.task(new ItemStack(Items.IRON_INGOT), 64);
                first.data.setProgress(observed, 64);
                MemorySource shared = new MemorySource(new ItemStack(Items.IRON_INGOT, 64));
                second.reward(rewarding, player -> shared.set(new ItemStack(Items.IRON_INGOT), 0));
                FtbDetectionSession firstSession = new FtbDetectionSession(16);
                FtbDetectionSession secondSession = new FtbDetectionSession(16);
                FtbAutoDetection.detect(first.player, first.data, List.of(observed), List.of(shared), firstSession);
                helper.assertTrue(shared.queries == 1 && first.data.getProgress(observed) == 64,
                    "Initial interleaved session did not retain one read-only current quantity");
                FtbAutoDetection.detect(second.player, second.data, List.of(rewarding), List.of(shared), secondSession);
                helper.assertTrue(second.data.isCompleted(rewarding) && shared.count(new ItemStack(Items.IRON_INGOT)) == 0,
                    "Other team's completion reward did not remove the shared warehouse stock");
                FtbAutoDetection.detect(first.player, first.data, List.of(subsequent), List.of(shared), firstSession);
                helper.assertTrue(first.data.getProgress(subsequent) == 0 && shared.queries == 3,
                    "An interleaved session reused stock from before another FTB team's reward changed it");
            }
        }

        private static ItemStack tagged(String variant, boolean extra) {
            ItemStack stack = new ItemStack(Items.DIAMOND);
            stack.getOrCreateTag().putString("variant", variant);
            if (extra) stack.getOrCreateTag().putInt("extra", 1);
            return stack;
        }

        private static final class Fixture implements AutoCloseable {
            private static final AtomicLong IDS = new AtomicLong(0x7400000000000000L);
            private final ServerQuestFile file;
            private final Chapter chapter;
            private final ServerPlayer player;
            private final TeamData data;
            private List<ServerPlayer> onlinePlayers;
            private Map<UUID, ServerPlayer> onlineById;

            private Fixture(GameTestHelper helper) {
                file = ServerQuestFile.INSTANCE;
                helper.assertTrue(file != null, "Optimization regression requires a real initialized FTB quest file");
                UUID id = UUID.randomUUID();
                player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(id, "ftb-cache"));
                player.connection = new ServerGamePacketListenerImpl(player.server, new Connection(PacketFlow.SERVERBOUND), player);
                ((TeamManagerImpl) FTBTeamsAPI.api().getManager()).playerLoggedIn(null, id, player.getGameProfile().getName());
                data = TeamData.get(player);
                helper.assertTrue(data != null, "Optimization regression failed to create a real FTB team");
                chapter = new Chapter(IDS.incrementAndGet(), file, file.getDefaultChapterGroup());
                file.getDefaultChapterGroup().addChapter(chapter);
                refresh();
            }

            private ItemTask task(ItemStack prototype, int count) {
                Quest quest = new Quest(IDS.incrementAndGet(), chapter);
                chapter.addQuest(quest);
                ItemTask task = new ItemTask(IDS.incrementAndGet(), quest);
                task.setStackAndCount(prototype, count);
                task.setConsumeItems(Tristate.FALSE);
                quest.addTask(task);
                refresh();
                return task;
            }

            private void nbt(ItemTask task, Tristate match, boolean weak) {
                CompoundTag config = new CompoundTag();
                task.writeData(config);
                match.write(config, "match_nbt");
                config.putBoolean("weak_nbt_match", weak);
                task.readData(config);
                refresh();
            }

            private void reward(ItemTask task, Consumer<ServerPlayer> callback) {
                task.getQuest().addReward(new CallbackReward(IDS.incrementAndGet(), task.getQuest(), callback));
                refresh();
            }

            private void detect(List<ItemTask> tasks, List<StorageSource> sources) {
                FtbAutoDetection.detect(player, data, tasks, sources);
            }

            private void refresh() {
                file.refreshIDMap();
                file.clearCachedData();
            }

            @SuppressWarnings("unchecked")
            private void online() {
                try {
                    PlayerList players = player.server.getPlayerList();
                    var listField = PlayerList.class.getDeclaredField("players");
                    listField.setAccessible(true);
                    var byIdField = PlayerList.class.getDeclaredField("playersByUUID");
                    byIdField.setAccessible(true);
                    onlinePlayers = (List<ServerPlayer>) listField.get(players);
                    onlineById = (Map<UUID, ServerPlayer>) byIdField.get(players);
                    onlinePlayers.add(player);
                    onlineById.put(player.getUUID(), player);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("Could not register the isolated optimized queue player", failure);
                }
            }

            public void close() {
                if (onlinePlayers != null) {
                    onlinePlayers.remove(player);
                    onlineById.remove(player.getUUID(), player);
                    onlinePlayers = null;
                    onlineById = null;
                }
                file.getDefaultChapterGroup().removeChapter(chapter);
                refresh();
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

        private static final class MemorySource implements StorageSource {
            private final Map<StackKey, Long> stored = new LinkedHashMap<>();
            private final List<Long> observed = new ArrayList<>();
            private int queries;
            private boolean available = true;

            private MemorySource(ItemStack... stacks) {
                for (ItemStack stack : stacks) stored.merge(StackKey.of(stack), (long) stack.getCount(), Long::sum);
            }

            private long count(ItemStack prototype) { return stored.getOrDefault(StackKey.of(prototype), 0L); }

            private void set(ItemStack prototype, long count) {
                if (count <= 0) stored.remove(StackKey.of(prototype));
                else stored.put(StackKey.of(prototype), count);
            }

            public long countMatching(Item item, Predicate<ItemStack> matches, long limit) {
                queries++;
                long count = 0;
                if (available) for (var entry : stored.entrySet()) {
                    if (entry.getKey().item() == item && matches.test(entry.getKey().stack(1))) {
                        count += Math.min(limit - count, entry.getValue());
                        if (count >= limit) break;
                    }
                }
                observed.add(count);
                return count;
            }

            public List<ItemStack> snapshot(Set<Item> wanted) {
                throw new AssertionError("FTB quantity optimization requested a JEI inventory snapshot");
            }

            public List<ItemStack> preview(Set<Item> wanted) {
                throw new AssertionError("FTB quantity optimization enumerated warehouse preview candidates");
            }

            public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
                throw new AssertionError("Read-only quantity optimization extracted or simulated extracting materials");
            }

            public ItemStack insert(ItemStack stack) {
                throw new AssertionError("Read-only quantity optimization inserted materials");
            }

            public void finish(int extracted) {
                throw new AssertionError("Read-only quantity optimization charged extraction energy");
            }
        }
    }

    private static final class BeyondDimensionsCases {
        private static void ruleBudget(GameTestHelper helper) {
            Cases.Fixture fixture = new Cases.Fixture(helper);
            List<ItemTask> tasks = new ArrayList<>();
            for (int index = 0; index < 21; index++) {
                ItemTask task = fixture.task(Cases.tagged("budget-" + index, false), 1000);
                fixture.nbt(task, Tristate.TRUE, false);
                tasks.add(task);
            }
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(fixture.player, 10000L, 64);
            helper.assertTrue(network != null, "Distinct-rule budget fixture could not create its real BD network");
            try {
                StorageChanges.clear();
                for (ItemTask task : tasks) {
                    var key = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(task.getItemStack());
                    helper.assertTrue(network.getUnifiedStorage().insert(key, 64, false).isEmpty(),
                        "Distinct-rule budget fixture could not store each NBT variant");
                }
                fixture.online();
                helper.runAtTickTime(100, () -> {
                    try {
                        for (int index = 0; index < tasks.size(); index++) {
                            ItemTask task = tasks.get(index);
                            helper.assertTrue(fixture.data.getProgress(task) == 64 && !fixture.data.isCompleted(task),
                                "A task beyond the per-tick distinct-rule budget was skipped: rule " + (index + 1));
                            var key = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(task.getItemStack());
                            helper.assertTrue(network.getUnifiedStorage().getStackByKey(key).amount() == 64,
                                "Distinct-rule budget processing consumed a stored NBT variant");
                        }
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

        private static void changedPrimaryCatchup(GameTestHelper helper) {
            Cases.Fixture fixture = new Cases.Fixture(helper);
            ItemTask first = fixture.task(new ItemStack(Items.IRON_INGOT), 64);
            ItemTask next = fixture.task(new ItemStack(Items.GOLD_INGOT), 32);
            next.getQuest().addDependency(first.getQuest());
            fixture.refresh();
            var initial = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(fixture.player, 10000L, 64);
            ServerPlayer otherOwner = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "ftb-next-net"));
            var granted = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(otherOwner, 10000L, 64);
            helper.assertTrue(initial != null && granted != null, "Optimized queue catch-up could not create real networks");
            try {
                granted.addPlayer(fixture.player.getUUID());
                var iron = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(new ItemStack(Items.IRON_INGOT));
                var gold = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(new ItemStack(Items.GOLD_INGOT));
                helper.assertTrue(granted.getUnifiedStorage().insert(gold, 32, false).isEmpty(),
                    "Optimized queue catch-up could not prepare preexisting stock in the newly granted network");
                com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                    .setPrimaryNetForPlayer(fixture.player, initial);
                fixture.reward(first, player -> com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                    .setPrimaryNetForPlayer(player, granted));
                StorageChanges.clear();
                fixture.online();
                helper.assertTrue(initial.getUnifiedStorage().insert(iron, 64, false).isEmpty(),
                    "Optimized queue catch-up could not trigger its initial native deposit");
                helper.runAtTickTime(100, () -> {
                    try {
                        helper.assertTrue(fixture.data.isCompleted(first) && fixture.data.isCompleted(next)
                            && fixture.data.getProgress(next) == 32,
                            "A reward changed the player's primary network but the natural queue did not catch up its newly unlocked task");
                        helper.assertTrue(com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                            .getNetFromPlayer(fixture.player) == granted
                            && initial.getUnifiedStorage().getStackByKey(iron).amount() == 64
                            && granted.getUnifiedStorage().getStackByKey(gold).amount() == 32,
                            "Optimized source rediscovery changed inventory or failed to retain the rewarded network binding");
                        helper.succeed();
                    } finally {
                        initial.destroySelf();
                        granted.destroySelf();
                        fixture.close();
                    }
                });
            } catch (RuntimeException | AssertionError failure) {
                initial.destroySelf();
                granted.destroySelf();
                fixture.close();
                throw failure;
            }
        }

        private static void accessChange(GameTestHelper helper, boolean binding) {
            try (Cases.Fixture fixture = new Cases.Fixture(helper)) {
                ItemTask first = fixture.task(new ItemStack(Items.IRON_INGOT), 64);
                ItemTask next = fixture.task(new ItemStack(Items.IRON_INGOT), 128);
                ServerPlayer owner = binding ? fixture.player : new FakePlayer(helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "ftb-owner"));
                var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                    .createNewNetForPlayer(owner, 10000L, 64);
                helper.assertTrue(network != null, "Real BD optimized count fixture could not create its network");
                try {
                    if (!binding) network.addPlayer(fixture.player.getUUID());
                    com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                        .setPrimaryNetForPlayer(fixture.player, network);
                    var key = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(
                        new ItemStack(Items.IRON_INGOT));
                    helper.assertTrue(network.getUnifiedStorage().insert(key, 64, false).isEmpty(),
                        "Real BD optimized count fixture failed to store materials");
                    List<StorageSource> sources = StorageSources.find(fixture.player);
                    helper.assertTrue(sources.size() == 1 && sources.get(0).sourceId() == StorageSource.BEYOND_DIMENSIONS,
                        "Optimized access regression did not discover its real BD source");
                    fixture.reward(first, player -> {
                        if (binding) com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                            .setPrimaryNetForPlayer(player, null);
                        else network.removePlayer(player.getUUID());
                    });
                    fixture.detect(List.of(first, next), sources);
                    helper.assertTrue(fixture.data.isCompleted(first) && fixture.data.getProgress(next) == 0,
                        "Prepared BD counting retained stock after a reward changed primary binding or network membership");
                    helper.assertTrue(network.getUnifiedStorage().getStackByKey(key).amount() == 64,
                        "Prepared read-only BD counting changed inventory when source access was revoked");
                } finally {
                    network.destroySelf();
                }
            }
        }
    }
}

package dev.airun.storagefill.compat.ftb;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.StorageFill;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.ftb.mods.ftblibrary.config.Tristate;
import dev.ftb.mods.ftbquests.net.ClaimAllRewardsMessage;
import dev.ftb.mods.ftbquests.net.ClaimChoiceRewardMessage;
import dev.ftb.mods.ftbquests.net.ClaimRewardMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.loot.RewardTable;
import dev.ftb.mods.ftbquests.quest.loot.WeightedReward;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.event.PlayerLoggedInAfterTeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class FtbManualRepeatGameTests {
    @GameTest(template = "empty", batch = "ftb_manual_repeat_single", timeoutTicks = 190)
    public static void singleManualClaimRechecksTheNextStoredMaterialRoundOnce(GameTestHelper helper) {
        if (available(helper)) Cases.manual(helper, Cases.Mode.SINGLE);
    }

    @GameTest(template = "empty", batch = "ftb_manual_repeat_all", timeoutTicks = 190)
    public static void claimAllRechecksTheNextStoredMaterialRoundOnce(GameTestHelper helper) {
        if (available(helper)) Cases.manual(helper, Cases.Mode.ALL);
    }

    @GameTest(template = "empty", batch = "ftb_manual_repeat_choice", timeoutTicks = 190)
    public static void choiceManualClaimRechecksTheNextStoredMaterialRoundOnce(GameTestHelper helper) {
        if (available(helper)) Cases.manual(helper, Cases.Mode.CHOICE);
    }

    @GameTest(template = "empty", batch = "ftb_manual_repeat_multiple", timeoutTicks = 210)
    public static void remainingManualRewardsPreventResetUntilAllHaveBeenClaimed(GameTestHelper helper) {
        if (available(helper)) Cases.multiple(helper);
    }

    @GameTest(template = "empty", batch = "ftb_manual_repeat_cooldown", timeoutTicks = 1000000)
    public static void manualRepeatWaitsForNativeCooldownThenChecksStoredStockOnce(GameTestHelper helper) {
        if (available(helper)) Cases.cooldown(helper);
    }

    private static boolean available(GameTestHelper helper) {
        if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbteams")
            && ModList.get().isLoaded("beyonddimensions")) return true;
        StorageFill.LOGGER.warn("SKIPPED FTB manual repeat GameTest: FTB Quests, FTB Teams, or Beyond Dimensions is absent");
        helper.succeed();
        return false;
    }

    private static final class Cases {
        private static final AtomicLong IDS = new AtomicLong(0x6D00000000000000L);
        private enum Mode { SINGLE, ALL, CHOICE }

        private static void manual(GameTestHelper helper, Mode mode) {
            Fixture fixture = new Fixture(helper, mode == Mode.CHOICE, 1, 0);
            fixture.start();
            helper.runAtTickTime(40, () -> fixture.checked(() -> {
                fixture.ready(helper, 0, 0);
                helper.assertTrue(fixture.idle(), "Initial manual-repeat warehouse detection was still active");
                fixture.claim(mode, 0);
                fixture.reset(helper, 1, 1);
                fixture.claim(mode, 0);
                fixture.reset(helper, 1, 1);
            }));
            helper.runAtTickTime(100, () -> fixture.checked(() -> {
                fixture.ready(helper, 1, 1);
                fixture.claim(mode, 0);
                fixture.reset(helper, 2, 2);
            }));
            helper.runAtTickTime(160, () -> fixture.checked(() -> {
                try {
                    fixture.ready(helper, 2, 2);
                    helper.succeed();
                } finally {
                    fixture.close();
                }
            }));
        }

        private static void multiple(GameTestHelper helper) {
            Fixture fixture = new Fixture(helper, false, 2, 0);
            fixture.start();
            helper.runAtTickTime(40, () -> fixture.checked(() -> {
                fixture.ready(helper, 0, 0);
                helper.assertTrue(fixture.idle(), "Initial multiple-reward warehouse detection was still active");
                fixture.claim(Mode.SINGLE, 0);
                helper.assertTrue(fixture.data.isCompleted(fixture.quest) && fixture.data.getProgress(fixture.task) == 8
                    && fixture.data.getCompletionCount(fixture.quest) == 0 && fixture.rewardsGiven() == 1
                    && fixture.data.isRewardClaimed(fixture.player.getUUID(), fixture.rewards[0])
                    && !fixture.data.isRewardClaimed(fixture.player.getUUID(), fixture.rewards[1]),
                    "Claiming only one reward reset a quest whose other manual reward was unclaimed");
            }));
            helper.runAtTickTime(80, () -> fixture.checked(() -> {
                helper.assertTrue(fixture.data.isCompleted(fixture.quest) && fixture.data.getProgress(fixture.task) == 8
                    && fixture.data.getCompletionCount(fixture.quest) == 0 && fixture.rewardsGiven() == 1,
                    "An incomplete set of manual rewards caused a delayed repeat reset or automatic reward");
                fixture.claim(Mode.SINGLE, 1);
                fixture.reset(helper, 1, 2);
            }));
            helper.runAtTickTime(150, () -> fixture.checked(() -> {
                try {
                    fixture.ready(helper, 1, 2);
                    helper.succeed();
                } finally {
                    fixture.close();
                }
            }));
        }

        private static void cooldown(GameTestHelper helper) {
            Fixture fixture = new Fixture(helper, false, 1, 2);
            fixture.start();
            helper.runAtTickTime(40, () -> fixture.checked(() -> {
                fixture.ready(helper, 0, 0);
                helper.assertTrue(fixture.idle(), "Initial cooldown warehouse detection was still active");
                long deadline = System.nanoTime() + 8_000_000_000L;
                fixture.claim(Mode.SINGLE, 0);
                fixture.reset(helper, 1, 1);
                helper.assertTrue(fixture.data.getMilliSecondsUntilRepeatable(fixture.quest) > 0,
                    "The real FTB positive repeat cooldown did not start");
                waitForCooldown(helper, fixture, deadline);
            }));
        }

        private static void waitForCooldown(GameTestHelper helper, Fixture fixture, long deadline) {
            fixture.checked(() -> {
                helper.assertTrue(System.nanoTime() < deadline, "Native repeat cooldown did not expire within eight real seconds");
                if (fixture.data.getMilliSecondsUntilRepeatable(fixture.quest) > 0) {
                    fixture.reset(helper, 1, 1);
                    helper.runAfterDelay(1, () -> waitForCooldown(helper, fixture, deadline));
                    return;
                }
                helper.runAfterDelay(40, () -> fixture.checked(() -> {
                    fixture.ready(helper, 1, 1);
                    helper.runAfterDelay(40, () -> fixture.checked(() -> {
                        try {
                            fixture.ready(helper, 1, 1);
                            helper.succeed();
                        } finally {
                            fixture.close();
                        }
                    }));
                }));
            });
        }

        private static final class Fixture implements AutoCloseable {
            private final ServerQuestFile file;
            private final Chapter chapter;
            private final Quest quest;
            private final ItemTask task;
            private final Reward[] rewards;
            private final ServerPlayer player;
            private final TeamData data;
            private final ItemStack rewardPrototype;
            private final com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet network;
            private final com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey key;
            private List<ServerPlayer> onlinePlayers;
            private Map<UUID, ServerPlayer> onlineById;
            private boolean closed;

            private Fixture(GameTestHelper helper, boolean choice, int rewardCount, int cooldown) {
                file = ServerQuestFile.INSTANCE;
                helper.assertTrue(file != null, "Manual-repeat fixture has no FTB server quest file");
                player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "ftb-man-repeat"));
                player.connection = new ServerGamePacketListenerImpl(player.server,
                    new Connection(PacketFlow.SERVERBOUND), player);
                var manager = (TeamManagerImpl) FTBTeamsAPI.api().getManager();
                manager.playerLoggedIn(null, player.getUUID(), player.getGameProfile().getName());
                data = TeamData.get(player);
                helper.assertTrue(data != null && !data.isLocked(), "Manual-repeat fixture has no unlocked team");
                chapter = new Chapter(IDS.incrementAndGet(), file, file.getDefaultChapterGroup());
                file.getDefaultChapterGroup().addChapter(chapter);
                quest = new Quest(IDS.incrementAndGet(), chapter);
                chapter.addQuest(quest);
                CompoundTag config = new CompoundTag();
                quest.writeData(config);
                Tristate.TRUE.write(config, "can_repeat");
                config.putInt("repeat_cooldown", cooldown);
                quest.readData(config);
                task = new ItemTask(IDS.incrementAndGet(), quest);
                task.setStackAndCount(new ItemStack(Items.OAK_LOG), 8);
                task.setConsumeItems(Tristate.FALSE);
                quest.addTask(task);
                rewardPrototype = new ItemStack(Items.DIAMOND);
                rewardPrototype.getOrCreateTag().putString("manual_repeat_reward", player.getUUID().toString());
                rewards = new Reward[rewardCount];
                for (int index = 0; index < rewardCount; index++) {
                    Reward reward;
                    if (choice) {
                        RewardTable table = new RewardTable(-1L, file);
                        table.addReward(new WeightedReward(new ItemReward(IDS.incrementAndGet(), table.getFakeQuest(),
                            rewardPrototype.copy(), 1), 1));
                        ChoiceReward selectable = new ChoiceReward(IDS.incrementAndGet(), quest);
                        selectable.setTable(table);
                        reward = selectable;
                    } else {
                        reward = new ItemReward(IDS.incrementAndGet(), quest, rewardPrototype.copy(), 1);
                    }
                    CompoundTag rewardConfig = new CompoundTag();
                    reward.writeData(rewardConfig);
                    rewardConfig.putString("auto", "disabled");
                    reward.readData(rewardConfig);
                    rewards[index] = reward;
                    quest.addReward(reward);
                }
                file.refreshIDMap();
                file.clearCachedData();
                network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                    .createNewNetForPlayer(player, 10000L, 64);
                helper.assertTrue(network != null, "Manual-repeat fixture failed to create the real BD network");
                key = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(new ItemStack(Items.OAK_LOG));
                helper.assertTrue(network.getUnifiedStorage().insert(key, 64, false).isEmpty(),
                    "Manual-repeat fixture failed to preload the BD oak logs");
            }

            @SuppressWarnings("unchecked")
            private void start() {
                checked(() -> {
                    try {
                        PlayerList list = player.server.getPlayerList();
                        Field playersField = PlayerList.class.getDeclaredField("players");
                        playersField.setAccessible(true);
                        Field byIdField = PlayerList.class.getDeclaredField("playersByUUID");
                        byIdField.setAccessible(true);
                        onlinePlayers = (List<ServerPlayer>) playersField.get(list);
                        onlineById = (Map<UUID, ServerPlayer>) byIdField.get(list);
                        onlinePlayers.add(player);
                        onlineById.put(player.getUUID(), player);
                    } catch (ReflectiveOperationException error) {
                        throw new AssertionError("Unable to register the real manual-repeat fixture player online", error);
                    }
                    var team = FTBTeamsAPI.api().getManager().getTeamForPlayer(player).orElseThrow();
                    TeamEvent.PLAYER_LOGGED_IN.invoker().accept(new PlayerLoggedInAfterTeamEvent(team, player));
                });
            }

            private void claim(Mode mode, int index) {
                switch (mode) {
                    case SINGLE -> new ClaimRewardMessage(rewards[index].id, false).handle(context());
                    case ALL -> new ClaimAllRewardsMessage().handle(context());
                    case CHOICE -> new ClaimChoiceRewardMessage(rewards[index].id, 0).handle(context());
                }
            }

            private void ready(GameTestHelper helper, int cycles, int items) {
                helper.assertTrue(data.isCompleted(quest) && data.isCompleted(task) && data.getProgress(task) == 8
                    && data.getCompletionCount(quest) == cycles && rewardsGiven() == items,
                    "Stored stock did not complete exactly one unclaimed next round after manual reward collection");
                for (Reward reward : rewards) helper.assertFalse(data.isRewardClaimed(player.getUUID(), reward),
                    "Next repeat round automatically claimed a manual reward or retained the preceding round's ledger");
                unchangedStock(helper);
            }

            private void reset(GameTestHelper helper, int cycles, int items) {
                helper.assertTrue(!data.isCompleted(quest) && !data.isCompleted(task) && data.getProgress(task) == 0
                    && data.getCompletionCount(quest) == cycles && rewardsGiven() == items,
                    "Manual reward collection did not reset one repeat cycle or issued its reward more than once");
                unchangedStock(helper);
            }

            private void unchangedStock(GameTestHelper helper) {
                long amount = network.getUnifiedStorage().getStackByKey(key).amount();
                int carried = player.getInventory().items.stream().filter(stack -> stack.is(Items.OAK_LOG))
                    .mapToInt(ItemStack::getCount).sum();
                helper.assertTrue(amount == 64 && carried == 0,
                    "Manual repeat changed oak-log storage: network=" + amount + ", player=" + carried + ", closed=" + closed);
            }

            private int rewardsGiven() {
                StackKey expected = StackKey.of(rewardPrototype);
                return player.getInventory().items.stream().filter(stack -> !stack.isEmpty()
                    && StackKey.of(stack).equals(expected)).mapToInt(ItemStack::getCount).sum();
            }

            private boolean idle() {
                try {
                    Field pending = FtbDetectionQueue.class.getDeclaredField("PENDING");
                    pending.setAccessible(true);
                    Field active = FtbDetectionQueue.class.getDeclaredField("ACTIVE_PLAYERS");
                    active.setAccessible(true);
                    return !((Map<?, ?>) pending.get(null)).containsKey(player.getUUID())
                        && !((Map<?, ?>) active.get(null)).containsKey(player.getUUID());
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("Unable to confirm the initial repeat detection became idle", error);
                }
            }

            private NetworkManager.PacketContext context() {
                return new NetworkManager.PacketContext() {
                    public Player getPlayer() { return player; }
                    public void queue(Runnable runnable) { runnable.run(); }
                    public Env getEnvironment() { return Env.SERVER; }
                };
            }

            private void checked(Runnable action) {
                if (closed) return;
                try {
                    action.run();
                } catch (RuntimeException | AssertionError failure) {
                    close();
                    throw failure;
                }
            }

            public void close() {
                if (closed) return;
                closed = true;
                try {
                    network.destroySelf();
                } finally {
                    if (onlinePlayers != null) {
                        MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
                        onlinePlayers.remove(player);
                        onlineById.remove(player.getUUID(), player);
                    }
                    file.getDefaultChapterGroup().removeChapter(chapter);
                    file.refreshIDMap();
                    file.clearCachedData();
                }
            }
        }
    }
}

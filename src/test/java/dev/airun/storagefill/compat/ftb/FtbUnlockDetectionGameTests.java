package dev.airun.storagefill.compat.ftb;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StorageFill;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.ftb.mods.ftblibrary.config.Tristate;
import dev.ftb.mods.ftbquests.net.SubmitTaskMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.CheckmarkTask;
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
public final class FtbUnlockDetectionGameTests {
    @GameTest(template = "empty", batch = "ftb_unlock_manual", timeoutTicks = 140)
    public static void nativeCheckmarkCompletionRechecksPreexistingDependentNetworkStock(GameTestHelper helper) {
        if (available(helper)) Cases.run(helper, false);
    }

    @GameTest(template = "empty", batch = "ftb_unlock_manual", timeoutTicks = 140)
    public static void nativeCheckmarkCompletionRechecksNextSequentialNetworkItemTask(GameTestHelper helper) {
        if (available(helper)) Cases.run(helper, true);
    }

    private static boolean available(GameTestHelper helper) {
        if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbteams")
            && ModList.get().isLoaded("beyonddimensions")) return true;
        StorageFill.LOGGER.warn("SKIPPED FTB unlock detection GameTest: FTB Quests, FTB Teams, or Beyond Dimensions is absent");
        helper.succeed();
        return false;
    }

    private static final class Cases {
        private static final AtomicLong IDS = new AtomicLong(0x6C00000000000000L);

        private static void run(GameTestHelper helper, boolean sequential) {
            Fixture fixture = new Fixture(helper, sequential);
            try {
                fixture.online();
                var team = FTBTeamsAPI.api().getManager().getTeamForPlayer(fixture.player).orElseThrow();
                TeamEvent.PLAYER_LOGGED_IN.invoker().accept(new PlayerLoggedInAfterTeamEvent(team, fixture.player));
                helper.runAtTickTime(40, () -> {
                    try {
                        helper.assertTrue(fixture.idle(), "Initial warehouse detection did not become idle before manual completion");
                        helper.assertTrue(!fixture.data.isCompleted(fixture.checkmark)
                            && fixture.data.getProgress(fixture.material) == 0 && fixture.stored() == 64,
                            "Preloaded material was consumed or credited before its native prerequisite was completed");
                        helper.assertTrue(fixture.player.getInventory().items.stream().noneMatch(stack -> stack.is(Items.GOLD_INGOT)),
                            "Unlock fixture accidentally supplied matching materials through player inventory");
                        new SubmitTaskMessage(fixture.checkmark.id).handle(fixture.context());
                        helper.assertTrue(fixture.data.isCompleted(fixture.checkmark)
                            && fixture.data.canStartTasks(fixture.material.getQuest()),
                            "Original FTB checkmark submission did not unlock the target task");
                    } catch (RuntimeException | AssertionError failure) {
                        fixture.close();
                        throw failure;
                    }
                });
                helper.runAtTickTime(100, () -> {
                    try {
                        helper.assertTrue(fixture.data.isCompleted(fixture.material)
                            && fixture.data.getProgress(fixture.material) == 64,
                            sequential ? "Completing the native checkmark did not detect stock for the next sequential item task"
                                : "Completing the native prerequisite checkmark did not detect stock for the newly unlocked quest");
                        helper.assertTrue(fixture.stored() == 64,
                            "Natural unlock detection consumed the preexisting network materials");
                        helper.succeed();
                    } finally {
                        fixture.close();
                    }
                });
            } catch (RuntimeException | AssertionError failure) {
                fixture.close();
                throw failure;
            }
        }

        private static final class Fixture implements AutoCloseable {
            private final ServerQuestFile file;
            private final Chapter chapter;
            private final CheckmarkTask checkmark;
            private final ItemTask material;
            private final ServerPlayer player;
            private final TeamData data;
            private final com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet network;
            private final com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey key;
            private List<ServerPlayer> onlinePlayers;
            private Map<UUID, ServerPlayer> onlineById;
            private boolean closed;

            private Fixture(GameTestHelper helper, boolean sequential) {
                file = ServerQuestFile.INSTANCE;
                helper.assertTrue(file != null, "FTB unlock fixture has no server quest file");
                player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "ftb-unlock"));
                player.connection = new ServerGamePacketListenerImpl(player.server,
                    new Connection(PacketFlow.SERVERBOUND), player);
                var manager = (TeamManagerImpl) FTBTeamsAPI.api().getManager();
                manager.playerLoggedIn(null, player.getUUID(), player.getGameProfile().getName());
                data = TeamData.get(player);
                helper.assertTrue(data != null && !data.isLocked(), "FTB unlock fixture has no unlocked player team");
                chapter = new Chapter(IDS.incrementAndGet(), file, file.getDefaultChapterGroup());
                file.getDefaultChapterGroup().addChapter(chapter);
                Quest prerequisite = new Quest(IDS.incrementAndGet(), chapter);
                chapter.addQuest(prerequisite);
                if (sequential) {
                    CompoundTag config = new CompoundTag();
                    prerequisite.writeData(config);
                    Tristate.TRUE.write(config, "require_sequential_tasks");
                    prerequisite.readData(config);
                }
                checkmark = new CheckmarkTask(IDS.incrementAndGet(), prerequisite);
                prerequisite.addTask(checkmark);
                Quest target = prerequisite;
                if (!sequential) {
                    target = new Quest(IDS.incrementAndGet(), chapter);
                    chapter.addQuest(target);
                    target.addDependency(prerequisite);
                }
                material = new ItemTask(IDS.incrementAndGet(), target);
                material.setStackAndCount(new ItemStack(Items.GOLD_INGOT), 64);
                material.setConsumeItems(Tristate.FALSE);
                target.addTask(material);
                file.refreshIDMap();
                file.clearCachedData();
                network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                    .createNewNetForPlayer(player, 10000L, 64);
                helper.assertTrue(network != null, "FTB unlock fixture failed to create the real BD network");
                key = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(new ItemStack(Items.GOLD_INGOT));
                helper.assertTrue(network.getUnifiedStorage().insert(key, 64, false).isEmpty()
                    && stored() == 64, "FTB unlock fixture failed to preload its network materials");
            }

            @SuppressWarnings("unchecked")
            private void online() {
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
                    throw new AssertionError("Unable to register the isolated unlock fixture as an online player", error);
                }
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
                    throw new AssertionError("Unable to confirm the initial storage detection became idle", error);
                }
            }

            private long stored() { return network.getUnifiedStorage().getStackByKey(key).amount(); }

            private NetworkManager.PacketContext context() {
                return new NetworkManager.PacketContext() {
                    public Player getPlayer() { return player; }
                    public void queue(Runnable runnable) { runnable.run(); }
                    public Env getEnvironment() { return Env.SERVER; }
                };
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

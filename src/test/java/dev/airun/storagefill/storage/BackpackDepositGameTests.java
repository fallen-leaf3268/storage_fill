package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StorageFill;
import dev.ftb.mods.ftblibrary.config.Tristate;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.event.PlayerLoggedInAfterTeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class BackpackDepositGameTests {
    @GameTest(template = "empty", batch = "storage_sb_manual_hook")
    public static void nativeBackpackSlotSafeInsertRecordsActualDeposit(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.manualBridge(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "storage_sb_native_control")
    public static void nativeBackpackInventoryInsertRecordsActualDeposit(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.nativeBridge(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "storage_sb_manual_ftb", timeoutTicks = 140)
    public static void manualBackpackDepositCompletesMergedCurrentStockTask(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.manualQuest(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_owner_filter")
    public static void nativeCoreInventoryWithAnotherOwnerDoesNotRecordBackpackDeposits(GameTestHelper helper)
            throws ReflectiveOperationException {
        if (!installed(helper)) return;
        Cases.otherOwner(helper);
        helper.succeed();
    }

    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbteams")
            && ModList.get().isLoaded("sophisticatedbackpacks")) return true;
        StorageFill.LOGGER.warn("SKIPPED backpack manual deposit audit: FTB or Sophisticated Backpacks is absent");
        helper.succeed();
        return false;
    }

    private static final class Cases {
        private static final AtomicLong IDS = new AtomicLong(0x7500000000000000L);

        private static IBackpackWrapper wrapper(ItemStack stack) {
            return stack.getCapability(CapabilityBackpackWrapper.getCapabilityInstance()).orElseThrow(IllegalStateException::new);
        }

        private static void nativeBridge(GameTestHelper helper) {
            ItemStack backpack = new ItemStack(ModItems.BACKPACK.get());
            var handler = wrapper(backpack).getInventoryHandler();
            StorageChanges.clear();
            ItemStack remainder = handler.insertItem(0, new ItemStack(Items.IRON_INGOT, 64), false);
            helper.assertTrue(remainder.isEmpty() && handler.getStackInSlot(0).getCount() == 64,
                "Native insertion control did not actually store sixty-four materials");
            StorageChanges.Batch batch = StorageChanges.drain();
            helper.assertTrue(batch.changes().size() == 1 && batch.changes().get(0).identity() == handler
                && batch.changes().get(0).sourceId() == StorageSource.SOPHISTICATED_BACKPACKS
                && batch.changes().get(0).items().equals(Set.of(Items.IRON_INGOT)),
                "Native inventory insertion control did not record its successful backpack deposit");
        }

        private static void manualBridge(GameTestHelper helper) {
            ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "sb-slot-audit"));
            ItemStack backpack = new ItemStack(ModItems.BACKPACK.get());
            IBackpackWrapper wrapper = wrapper(backpack);
            player.getInventory().setItem(0, backpack);
            StorageInventorySlot slot = new StorageInventorySlot(false, wrapper, 0, player);
            StorageChanges.clear();
            ItemStack remainder = slot.safeInsert(new ItemStack(Items.IRON_INGOT, 64), 64);
            helper.assertTrue(remainder.isEmpty() && wrapper.getInventoryHandler().getStackInSlot(0).getCount() == 64,
                "Native manual storage-slot insertion did not actually store sixty-four materials");
            StorageChanges.Batch batch = StorageChanges.drain();
            helper.assertTrue(batch.changes().size() == 1
                && batch.changes().get(0).identity() == wrapper.getInventoryHandler()
                && batch.changes().get(0).sourceId() == StorageSource.SOPHISTICATED_BACKPACKS
                && batch.changes().get(0).items().equals(Set.of(Items.IRON_INGOT)),
                "Actual backpack GUI safeInsert changed warehouse stock without recording a deposit");
            var handler = wrapper.getInventoryHandler();
            handler.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 16));
            helper.assertFalse(StorageChanges.hasChanges(), "Backpack GUI material decrease recorded an insertion");
            helper.assertTrue(slot.safeInsert(new ItemStack(Items.IRON_INGOT, 32), 32).isEmpty()
                && handler.getStackInSlot(0).getCount() == 48, "Backpack GUI merge did not preserve its actual materials");
            StorageChanges.Batch merged = StorageChanges.drain();
            helper.assertTrue(merged.changes().size() == 1 && merged.changes().get(0).identity() == handler
                && merged.changes().get(0).items().equals(Set.of(Items.IRON_INGOT)),
                "Backpack GUI merge did not record the existing stack's actual increase");
            handler.setStackInSlot(0, handler.getStackInSlot(0).copy());
            helper.assertFalse(StorageChanges.hasChanges(), "Replacing an unchanged backpack stack recorded an insertion");
            handler.setStackInSlot(0, ItemStack.EMPTY);
            helper.assertFalse(StorageChanges.hasChanges(), "Removing a backpack stack recorded an insertion");
            handler.insertItem(0, new ItemStack(Items.IRON_INGOT, 32), true);
            helper.assertFalse(StorageChanges.hasChanges(), "Backpack native simulated insertion recorded an actual increase");
        }

        private static void otherOwner(GameTestHelper helper) throws ReflectiveOperationException {
            ItemStack backpack = new ItemStack(ModItems.BACKPACK.get());
            IBackpackWrapper wrapper = wrapper(backpack);
            var handler = wrapper.getInventoryHandler();
            Class<?> ownerType = net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper.class;
            Object owner = java.lang.reflect.Proxy.newProxyInstance(ownerType.getClassLoader(), new Class<?>[] { ownerType },
                (proxy, method, arguments) -> method.invoke(wrapper, arguments));
            var ownerField = net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler.class.getDeclaredField("storageWrapper");
            ownerField.setAccessible(true);
            Object original = ownerField.get(handler);
            ownerField.set(handler, owner);
            try {
                StorageChanges.clear();
                helper.assertTrue(handler.insertItem(0, new ItemStack(Items.IRON_INGOT, 16), false).isEmpty(),
                    "Non-backpack Core owner control did not accept native insertion");
                handler.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 32));
                helper.assertTrue(handler.getStackInSlot(0).getCount() == 16 && handler.getStackInSlot(1).getCount() == 32,
                    "Non-backpack owner filtering changed the native stored materials");
                helper.assertFalse(StorageChanges.hasChanges(), "Unrelated Sophisticated Core inventory recorded backpack detection changes");
            } finally {
                ownerField.set(handler, original);
                StorageChanges.clear();
            }
        }

        private static void manualQuest(GameTestHelper helper) {
            QuestFixture fixture = new QuestFixture(helper);
            try {
                ItemStack backpack = new ItemStack(ModItems.BACKPACK.get());
                IBackpackWrapper wrapper = wrapper(backpack);
                helper.assertTrue(wrapper.getInventoryHandler().insertItem(0, new ItemStack(Items.IRON_INGOT, 64), false).isEmpty(),
                    "Manual deposit task audit could not seed its existing backpack stock");
                fixture.player.getInventory().setItem(0, backpack);
                fixture.player.getInventory().setItem(1, new ItemStack(Items.IRON_INGOT, 64));
                StorageChanges.clear();
                fixture.online();
                var team = FTBTeamsAPI.api().getManager().getTeamForPlayer(fixture.player).orElseThrow();
                TeamEvent.PLAYER_LOGGED_IN.invoker().accept(new PlayerLoggedInAfterTeamEvent(team, fixture.player));
                helper.runAtTickTime(30, () -> {
                    try {
                        helper.assertTrue(fixture.data.getProgress(fixture.task) == 64 && !fixture.data.isCompleted(fixture.task),
                            "Manual deposit task audit did not establish the initial separate sixty-four-item views");
                        ItemStack moved = fixture.player.getInventory().getItem(1);
                        fixture.player.getInventory().setItem(1, ItemStack.EMPTY);
                        StorageInventorySlot slot = new StorageInventorySlot(false, wrapper, 1, fixture.player);
                        helper.assertTrue(slot.safeInsert(moved, 64).isEmpty(),
                            "Manual deposit task audit failed to move its carried materials through the native GUI slot");
                        helper.assertTrue(wrapper.getInventoryHandler().getStackInSlot(0).getCount() == 64
                            && wrapper.getInventoryHandler().getStackInSlot(1).getCount() == 64,
                            "Manual deposit task audit did not combine sixty-four plus sixty-four current materials");
                    } catch (RuntimeException | AssertionError failure) {
                        fixture.close();
                        throw failure;
                    }
                });
                helper.runAtTickTime(100, () -> {
                    try {
                        helper.assertTrue(fixture.data.getProgress(fixture.task) == 128 && fixture.data.isCompleted(fixture.task),
                            "The natural FTB queue did not detect the current 128 materials after a real manual backpack GUI deposit");
                        helper.assertTrue(wrapper.getInventoryHandler().getStackInSlot(0).getCount() == 64
                            && wrapper.getInventoryHandler().getStackInSlot(1).getCount() == 64,
                            "Read-only manual backpack task detection changed the stored materials");
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

        private static final class QuestFixture implements AutoCloseable {
            private final ServerQuestFile file;
            private final Chapter chapter;
            private final ServerPlayer player;
            private final TeamData data;
            private final ItemTask task;
            private List<ServerPlayer> onlinePlayers;
            private Map<UUID, ServerPlayer> onlineById;
            private boolean closed;

            private QuestFixture(GameTestHelper helper) {
                file = ServerQuestFile.INSTANCE;
                helper.assertTrue(file != null, "Manual deposit audit requires a real FTB server quest file");
                UUID id = UUID.randomUUID();
                player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(id, "sb-ftb-audit"));
                player.connection = new ServerGamePacketListenerImpl(player.server, new Connection(PacketFlow.SERVERBOUND), player);
                ((TeamManagerImpl) FTBTeamsAPI.api().getManager()).playerLoggedIn(null, id, player.getGameProfile().getName());
                data = TeamData.get(player);
                helper.assertTrue(data != null, "Manual deposit audit could not create a real FTB team");
                chapter = new Chapter(IDS.incrementAndGet(), file, file.getDefaultChapterGroup());
                file.getDefaultChapterGroup().addChapter(chapter);
                Quest quest = new Quest(IDS.incrementAndGet(), chapter);
                chapter.addQuest(quest);
                task = new ItemTask(IDS.incrementAndGet(), quest);
                task.setStackAndCount(new ItemStack(Items.IRON_INGOT), 128);
                task.setConsumeItems(Tristate.FALSE);
                quest.addTask(task);
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
                    throw new AssertionError("Manual deposit audit could not register its isolated online player", failure);
                }
            }

            public void close() {
                if (closed) return;
                closed = true;
                if (onlinePlayers != null) {
                    onlinePlayers.remove(player);
                    onlineById.remove(player.getUUID(), player);
                }
                file.getDefaultChapterGroup().removeChapter(chapter);
                file.refreshIDMap();
                file.clearCachedData();
                StorageChanges.clear();
            }
        }
    }
}

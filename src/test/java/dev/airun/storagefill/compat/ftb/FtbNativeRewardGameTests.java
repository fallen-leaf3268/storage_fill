package dev.airun.storagefill.compat.ftb;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.storage.StorageSource;
import dev.airun.storagefill.storage.StorageSources;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class FtbNativeRewardGameTests {
    @GameTest(template = "empty", batch = "ftb_native_reward_bd")
    public static void realBdRewardPreservesNbtAndClaimOnceWithoutFillingOtherProviders(GameTestHelper helper) {
        if (installed(helper, "beyonddimensions")) Cases.bd(helper, 0);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_bd")
    public static void partiallyFullRealBdRewardGivesOnlyUnstoredRemainder(GameTestHelper helper) {
        if (installed(helper, "beyonddimensions")) Cases.bd(helper, 1);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_bd")
    public static void revokedRealBdMembershipFallsBackWithoutChangingNetwork(GameTestHelper helper) {
        if (installed(helper, "beyonddimensions")) Cases.bd(helper, 2);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_rs", timeoutTicks = 180)
    public static void realRsRewardPreservesNbtAndChargesWirelessEnergy(GameTestHelper helper) {
        if (installed(helper, "refinedstorage")) Cases.rs(helper, 0);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_rs", timeoutTicks = 180)
    public static void partiallyFullRealRsRewardGivesOnlyUnstoredRemainder(GameTestHelper helper) {
        if (installed(helper, "refinedstorage")) Cases.rs(helper, 1);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_rs", timeoutTicks = 180)
    public static void realRsInsertOnlySecurityCardStillAcceptsItemRewards(GameTestHelper helper) {
        if (installed(helper, "refinedstorage")) Cases.rs(helper, 2);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_rs", timeoutTicks = 180)
    public static void realRsDeniedInsertionFallsBackWithoutBypassingSecurity(GameTestHelper helper) {
        if (installed(helper, "refinedstorage")) Cases.rs(helper, 3);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_rs", timeoutTicks = 180)
    public static void realRsDepletedTerminalFallsBackWithoutInsertingItems(GameTestHelper helper) {
        if (installed(helper, "refinedstorage")) Cases.rs(helper, 4);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_rs", timeoutTicks = 180)
    public static void realRsOutOfRangeRewardFallsBackWithoutChargingTerminal(GameTestHelper helper) {
        if (installed(helper, "refinedstorage")) Cases.rs(helper, 5);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_ae", timeoutTicks = 180)
    public static void realAeRewardPreservesNbtAndChargesWirelessEnergy(GameTestHelper helper) {
        if (installed(helper, "ae2")) Cases.ae(helper, 0);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_ae", timeoutTicks = 180)
    public static void partiallyFullRealAeCellGivesOnlyUnstoredRewardRemainder(GameTestHelper helper) {
        if (installed(helper, "ae2")) Cases.ae(helper, 1);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_ae", timeoutTicks = 180)
    public static void realAeDepletedTerminalFallsBackWithoutInsertingItems(GameTestHelper helper) {
        if (installed(helper, "ae2")) Cases.ae(helper, 2);
    }

    @GameTest(template = "empty", batch = "ftb_native_reward_ae", timeoutTicks = 180)
    public static void realAeOutOfRangeRewardFallsBackWithoutChargingTerminal(GameTestHelper helper) {
        if (installed(helper, "ae2")) Cases.ae(helper, 3);
    }

    private static boolean installed(GameTestHelper helper, String mod) {
        if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbteams") && ModList.get().isLoaded(mod)) return true;
        StorageFill.LOGGER.warn("SKIPPED native FTB reward GameTest: FTB Quests, FTB Teams, or {} is absent", mod);
        helper.succeed();
        return false;
    }

    private static final class Cases {
        private static void bd(GameTestHelper helper, int mode) { BeyondDimensions.run(helper, mode); }
        private static void rs(GameTestHelper helper, int mode) { RefinedStorage.run(helper, mode); }
        private static void ae(GameTestHelper helper, int mode) { AppliedEnergistics.run(helper, mode); }

        private static ItemStack tagged(int variant, int count) {
            ItemStack stack = new ItemStack(Items.DIAMOND, count);
            stack.getOrCreateTag().putInt("native_reward_variant", variant);
            return stack;
        }

        private static int carried(ServerPlayer player, ItemStack prototype) {
            return player.getInventory().items.stream().filter(stack -> !stack.isEmpty()
                && StackKey.of(stack).equals(StackKey.of(prototype))).mapToInt(ItemStack::getCount).sum();
        }

        private static Block block(String mod, String name) {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(mod, name));
            if (block == null || block == Blocks.AIR) throw new AssertionError("Missing native reward fixture block " + mod + ":" + name);
            return block;
        }

        private static ServerPlayer player(GameTestHelper helper, String name) {
            ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), name));
            player.connection = new ServerGamePacketListenerImpl(player.server, new Connection(PacketFlow.SERVERBOUND), player);
            BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
            player.setPos(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
            return player;
        }

        private static final class Fixture implements AutoCloseable {
            private static final AtomicLong IDS = new AtomicLong(0x6A00000000000000L);
            private final GameTestHelper helper;
            private final ServerQuestFile file;
            private final Chapter chapter;
            private final Quest quest;
            private final ServerPlayer player;
            private final TeamData data;

            private Fixture(GameTestHelper helper) {
                this.helper = helper;
                file = ServerQuestFile.INSTANCE;
                helper.assertTrue(file != null, "Native reward fixture has no FTB server quest file");
                player = player(helper, "ftb-native-gift");
                ((TeamManagerImpl) FTBTeamsAPI.api().getManager()).playerLoggedIn(null,
                    player.getUUID(), player.getGameProfile().getName());
                data = TeamData.get(player);
                helper.assertTrue(data != null, "Native reward fixture has no real FTB team data");
                chapter = new Chapter(IDS.incrementAndGet(), file, file.getDefaultChapterGroup());
                file.getDefaultChapterGroup().addChapter(chapter);
                quest = new Quest(IDS.incrementAndGet(), chapter);
                chapter.addQuest(quest);
                ItemTask task = new ItemTask(IDS.incrementAndGet(), quest);
                task.setStackAndCount(new ItemStack(Items.IRON_INGOT), 1);
                quest.addTask(task);
                refresh();
                data.setProgress(task, 1);
                helper.assertTrue(data.isCompleted(quest), "Native reward fixture quest is not complete");
            }

            private void claim(ItemStack prototype, int count) {
                ItemReward reward = new ItemReward(IDS.incrementAndGet(), quest, prototype.copy(), count);
                quest.addReward(reward);
                refresh();
                data.claimReward(player, reward, false);
                data.claimReward(player, reward, false);
                helper.assertTrue(data.isRewardClaimed(player.getUUID(), reward), "Native FTB claim ledger was not recorded");
            }

            private void refresh() { file.refreshIDMap(); file.clearCachedData(); }

            public void close() {
                RewardPreferences.set(player, RewardDestination.INVENTORY);
                file.getDefaultChapterGroup().removeChapter(chapter);
                refresh();
            }
        }

        private static final class BeyondDimensions {
            private static void run(GameTestHelper helper, int mode) {
                try (Fixture fixture = new Fixture(helper)) {
                    ServerPlayer owner = player(helper, "ftb-bd-owner");
                    var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                        .createNewNetForPlayer(owner, mode == 1 ? 64 : 10000, mode == 1 ? 1 : 64);
                    helper.assertTrue(network != null && !network.deleted, "Native BD reward network was not created");
                    try {
                        network.addPlayer(fixture.player.getUUID());
                        com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.setPrimaryNetForPlayer(fixture.player, network);
                        helper.assertTrue(network.getPlayers().contains(fixture.player.getUUID())
                            && com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.getNetFromPlayer(fixture.player) == network,
                            "Native BD reward member did not receive authorized primary-network access");
                        ItemStack prototype = tagged(1, 1);
                        var key = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(prototype);
                        Runnable otherProvider = () -> { };
                        int seeded = mode == 1 ? 62 : 0;
                        if (seeded > 0) helper.assertTrue(network.getUnifiedStorage().insert(key, seeded, false).isEmpty(), "Native BD capacity seed failed");
                        if (mode == 0) {
                            var other = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(tagged(2, 1));
                            helper.assertTrue(network.getUnifiedStorage().insert(other, 19, false).isEmpty(), "Native BD alternate NBT seed failed");
                            if (ModList.get().isLoaded("sophisticatedbackpacks")) otherProvider = BackpackWitness.attach(helper, fixture.player, prototype);
                        }
                        if (mode == 2) {
                            network.removePlayer(fixture.player.getUUID());
                            helper.assertFalse(network.getPlayers().contains(fixture.player.getUUID()), "Native BD reward membership was not revoked");
                            helper.assertTrue(StorageSources.findForInsert(fixture.player, StorageSource.BEYOND_DIMENSIONS).isEmpty(), "Revoked BD member still discovered an insertion source");
                        }
                        RewardPreferences.set(fixture.player, RewardDestination.BEYOND_DIMENSIONS);
                        int reward = mode == 0 ? 75 : 10;
                        fixture.claim(prototype, reward);
                        long stored = network.getUnifiedStorage().getStackByKey(key).amount();
                        int local = carried(fixture.player, prototype);
                        helper.assertTrue(stored == (mode == 0 ? 75 : mode == 1 ? 64 : 0)
                            && local == (mode == 0 ? 0 : mode == 1 ? 8 : 10), "Native BD reward lost, duplicated, or misrouted exact NBT quantities");
                        helper.assertTrue(stored + local == seeded + reward, "Native BD reward broke conservation");
                        if (mode == 0) {
                            var other = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(tagged(2, 1));
                            helper.assertTrue(network.getUnifiedStorage().getStackByKey(other).amount() == 19, "Native BD reward merged another NBT variant");
                            otherProvider.run();
                        }
                        helper.succeed();
                    } finally { network.destroySelf(); }
                }
            }
        }

        private static final class BackpackWitness {
            private static Runnable attach(GameTestHelper helper, ServerPlayer player, ItemStack prototype) {
                ItemStack backpack = new ItemStack(net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems.BACKPACK.get());
                var handler = backpack.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper
                    .getCapabilityInstance()).orElseThrow(IllegalStateException::new).getInventoryHandler();
                player.getInventory().setItem(0, backpack);
                return () -> {
                    int stored = 0;
                    for (int slot = 0; slot < handler.getSlots(); slot++) {
                        ItemStack stack = handler.getStackInSlot(slot);
                        if (!stack.isEmpty() && StackKey.of(stack).equals(StackKey.of(prototype))) stored += stack.getCount();
                    }
                    helper.assertTrue(stored == 0, "Selecting BD also routed the same item reward into a carried backpack");
                };
            }
        }

        private static final class RefinedStorage {
            private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
            private static final BlockPos STORAGE = new BlockPos(0, 1, 1);
            private static final BlockPos TRANSMITTER = new BlockPos(2, 1, 1);
            private static final BlockPos SECURITY = new BlockPos(1, 1, 0);

            private static void run(GameTestHelper helper, int mode) {
                helper.setBlock(CONTROLLER, block("refinedstorage", "creative_controller"));
                helper.setBlock(STORAGE, block("refinedstorage", "64k_storage_block"));
                helper.setBlock(TRANSMITTER, block("refinedstorage", "wireless_transmitter"));
                if (mode == 2 || mode == 3) helper.setBlock(SECURITY, block("refinedstorage", "security_manager"));
                helper.runAfterDelay(60, () -> {
                    try (Fixture fixture = new Fixture(helper)) {
                        var controller = (com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity)
                            helper.getLevel().getBlockEntity(helper.absolutePos(CONTROLLER));
                        helper.assertTrue(controller != null, "Native RS reward controller is missing");
                        var network = controller.getNetwork();
                        helper.assertTrue(network.canRun() && network.getItemStorageCache().getStorages().size() == 1,
                            "Native RS reward fixture did not attach one active real 64k storage block");
                        BlockPos position = helper.absolutePos(CONTROLLER);
                        fixture.player.setPos(position.getX() + 0.5, position.getY() + 1.5, position.getZ() + 0.5);
                        ItemStack terminal = new ItemStack(com.refinedmods.refinedstorage.RSItems.WIRELESS_GRID.get());
                        var binding = terminal.getOrCreateTag();
                        binding.putInt("NodeX", position.getX()); binding.putInt("NodeY", position.getY()); binding.putInt("NodeZ", position.getZ());
                        binding.putString("Dimension", helper.getLevel().dimension().location().toString());
                        var energy = terminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(IllegalStateException::new);
                        energy.receiveEnergy(Integer.MAX_VALUE, false);
                        fixture.player.getInventory().setItem(0, terminal);
                        helper.assertTrue(StorageSources.findForInsert(fixture.player, StorageSource.REFINED_STORAGE).size() == 1,
                            "Native RS reward terminal did not discover its real network");
                        if (mode == 2 || mode == 3) {
                            var security = (com.refinedmods.refinedstorage.blockentity.SecurityManagerBlockEntity)
                                helper.getLevel().getBlockEntity(helper.absolutePos(SECURITY));
                            helper.assertTrue(security != null && security.getNode().isActive(), "Native RS reward security manager is inactive");
                            ItemStack card = new ItemStack(com.refinedmods.refinedstorage.RSItems.SECURITY_CARD.get());
                            card.getOrCreateTag().putString("Owner", fixture.player.getUUID().toString());
                            card.getOrCreateTag().putString("OwnerName", fixture.player.getGameProfile().getName());
                            for (var permission : com.refinedmods.refinedstorage.api.network.security.Permission.values()) {
                                com.refinedmods.refinedstorage.item.SecurityCardItem.setPermission(card, permission,
                                    permission == com.refinedmods.refinedstorage.api.network.security.Permission.MODIFY
                                    || mode == 2 && permission == com.refinedmods.refinedstorage.api.network.security.Permission.INSERT
                                    || mode == 3 && permission == com.refinedmods.refinedstorage.api.network.security.Permission.EXTRACT);
                            }
                            security.getNode().getCardsItems().setStackInSlot(0, card);
                            network.getSecurityManager().invalidate();
                            helper.assertTrue(network.getSecurityManager().hasPermission(com.refinedmods.refinedstorage.api.network.security.Permission.INSERT, fixture.player) == (mode == 2)
                                && network.getSecurityManager().hasPermission(com.refinedmods.refinedstorage.api.network.security.Permission.EXTRACT, fixture.player) == (mode == 3),
                                "Real RS security card did not establish asymmetric insert/extract permissions");
                            helper.assertTrue(StorageSources.findForInsert(fixture.player, StorageSource.REFINED_STORAGE).size() == (mode == 2 ? 1 : 0),
                                "RS insertion discovery used the wrong permission");
                            if (mode == 2) helper.assertTrue(StorageSources.find(fixture.player).stream().noneMatch(source -> source.sourceId() == StorageSource.REFINED_STORAGE),
                                "RS insert-only reward fixture accidentally retained extraction permission");
                        }
                        if (mode == 4) {
                            if (!com.refinedmods.refinedstorage.RS.SERVER_CONFIG.getWirelessGrid().getUseEnergy()) {
                                StorageFill.LOGGER.warn("SKIPPED native FTB RS depleted-terminal reward GameTest: wireless energy is disabled");
                                helper.succeed();
                                return;
                            }
                            energy.extractEnergy(Integer.MAX_VALUE, false);
                            helper.assertTrue(energy.getEnergyStored() == 0, "Native RS reward terminal was not depleted");
                        }
                        if (mode == 5) fixture.player.setPos(position.getX() + 10000.5, position.getY() + 1.5, position.getZ() + 0.5);
                        ItemStack prototype = tagged(1, 1);
                        int seeded = mode == 1 ? 63998 : 0;
                        if (seeded > 0) helper.assertTrue(network.insertItem(prototype.copyWithCount(seeded), seeded,
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty(), "Native RS reward capacity seed was rejected");
                        if (mode == 0) helper.assertTrue(network.insertItem(tagged(2, 19), 19,
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty(), "Native RS reward alternate NBT seed was rejected");
                        int before = energy.getEnergyStored();
                        RewardPreferences.set(fixture.player, RewardDestination.REFINED_STORAGE);
                        int reward = mode == 0 ? 75 : 10;
                        fixture.claim(prototype, reward);
                        int stored = network.getItemStorageCache().getList().getCount(prototype);
                        int local = carried(fixture.player, prototype);
                        int inserted = mode == 0 ? 75 : mode == 1 ? 2 : mode == 2 ? 10 : 0;
                        helper.assertTrue(stored == seeded + inserted && local == reward - inserted && stored + local == seeded + reward,
                            "Native RS reward lost, duplicated, or bypassed insertion access for exact NBT quantities");
                        helper.assertTrue(fixture.player.getInventory().getItem(0) == terminal, "Native RS reward replaced the carried wireless terminal");
                        var config = com.refinedmods.refinedstorage.RS.SERVER_CONFIG.getWirelessGrid();
                        int charged = config.getUseEnergy() && inserted > 0
                            ? ((reward + 63) / 64) * (Math.max(0, config.getOpenUsage()) + Math.max(0, config.getInsertUsage())) : 0;
                        helper.assertTrue(before - energy.getEnergyStored() == charged, "Native RS reward charged incorrect wireless insertion energy");
                        if (mode == 0) helper.assertTrue(network.getItemStorageCache().getList().getCount(tagged(2, 1)) == 19,
                            "Native RS reward merged another NBT variant");
                        helper.succeed();
                    } finally {
                        if (mode == 2 || mode == 3) helper.setBlock(SECURITY, Blocks.AIR);
                        helper.setBlock(TRANSMITTER, Blocks.AIR);
                        helper.setBlock(STORAGE, Blocks.AIR);
                        helper.setBlock(CONTROLLER, Blocks.AIR);
                    }
                });
            }
        }

        private static final class AppliedEnergistics {
            private static final BlockPos ENERGY = new BlockPos(1, 1, 1);
            private static final BlockPos ACCESS = new BlockPos(1, 1, 0);

            private static ItemStack item(String name) {
                var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", name));
                if (item == null || item == Items.AIR) throw new AssertionError("Missing native AE2 reward fixture item " + name);
                return new ItemStack(item);
            }

            private static void run(GameTestHelper helper, int mode) {
                helper.setBlock(ENERGY, block("ae2", "creative_energy_cell"));
                var state = block("ae2", "wireless_access_point").defaultBlockState();
                helper.setBlock(ACCESS, appeng.api.orientation.IOrientationStrategy.get(state).setFacing(state, Direction.NORTH));
                helper.runAfterDelay(60, () -> {
                    try (Fixture fixture = new Fixture(helper)) {
                        var access = (appeng.blockentity.networking.WirelessAccessPointBlockEntity)
                            helper.getLevel().getBlockEntity(helper.absolutePos(ACCESS));
                        helper.assertTrue(access != null && access.isActive(), "Native AE2 reward wireless access point is inactive");
                        var grid = access.getGrid();
                        helper.assertTrue(grid != null && grid.getEnergyService().isNetworkPowered(), "Native AE2 reward grid is unpowered");
                        var cell = appeng.api.storage.StorageCells.getCellInventory(item("item_storage_cell_1k"), () -> { });
                        helper.assertTrue(cell != null, "Native AE2 reward 1k cell is missing");
                        appeng.api.storage.IStorageProvider provider = mounts -> mounts.mount(cell, 0);
                        grid.getStorageService().addGlobalStorageProvider(provider);
                        try {
                            BlockPos position = helper.absolutePos(ACCESS);
                            fixture.player.setPos(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
                            ItemStack terminal = item("wireless_terminal");
                            var terminalItem = (appeng.items.tools.powered.WirelessTerminalItem) terminal.getItem();
                            appeng.items.tools.powered.WirelessTerminalItem.LINKABLE_HANDLER.link(terminal, GlobalPos.of(helper.getLevel().dimension(), position));
                            terminalItem.injectAEPower(terminal, Double.MAX_VALUE, appeng.api.config.Actionable.MODULATE);
                            fixture.player.getInventory().setItem(0, terminal);
                            helper.assertTrue(StorageSources.findForInsert(fixture.player, StorageSource.APPLIED_ENERGISTICS).size() == 1,
                                "Native AE2 reward terminal did not discover its real grid");
                            ItemStack prototype = tagged(1, 1);
                            var key = appeng.api.stacks.AEItemKey.of(prototype);
                            var other = appeng.api.stacks.AEItemKey.of(tagged(2, 1));
                            var storage = grid.getStorageService().getInventory();
                            var actor = appeng.api.networking.security.IActionSource.ofPlayer(fixture.player);
                            long seeded = 0;
                            if (mode == 1) {
                                long capacity = storage.insert(key, 1000000, appeng.api.config.Actionable.MODULATE, actor);
                                helper.assertTrue(capacity > 2 && capacity < 1000000
                                    && storage.extract(key, 2, appeng.api.config.Actionable.MODULATE, actor) == 2,
                                    "Native AE2 reward cell did not establish exactly two empty item spaces");
                                seeded = capacity - 2;
                            }
                            if (mode == 0) helper.assertTrue(storage.insert(other, 19, appeng.api.config.Actionable.MODULATE, actor) == 19,
                                "Native AE2 reward alternate NBT seed was rejected");
                            if (mode == 2) {
                                terminalItem.extractAEPower(terminal, Double.MAX_VALUE, appeng.api.config.Actionable.MODULATE);
                                helper.assertTrue(terminalItem.getAECurrentPower(terminal) == 0, "Native AE2 reward terminal was not depleted");
                            }
                            if (mode == 3) fixture.player.setPos(position.getX() + 10000.5, position.getY() + 0.5, position.getZ() + 0.5);
                            double before = terminalItem.getAECurrentPower(terminal);
                            RewardPreferences.set(fixture.player, RewardDestination.APPLIED_ENERGISTICS);
                            int reward = mode == 0 ? 75 : 10;
                            fixture.claim(prototype, reward);
                            long stored = storage.extract(key, 1000000, appeng.api.config.Actionable.SIMULATE, actor);
                            int local = carried(fixture.player, prototype);
                            int inserted = mode == 0 ? 75 : mode == 1 ? 2 : 0;
                            helper.assertTrue(stored == seeded + inserted && local == reward - inserted && stored + local == seeded + reward,
                                "Native AE2 reward lost, duplicated, or bypassed unavailable access for exact NBT quantities");
                            helper.assertTrue(fixture.player.getInventory().getItem(0) == terminal, "Native AE2 reward replaced its wireless terminal");
                            helper.assertTrue(Math.abs(before - terminalItem.getAECurrentPower(terminal)
                                - inserted / (double) key.getAmountPerOperation()) < 0.001, "Native AE2 reward charged incorrect powered-insertion energy");
                            if (mode == 0) helper.assertTrue(storage.extract(other, 1000, appeng.api.config.Actionable.SIMULATE, actor) == 19,
                                "Native AE2 reward merged another NBT variant");
                            helper.succeed();
                        } finally { grid.getStorageService().removeGlobalStorageProvider(provider); }
                    } finally {
                        helper.setBlock(ACCESS, Blocks.AIR);
                        helper.setBlock(ENERGY, Blocks.AIR);
                    }
                });
            }
        }
    }
}

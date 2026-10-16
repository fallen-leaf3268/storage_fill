package dev.airun.storagefill.compat.ftb;

import com.mojang.authlib.GameProfile;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.StorageFill;
import dev.ftb.mods.ftblibrary.config.Tristate;
import dev.ftb.mods.ftbquests.net.ClaimAllRewardsMessage;
import dev.ftb.mods.ftbquests.net.ClaimChoiceRewardMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.loot.RewardTable;
import dev.ftb.mods.ftbquests.quest.loot.WeightedReward;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.AllTableReward;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.reward.RandomReward;
import dev.ftb.mods.ftbquests.quest.reward.XPReward;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class FtbRewardRoutingGameTests {
    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void defaultItemRewardPreservesNativeInventoryAndClaimLedger(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.defaultItemRewardPreservesNativeInventoryAndClaimLedger(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void unavailableSelectedStorageFallsBackToNativeInventory(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.unavailableSelectedStorageFallsBackToNativeInventory(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void itemRewardUsesOnlySelectedRealBackpack(GameTestHelper helper) {
        if (!available(helper) || !backpackAvailable(helper)) return;
        Cases.itemRewardUsesOnlySelectedRealBackpack(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void originalChoiceMessageRejectsAlreadyClaimedReward(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.originalChoiceMessageRejectsAlreadyClaimedReward(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void partiallyFullSelectedBackpackGivesOnlyRemainderToPlayer(GameTestHelper helper) {
        if (!available(helper) || !backpackAvailable(helper)) return;
        Cases.partiallyFullSelectedBackpackGivesOnlyRemainderToPlayer(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void fullNativePlayerInventoryDropsExactRewardRemainder(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.fullNativePlayerInventoryDropsExactRewardRemainder(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void originalChoiceMessageRejectsLockedTeamBeforeGivingItems(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.originalChoiceMessageRejectsLockedTeamBeforeGivingItems(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void originalClaimAllRoutesItemsAndPreservesExperienceReward(GameTestHelper helper) {
        if (!available(helper) || !backpackAvailable(helper)) return;
        Cases.originalClaimAllRoutesItemsAndPreservesExperienceReward(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void nestedRandomAndAllTableItemRewardsUseSelectedBackpack(GameTestHelper helper) {
        if (!available(helper) || !backpackAvailable(helper)) return;
        Cases.nestedRandomAndAllTableItemRewardsUseSelectedBackpack(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void nonRewardNativeGiveDoesNotUseFtbRewardDestination(GameTestHelper helper) {
        if (!available(helper) || !backpackAvailable(helper)) return;
        Cases.nonRewardNativeGiveDoesNotUseFtbRewardDestination(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void automaticItemRewardUsesSelectedBackpackAndNativeClaimLedger(GameTestHelper helper) {
        if (!available(helper) || !backpackAvailable(helper)) return;
        Cases.automaticItemRewardUsesSelectedBackpackAndNativeClaimLedger(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void onlyOneItemRewardKeepsNativePlayerInventoryMeaning(GameTestHelper helper) {
        if (!available(helper) || !backpackAvailable(helper)) return;
        Cases.onlyOneItemRewardKeepsNativePlayerInventoryMeaning(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void choiceRewardCanBeClaimedAgainAfterNativeRepeatReset(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.choiceRewardCanBeClaimedAgainAfterNativeRepeatReset(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward")
    public static void blockedChoiceRewardCannotIssueUnrecordedItems(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.blockedChoiceRewardCannotIssueUnrecordedItems(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward_failure")
    public static void failedChoiceCannotReissuePartiallyStoredItemReward(GameTestHelper helper) {
        if (!available(helper) || !backpackAvailable(helper)) return;
        Cases.failedChoiceCannotReissuePartiallyStoredItemReward(helper);
    }

    @GameTest(template = "empty", batch = "storage_ftb_reward_failure")
    public static void failedChoiceKeepsNativeRepeatResetAndNextCycleClaim(GameTestHelper helper) {
        if (!available(helper) || !backpackAvailable(helper)) return;
        Cases.failedChoiceKeepsNativeRepeatResetAndNextCycleClaim(helper);
    }

    private static boolean available(GameTestHelper helper) {
        if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbteams")) return true;
        StorageFill.LOGGER.warn("SKIPPED FTB reward routing GameTest: FTB Quests or Teams is absent");
        helper.succeed();
        return false;
    }

    private static boolean backpackAvailable(GameTestHelper helper) {
        if (ModList.get().isLoaded("sophisticatedbackpacks")) return true;
        StorageFill.LOGGER.warn("SKIPPED FTB backpack reward GameTest: Sophisticated Backpacks is absent");
        helper.succeed();
        return false;
    }

    private static final class Cases {
        private static final AtomicLong IDS = new AtomicLong(0x6900000000000000L);

        public static void defaultItemRewardPreservesNativeInventoryAndClaimLedger(GameTestHelper helper) {
            if (!installed(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                ItemStack prototype = tagged();
                ItemReward reward = f.reward(prototype, 75);
                f.data.claimReward(f.player, reward, false);
                f.data.claimReward(f.player, reward, false);
                helper.assertTrue(count(f.player, prototype) == 75 && f.data.isRewardClaimed(f.player.getUUID(), reward),
                    "Default item reward changed its exact quantity, NBT, or claim-once ledger");
            }
            helper.succeed();
        }

        public static void unavailableSelectedStorageFallsBackToNativeInventory(GameTestHelper helper) {
            if (!installed(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                ItemStack prototype = tagged();
                f.data.claimReward(f.player, f.reward(prototype, 9), false);
                helper.assertTrue(count(f.player, prototype) == 9,
                    "Missing selected backpack lost or duplicated an item reward");
            }
            helper.succeed();
        }

        public static void itemRewardUsesOnlySelectedRealBackpack(GameTestHelper helper) {
            if (!installed(helper) || !backpacks(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                ItemStack backpack = new ItemStack(net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems.BACKPACK.get());
                var handler = backpack.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper
                    .getCapabilityInstance()).orElseThrow(IllegalStateException::new).getInventoryHandler();
                f.player.getInventory().setItem(0, backpack);
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                ItemStack prototype = tagged();
                ItemReward reward = f.reward(prototype, 75);
                f.data.claimReward(f.player, reward, false);
                int stored = 0;
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);
                    if (!stack.isEmpty() && StackKey.of(stack).equals(StackKey.of(prototype))) stored += stack.getCount();
                }
                helper.assertTrue(stored == 75 && count(f.player, prototype) == 0
                    && f.data.isRewardClaimed(f.player.getUUID(), reward),
                    "Real FTB item reward did not preserve count and NBT while routing to the selected backpack");
            }
            helper.succeed();
        }

        public static void originalChoiceMessageRejectsAlreadyClaimedReward(GameTestHelper helper) {
            if (!installed(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                ChoiceReward reward = f.choice();
                var context = f.context();
                new ClaimChoiceRewardMessage(reward.id, 0).handle(context);
                new ClaimChoiceRewardMessage(reward.id, 0).handle(context);
                helper.assertTrue(count(f.player, tagged()) == 7 && f.data.isRewardClaimed(f.player.getUUID(), reward),
                    "Repeated original FTB choice message issued its child item reward more than once");
            }
            helper.succeed();
        }

        public static void partiallyFullSelectedBackpackGivesOnlyRemainderToPlayer(GameTestHelper helper) {
            if (!installed(helper) || !backpacks(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                ItemStack backpack = new ItemStack(net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems.BACKPACK.get());
                var handler = backpack.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper
                    .getCapabilityInstance()).orElseThrow(IllegalStateException::new).getInventoryHandler();
                for (int slot = 0; slot < handler.getSlots(); slot++) handler.setStackInSlot(slot, new ItemStack(Items.STONE, 64));
                ItemStack prototype = new ItemStack(Items.DIAMOND);
                handler.setStackInSlot(0, prototype.copyWithCount(62));
                f.player.getInventory().setItem(0, backpack);
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                ItemReward reward = f.reward(prototype, 10);
                f.data.claimReward(f.player, reward, false);
                helper.assertTrue(handler.getStackInSlot(0).getCount() == 64 && count(f.player, prototype) == 8,
                    "Partial real FTB reward returned committed items or lost the uncommitted remainder");
            }
            helper.succeed();
        }

        public static void fullNativePlayerInventoryDropsExactRewardRemainder(GameTestHelper helper) {
            if (!installed(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                f.player.setPos(helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)).getCenter());
                for (int slot = 0; slot < f.player.getInventory().items.size(); slot++)
                    f.player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                f.data.claimReward(f.player, f.reward(tagged(), 7), false);
                int dropped = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    f.player.getBoundingBox().inflate(3)).stream().filter(entity -> !entity.getItem().isEmpty()
                    && StackKey.of(entity.getItem()).equals(StackKey.of(tagged())))
                    .mapToInt(entity -> entity.getItem().getCount()).sum();
                helper.assertTrue(dropped == 7 && count(f.player, tagged()) == 0,
                    "Native full-inventory fallback lost or duplicated the exact dropped reward remainder");
            }
            helper.succeed();
        }

        public static void originalChoiceMessageRejectsLockedTeamBeforeGivingItems(GameTestHelper helper) {
            if (!installed(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                ChoiceReward reward = f.choice();
                f.data.setLocked(true);
                new ClaimChoiceRewardMessage(reward.id, 0).handle(f.context());
                helper.assertTrue(count(f.player, tagged()) == 0 && !f.data.isRewardClaimed(f.player.getUUID(), reward),
                    "Locked FTB team received an unrecorded child reward through the original choice message");
            }
            helper.succeed();
        }

        public static void originalClaimAllRoutesItemsAndPreservesExperienceReward(GameTestHelper helper) {
            if (!installed(helper) || !backpacks(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                var handler = f.backpack();
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                ItemReward item = f.reward(tagged(), 13);
                XPReward xp = new XPReward(IDS.incrementAndGet(), f.quest, 17);
                f.quest.addReward(xp);
                f.file.refreshIDMap();
                new ClaimAllRewardsMessage().handle(f.context());
                new ClaimAllRewardsMessage().handle(f.context());
                helper.assertTrue(stored(handler, tagged()) == 13 && count(f.player, tagged()) == 0
                    && f.player.totalExperience == 17 && f.data.isRewardClaimed(f.player.getUUID(), item)
                    && f.data.isRewardClaimed(f.player.getUUID(), xp),
                    "Original claim-all changed an item route, a non-item reward, or FTB duplicate protection");
            }
            helper.succeed();
        }

        public static void nestedRandomAndAllTableItemRewardsUseSelectedBackpack(GameTestHelper helper) {
            if (!installed(helper) || !backpacks(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                var handler = f.backpack();
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                RewardTable randomTable = new RewardTable(-1L, f.file);
                randomTable.addReward(new WeightedReward(new ItemReward(IDS.incrementAndGet(), randomTable.getFakeQuest(), tagged(), 5), 1));
                RandomReward random = new RandomReward(IDS.incrementAndGet(), f.quest);
                random.setTable(randomTable);
                RewardTable allTable = new RewardTable(-1L, f.file);
                allTable.addReward(new WeightedReward(random, 1));
                allTable.addReward(new WeightedReward(new ItemReward(IDS.incrementAndGet(), allTable.getFakeQuest(), tagged(), 3), 1));
                AllTableReward all = new AllTableReward(IDS.incrementAndGet(), f.quest);
                all.setTable(allTable);
                f.quest.addReward(all);
                f.file.refreshIDMap();
                f.player.getRandom().setSeed(0x5A17L);
                f.data.claimReward(f.player, all, false);
                f.data.claimReward(f.player, all, false);
                helper.assertTrue(stored(handler, tagged()) == 8 && count(f.player, tagged()) == 0,
                    "Nested native random/all-table rewards bypassed the selected route or their parent claim ledger");
            }
            helper.succeed();
        }

        public static void nonRewardNativeGiveDoesNotUseFtbRewardDestination(GameTestHelper helper) {
            if (!installed(helper) || !backpacks(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                var handler = f.backpack();
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                dev.architectury.hooks.item.ItemStackHooks.giveItem(f.player, tagged().copyWithCount(9));
                helper.assertTrue(count(f.player, tagged()) == 9 && stored(handler, tagged()) == 0,
                    "FTB reward routing intercepted an unrelated native give-item operation");
            }
            helper.succeed();
        }

        public static void automaticItemRewardUsesSelectedBackpackAndNativeClaimLedger(GameTestHelper helper) {
            if (!installed(helper) || !backpacks(helper)) return;
            try (Fixture f = new Fixture(helper, false)) {
                var handler = f.backpack();
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                ItemReward reward = f.reward(tagged(), 12);
                CompoundTag config = new CompoundTag();
                reward.writeData(config);
                Tristate.TRUE.write(config, "team_reward");
                config.putString("auto", "enabled");
                reward.readData(config);
                f.file.withPlayerContext(f.player, () -> f.data.setProgress(f.task, 1));
                helper.assertTrue(stored(handler, tagged()) == 12 && count(f.player, tagged()) == 0
                    && f.data.isRewardClaimed(f.player.getUUID(), reward),
                    "Native automatic player item reward did not use the selected route and claim ledger");
            }
            helper.succeed();
        }

        public static void onlyOneItemRewardKeepsNativePlayerInventoryMeaning(GameTestHelper helper) {
            if (!installed(helper) || !backpacks(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                var handler = f.backpack();
                handler.setStackInSlot(0, tagged().copyWithCount(5));
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                ItemReward reward = f.reward(tagged(), 7);
                CompoundTag config = new CompoundTag();
                reward.writeData(config);
                config.putBoolean("only_one", true);
                reward.readData(config);
                f.data.claimReward(f.player, reward, false);
                helper.assertTrue(stored(handler, tagged()) == 12, "Only-one item reward started treating network stock as player inventory");
                f.player.getInventory().setItem(1, tagged());
                ItemReward second = f.reward(tagged(), 9);
                second.writeData(config);
                config.putBoolean("only_one", true);
                second.readData(config);
                f.data.claimReward(f.player, second, false);
                helper.assertTrue(stored(handler, tagged()) == 12 && count(f.player, tagged()) == 1
                    && f.data.isRewardClaimed(f.player.getUUID(), second),
                    "Only-one reward ignored its original player-inventory check or changed its claim ledger");
            }
            helper.succeed();
        }

        public static void choiceRewardCanBeClaimedAgainAfterNativeRepeatReset(GameTestHelper helper) {
            if (!installed(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                CompoundTag config = new CompoundTag();
                f.quest.writeData(config);
                Tristate.TRUE.write(config, "can_repeat");
                f.quest.readData(config);
                ChoiceReward reward = f.choice();
                new ClaimChoiceRewardMessage(reward.id, 0).handle(f.context());
                helper.assertTrue(f.data.getCompletionCount(f.quest) == 1 && !f.data.isCompleted(f.quest),
                    "Choice reward fixture did not trigger FTB's native repeat reset");
                new ClaimChoiceRewardMessage(reward.id, 0).handle(f.context());
                helper.assertTrue(count(f.player, tagged()) == 7, "Unready repeat quest issued a second child reward");
                f.data.setProgress(f.task, 1);
                new ClaimChoiceRewardMessage(reward.id, 0).handle(f.context());
                helper.assertTrue(count(f.player, tagged()) == 14 && f.data.getCompletionCount(f.quest) == 2,
                    "Choice ledger guard prevented a newly completed native repeat cycle");
            }
            helper.succeed();
        }

        public static void blockedChoiceRewardCannotIssueUnrecordedItems(GameTestHelper helper) {
            if (!installed(helper)) return;
            try (Fixture f = new Fixture(helper)) {
                ChoiceReward reward = f.choice();
                f.data.setRewardsBlocked(true);
                new ClaimChoiceRewardMessage(reward.id, 0).handle(f.context());
                helper.assertTrue(count(f.player, tagged()) == 0 && !f.data.isRewardClaimed(f.player.getUUID(), reward),
                    "Reward-blocked FTB team received an unrecorded child item reward");
            }
            helper.succeed();
        }

        public static void failedChoiceCannotReissuePartiallyStoredItemReward(GameTestHelper helper) {
            try (Fixture f = new Fixture(helper)) {
                var handler = f.backpack();
                for (int slot = 0; slot < handler.getSlots(); slot++) handler.setStackInSlot(slot, new ItemStack(Items.STONE, 64));
                ItemStack prototype = new ItemStack(Items.DIAMOND);
                handler.setStackInSlot(0, prototype.copyWithCount(62));
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                ChoiceReward reward = f.failingChoice(prototype, 10);
                boolean failed = false;
                try { new ClaimChoiceRewardMessage(reward.id, 0).handle(f.context()); }
                catch (IllegalStateException error) {
                    if (!"Injected child failure after real item reward".equals(error.getMessage())) throw error;
                    failed = true;
                }
                helper.assertTrue(failed && handler.getStackInSlot(0).getCount() == 64 && count(f.player, prototype) == 8,
                    "Choice failure fixture did not commit two real stored items and eight native inventory items before throwing");
                new ClaimChoiceRewardMessage(reward.id, 0).handle(f.context());
                helper.assertTrue(f.data.isRewardClaimed(f.player.getUUID(), reward)
                    && handler.getStackInSlot(0).getCount() == 64 && count(f.player, prototype) == 8,
                    "Retrying a failed choice reissued items that its original child already stored or gave to the player");
            }
            helper.succeed();
        }

        public static void failedChoiceKeepsNativeRepeatResetAndNextCycleClaim(GameTestHelper helper) {
            try (Fixture f = new Fixture(helper)) {
                var handler = f.backpack();
                RewardPreferences.set(f.player, RewardDestination.SOPHISTICATED_BACKPACKS);
                CompoundTag config = new CompoundTag();
                f.quest.writeData(config);
                Tristate.TRUE.write(config, "can_repeat");
                f.quest.readData(config);
                ChoiceReward reward = f.failingChoice(7);
                boolean failed = false;
                try { new ClaimChoiceRewardMessage(reward.id, 0).handle(f.context()); }
                catch (IllegalStateException error) {
                    if (!"Injected child failure after real item reward".equals(error.getMessage())) throw error;
                    failed = true;
                }
                helper.assertTrue(failed && stored(handler, tagged()) == 7 && f.data.getCompletionCount(f.quest) == 1
                    && !f.data.isCompleted(f.quest), "Failed choice did not settle its native repeat cycle after committed item delivery");
                new ClaimChoiceRewardMessage(reward.id, 0).handle(f.context());
                helper.assertTrue(stored(handler, tagged()) == 7, "An unready failed repeat cycle issued its item reward again");
                f.data.setProgress(f.task, 1);
                new ClaimChoiceRewardMessage(reward.id, 0).handle(f.context());
                helper.assertTrue(stored(handler, tagged()) == 14 && count(f.player, tagged()) == 0
                    && f.data.getCompletionCount(f.quest) == 2 && !f.data.isCompleted(f.quest),
                    "Failed choice protection blocked a new completed repeat cycle or reset it twice");
            }
            helper.succeed();
        }

        private static ItemStack tagged() {
            ItemStack stack = new ItemStack(Items.DIAMOND);
            stack.getOrCreateTag().putString("reward_variant", "preserve-exactly");
            return stack;
        }

        private static int count(ServerPlayer player, ItemStack prototype) {
            return player.getInventory().items.stream().filter(stack -> !stack.isEmpty()
                && StackKey.of(stack).equals(StackKey.of(prototype))).mapToInt(ItemStack::getCount).sum();
        }

        private static int stored(net.minecraftforge.items.IItemHandler handler, ItemStack prototype) {
            int count = 0;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && StackKey.of(stack).equals(StackKey.of(prototype))) count += stack.getCount();
            }
            return count;
        }

        private static boolean installed(GameTestHelper helper) {
            if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbteams")) return true;
            StorageFill.LOGGER.warn("SKIPPED FTB reward routing GameTest: FTB Quests or Teams is absent");
            helper.succeed();
            return false;
        }

        private static boolean backpacks(GameTestHelper helper) {
            if (ModList.get().isLoaded("sophisticatedbackpacks")) return true;
            StorageFill.LOGGER.warn("SKIPPED FTB backpack reward GameTest: Sophisticated Backpacks is absent");
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

            private Fixture(GameTestHelper helper) {
                this(helper, true);
            }

            private Fixture(GameTestHelper helper, boolean completed) {
                file = ServerQuestFile.INSTANCE;
                helper.assertTrue(file != null, "FTB server quest file is missing");
                player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "ftb-reward"));
                player.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(),
                    new Connection(PacketFlow.SERVERBOUND), player);
                ((TeamManagerImpl) FTBTeamsAPI.api().getManager()).playerLoggedIn(null,
                    player.getUUID(), player.getGameProfile().getName());
                data = TeamData.get(player);
                chapter = new Chapter(IDS.incrementAndGet(), file, file.getDefaultChapterGroup());
                file.getDefaultChapterGroup().addChapter(chapter);
                quest = new Quest(IDS.incrementAndGet(), chapter);
                chapter.addQuest(quest);
                task = new ItemTask(IDS.incrementAndGet(), quest);
                task.setStackAndCount(new ItemStack(Items.IRON_INGOT), 1);
                quest.addTask(task);
                file.refreshIDMap();
                if (completed) {
                    data.setProgress(task, 1);
                    helper.assertTrue(data.isCompleted(quest), "FTB reward fixture quest is not complete");
                }
            }

            private net.minecraftforge.items.IItemHandlerModifiable backpack() {
                ItemStack backpack = new ItemStack(net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems.BACKPACK.get());
                var handler = backpack.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper
                    .getCapabilityInstance()).orElseThrow(IllegalStateException::new).getInventoryHandler();
                player.getInventory().setItem(0, backpack);
                return handler;
            }

            private ItemReward reward(ItemStack prototype, int count) {
                ItemReward reward = new ItemReward(IDS.incrementAndGet(), quest, prototype.copy(), count);
                quest.addReward(reward);
                file.refreshIDMap();
                return reward;
            }

            private ChoiceReward choice() {
                RewardTable table = new RewardTable(-1L, file);
                table.addReward(new WeightedReward(new ItemReward(IDS.incrementAndGet(), table.getFakeQuest(), tagged(), 7), 1));
                ChoiceReward reward = new ChoiceReward(IDS.incrementAndGet(), quest);
                reward.setTable(table);
                quest.addReward(reward);
                file.refreshIDMap();
                return reward;
            }

            private ChoiceReward failingChoice(int count) {
                return failingChoice(tagged(), count);
            }

            private ChoiceReward failingChoice(ItemStack prototype, int count) {
                RewardTable table = new RewardTable(-1L, file);
                ItemReward child = new ItemReward(IDS.incrementAndGet(), table.getFakeQuest(), prototype, count) {
                    private boolean failNext = true;
                    public void claim(ServerPlayer recipient, boolean notify) {
                        super.claim(recipient, notify);
                        if (failNext) {
                            failNext = false;
                            throw new IllegalStateException("Injected child failure after real item reward");
                        }
                    }
                };
                table.addReward(new WeightedReward(child, 1));
                ChoiceReward reward = new ChoiceReward(IDS.incrementAndGet(), quest);
                reward.setTable(table);
                quest.addReward(reward);
                file.refreshIDMap();
                return reward;
            }

            private NetworkManager.PacketContext context() {
                return new NetworkManager.PacketContext() {
                    public Player getPlayer() { return player; }
                    public void queue(Runnable runnable) { runnable.run(); }
                    public Env getEnvironment() { return Env.SERVER; }
                };
            }

            public void close() {
                data.setLocked(false);
                data.setRewardsBlocked(false);
                file.getDefaultChapterGroup().removeChapter(chapter);
                file.refreshIDMap();
            }
        }
    }
}

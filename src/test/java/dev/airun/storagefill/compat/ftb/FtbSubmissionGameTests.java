package dev.airun.storagefill.compat.ftb;

import com.mojang.authlib.GameProfile;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.storage.StorageSource;
import dev.ftb.mods.ftblibrary.config.Tristate;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.net.SubmitTaskMessage;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
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

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class FtbSubmissionGameTests {
    @GameTest(template = "empty")
    public static void submissionUsesBackpackBeforeStorage(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.backpackBeforeStorage(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void enoughBackpackMaterialsDoNotOpenStorage(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.backpackOnly(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void insufficientMaterialsRemainPartiallySubmitted(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.partialSubmission(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullBackpackCanSubmitStorageMaterialsDirectly(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.fullBackpack(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void submissionUsesFtbNbtMatchingRules(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.nbtMatching(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lockedAndUnreadyTasksNeverConsumeMaterials(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.taskGuards(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingExtractionOnlySubmitsActuallyTakenMaterials(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.shortExtraction(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void duplicateClickCannotConsumeAgainInSameTick(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.duplicateClick(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void completionRewardCannotReenterAnotherSubmission(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.crossTaskReentry(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void automaticRepeatResetCannotConsumeNextCycle(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.repeatReset(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardPlacedInConsumedSlotSurvivesSubmission(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.rewardInventoryMutation(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void originalFtbSubmitMessageUsesRealSophisticatedBackpack(GameTestHelper helper) {
        if (!installed(helper)) return;
        if (!ModList.get().isLoaded("sophisticatedbackpacks")) {
            StorageFill.LOGGER.warn("SKIPPED FTB submit message GameTest: Sophisticated Backpacks is absent");
            helper.succeed();
            return;
        }
        Cases.realSubmissionMessage(helper);
        helper.succeed();
    }

    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbteams")) return true;
        StorageFill.LOGGER.warn("SKIPPED FTB submission GameTest: FTB Quests or Teams is absent");
        helper.succeed();
        return false;
    }

    @GameTest(template = "empty")
    public static void discoveryFailurePreservesAlreadySubmittedBackpackMaterials(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.discoveryFailure(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardFailureDoesNotRefundAcceptedMaterials(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.rewardFailure(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void exactStoredItemsDoNotEnumerateWarehouseCandidates(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.exactStorage(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void storageVariantDiscoveryStopsBeforeBuildingAnUnboundedList(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.boundedDiscovery(helper);
        helper.succeed();
    }

    private static final class Cases {
        private static final AtomicLong IDS = new AtomicLong(0x6100000000000000L);

        private static void boundedDiscovery(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.DIAMOND), 1000)) {
                CompoundTag config = new CompoundTag();
                fixture.task.writeData(config);
                Tristate.FALSE.write(config, "match_nbt");
                fixture.task.readData(config);
                int[] visited = {0};
                int[] taken = {0};
                boolean[] enumerating = {false};
                StorageSource source = new StorageSource() {
                    public List<ItemStack> snapshot(Set<Item> wanted) {
                        throw new AssertionError("Bounded submission requested an inventory snapshot");
                    }
                    public List<ItemStack> preview(Set<Item> wanted) {
                        throw new AssertionError("Bounded submission materialized an unbounded preview list");
                    }
                    public boolean visitPreview(Set<Item> wanted, java.util.function.Predicate<ItemStack> visitor) {
                        helper.assertTrue(wanted.equals(Set.of(Items.DIAMOND)), "Submission enumerated unrelated item types");
                        enumerating[0] = true;
                        try {
                            for (int variant = 0; variant < 10000; variant++) {
                                visited[0]++;
                                if (!visitor.test(tagged(1, Integer.toString(variant)))) return false;
                            }
                            return true;
                        } finally { enumerating[0] = false; }
                    }
                    public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
                        helper.assertFalse(enumerating[0], "Submission mutated storage while its native iterator was active");
                        if (!prototype.hasTag()) return ItemStack.EMPTY;
                        if (!simulate) taken[0]++;
                        return prototype.copyWithCount(1);
                    }
                    public ItemStack insert(ItemStack stack) { return stack; }
                    public void finish(int extracted) {
                        helper.assertTrue(extracted == taken[0], "Bounded submission finished with an incorrect confirmed amount");
                    }
                };
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(visited[0] == 512 && taken[0] == 64 && result.submitted() == 64
                    && result.remaining() == 936 && !result.failed() && fixture.data.getProgress(fixture.task) == 64,
                    "Submission exceeded its native traversal or extraction budget");
            }
        }

        private static void backpackBeforeStorage(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 8));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(fixture.data.getProgress(fixture.task) == 10 && fixture.data.isCompleted(fixture.task),
                    "Submission did not complete the real FTB task with backpack and network materials");
                helper.assertTrue(result.submitted() == 10 && result.remaining() == 0 && !result.failed(),
                    "Submission reported an incorrect material settlement");
                helper.assertTrue(count(fixture.player, new ItemStack(Items.IRON_INGOT)) == 0
                    && source.count(new ItemStack(Items.IRON_INGOT)) == 1,
                    "Submission did not use backpack materials before exactly seven network materials");
            }
        }

        private static void discoveryFailure(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> {
                    throw new IllegalStateException("Injected storage discovery failure");
                });
                helper.assertTrue(result.submitted() == 3 && result.remaining() == 7 && result.failed()
                    && fixture.data.getProgress(fixture.task) == 3 && count(fixture.player, new ItemStack(Items.IRON_INGOT)) == 0,
                    "Discovery failure lost the settlement of already accepted backpack materials");
            }
        }

        private static void exactStorage(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 12));
                source.forbidPreview = true;
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(result.submitted() == 10 && result.remaining() == 0 && !result.failed()
                    && fixture.data.isCompleted(fixture.task) && source.count(new ItemStack(Items.IRON_INGOT)) == 2,
                    "An exact stored item could not be submitted without enumerating warehouse candidates");
            }
        }

        private static void rewardFailure(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 1)) {
                fixture.reward(player -> { throw new IllegalStateException("Injected reward failure"); });
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 2));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(result.submitted() == 1 && result.remaining() == 0 && result.failed()
                    && fixture.data.getProgress(fixture.task) == 1 && source.count(new ItemStack(Items.IRON_INGOT)) == 1
                    && count(fixture.player, new ItemStack(Items.IRON_INGOT)) == 0,
                    "A failed reward callback refunded material already accepted by FTB");
            }
        }

        private static void backpackOnly(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 13));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> {
                    throw new AssertionError("A complete backpack submission accessed remote storage");
                });
                helper.assertTrue(result.submitted() == 10 && result.remaining() == 0 && !result.failed()
                    && fixture.data.isCompleted(fixture.task) && count(fixture.player, new ItemStack(Items.IRON_INGOT)) == 3,
                    "Backpack-only submission failed to consume exactly the current task requirement");
            }
        }

        private static void partialSubmission(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 4));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(result.submitted() == 7 && result.remaining() == 3 && !result.failed()
                    && fixture.data.getProgress(fixture.task) == 7 && !fixture.data.isCompleted(fixture.task),
                    "Available materials were not retained as partial FTB progress");
                helper.assertTrue(count(fixture.player, new ItemStack(Items.IRON_INGOT)) == 0
                    && source.count(new ItemStack(Items.IRON_INGOT)) == 0,
                    "Partial submission did not conserve the seven available materials");
            }
        }

        private static void fullBackpack(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                for (int slot = 0; slot < 36; slot++) fixture.player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 12));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(result.submitted() == 10 && result.remaining() == 0 && fixture.data.isCompleted(fixture.task)
                    && source.count(new ItemStack(Items.IRON_INGOT)) == 2,
                    "A full backpack prevented direct network submission");
                helper.assertTrue(count(fixture.player, new ItemStack(Items.COBBLESTONE)) == 36 * 64,
                    "Direct network submission changed unrelated full backpack slots");
            }
        }

        private static void nbtMatching(GameTestHelper helper) {
            ItemStack red = tagged(1, "red");
            ItemStack blue = tagged(1, "blue");
            try (Fixture fixture = new Fixture(helper, red, 5)) {
                CompoundTag config = new CompoundTag();
                fixture.task.writeData(config);
                Tristate.TRUE.write(config, "match_nbt");
                fixture.task.readData(config);
                fixture.player.getInventory().setItem(0, blue.copyWithCount(7));
                MemorySource source = new MemorySource(blue.copyWithCount(8), red.copyWithCount(3));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(result.submitted() == 3 && result.remaining() == 2
                    && fixture.data.getProgress(fixture.task) == 3 && source.count(red) == 0 && source.count(blue) == 8
                    && count(fixture.player, blue) == 7,
                    "NBT-constrained task consumed a nonmatching item variant");
            }
            try (Fixture fixture = new Fixture(helper, red, 5)) {
                CompoundTag config = new CompoundTag();
                fixture.task.writeData(config);
                Tristate.FALSE.write(config, "match_nbt");
                fixture.task.readData(config);
                MemorySource source = new MemorySource(blue.copyWithCount(8));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(result.submitted() == 5 && fixture.data.isCompleted(fixture.task) && source.count(blue) == 3,
                    "Task with NBT matching disabled rejected a valid stored item variant");
            }
        }

        private static void taskGuards(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                fixture.data.setLocked(true);
                assertRejected(helper, fixture, "Locked team consumed submission materials");
                fixture.data.setLocked(false);
            }
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                Quest prerequisite = new Quest(IDS.incrementAndGet(), fixture.chapter);
                fixture.chapter.addQuest(prerequisite);
                fixture.quest.addDependency(prerequisite);
                fixture.file.refreshIDMap();
                assertRejected(helper, fixture, "Task with incomplete dependencies consumed materials");
            }
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                ItemTask previous = new ItemTask(IDS.incrementAndGet(), fixture.quest);
                previous.setStackAndCount(new ItemStack(Items.GOLD_INGOT), 1);
                previous.setConsumeItems(Tristate.TRUE);
                fixture.quest.setTaskList(List.of(previous, fixture.task));
                CompoundTag config = new CompoundTag();
                fixture.quest.writeData(config);
                Tristate.TRUE.write(config, "require_sequential_tasks");
                fixture.quest.readData(config);
                fixture.file.refreshIDMap();
                assertRejected(helper, fixture, "Sequential task bypassed its preceding unfinished task");
            }
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                CompoundTag config = new CompoundTag();
                fixture.task.writeData(config);
                config.putBoolean("task_screen_only", true);
                fixture.task.readData(config);
                assertRejected(helper, fixture, "Task-screen-only task accepted an ordinary submission");
            }
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                fixture.data.setProgress(fixture.task, 10);
                assertRejected(helper, fixture, "Completed task consumed materials again");
            }
        }

        private static void assertRejected(GameTestHelper helper, Fixture fixture, String message) {
            fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
            long before = fixture.data.getProgress(fixture.task);
            MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 8));
            AtomicInteger opened = new AtomicInteger();
            var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> {
                opened.incrementAndGet();
                return List.of(source);
            });
            helper.assertTrue(result.submitted() == 0 && fixture.data.getProgress(fixture.task) == before
                && opened.get() == 0 && count(fixture.player, new ItemStack(Items.IRON_INGOT)) == 3
                && source.count(new ItemStack(Items.IRON_INGOT)) == 8, message);
        }

        private static void shortExtraction(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 2));
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 8));
                source.performBudget = 3;
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(result.submitted() == 5 && result.remaining() == 5
                    && fixture.data.getProgress(fixture.task) == 5 && source.count(new ItemStack(Items.IRON_INGOT)) == 5
                    && count(fixture.player, new ItemStack(Items.IRON_INGOT)) == 0,
                    "Short extraction credited unreceived material or lost actual material");
            }
        }

        private static void duplicateClick(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 4));
                var first = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                source.insert(new ItemStack(Items.IRON_INGOT, 3));
                var second = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(first.submitted() == 4 && second.submitted() == 0 && second.failed()
                    && fixture.data.getProgress(fixture.task) == 4 && source.count(new ItemStack(Items.IRON_INGOT)) == 3,
                    "Same-tick repeated submission consumed newly available materials again");
            }
        }

        private static void crossTaskReentry(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 1)) {
                ItemTask other = fixture.addTask(new ItemStack(Items.GOLD_INGOT), 2);
                MemorySource nestedSource = new MemorySource(new ItemStack(Items.GOLD_INGOT, 2));
                FtbQuestSubmission.Result[] nested = new FtbQuestSubmission.Result[1];
                AtomicInteger rewards = new AtomicInteger();
                fixture.reward(player -> {
                    helper.assertTrue(fixture.file.getCurrentPlayer() == fixture.player,
                        "FTB reward lost the submitting player's context");
                    rewards.incrementAndGet();
                    nested[0] = FtbQuestSubmission.submit(player, fixture.data, other, () -> List.of(nestedSource));
                });
                fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, List::of);
                helper.assertTrue(result.submitted() == 1 && rewards.get() == 1 && nested[0] != null
                    && nested[0].submitted() == 0 && nested[0].failed() && fixture.data.getProgress(other) == 0
                    && nestedSource.count(new ItemStack(Items.GOLD_INGOT)) == 2,
                    "Completion reward reentered another network submission for the same player");
            }
        }

        private static void repeatReset(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                CompoundTag config = new CompoundTag();
                fixture.quest.writeData(config);
                Tristate.TRUE.write(config, "can_repeat");
                fixture.quest.readData(config);
                AtomicInteger rewards = new AtomicInteger();
                fixture.reward(player -> rewards.incrementAndGet());
                fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
                MemorySource source = new MemorySource(new ItemStack(Items.IRON_INGOT, 20));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> List.of(source));
                helper.assertTrue(rewards.get() == 1 && fixture.data.getCompletionCount(fixture.quest) == 1
                    && fixture.data.getProgress(fixture.task) == 0 && !fixture.data.isCompleted(fixture.task),
                    "Real FTB automatic reward did not reset the repeatable task");
                helper.assertTrue(result.submitted() == 10 && result.remaining() == 0
                    && source.count(new ItemStack(Items.IRON_INGOT)) == 13
                    && count(fixture.player, new ItemStack(Items.IRON_INGOT)) == 0,
                    "Repeat callback changed settlement or consumed materials for another cycle");
            }
        }

        private static void rewardInventoryMutation(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                AtomicInteger rewards = new AtomicInteger();
                fixture.reward(player -> {
                    rewards.incrementAndGet();
                    helper.assertTrue(player.getInventory().getItem(0).isEmpty(),
                        "Completion callback still saw the items being submitted in the original backpack slot");
                    player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
                });
                fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 10));
                var result = FtbQuestSubmission.submit(fixture.player, fixture.data, fixture.task, () -> {
                    throw new AssertionError("Backpack reward submission unnecessarily opened remote storage");
                });
                helper.assertTrue(result.submitted() == 10 && rewards.get() == 1
                    && fixture.player.getInventory().getItem(0).is(Items.DIAMOND)
                    && fixture.player.getInventory().getItem(0).getCount() == 2,
                    "Submission overwrote a reward placed into the consumed backpack slot");
            }
        }

        private static void realSubmissionMessage(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper, new ItemStack(Items.IRON_INGOT), 10)) {
                ItemStack backpack = new ItemStack(net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems.BACKPACK.get());
                var wrapper = backpack.getCapability(
                    net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance())
                    .orElseThrow(IllegalStateException::new);
                helper.assertTrue(wrapper.getInventoryHandler().insertItem(0, new ItemStack(Items.IRON_INGOT, 8), false).isEmpty(),
                    "Real Sophisticated Backpack setup did not accept the test materials");
                fixture.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
                fixture.player.getInventory().setItem(1, backpack);
                new SubmitTaskMessage(fixture.task.id).handle(new NetworkManager.PacketContext() {
                    public Player getPlayer() { return fixture.player; }
                    public void queue(Runnable runnable) { runnable.run(); }
                    public Env getEnvironment() { return Env.SERVER; }
                });
                helper.assertTrue(fixture.data.getProgress(fixture.task) == 10 && fixture.data.isCompleted(fixture.task)
                    && wrapper.getInventoryHandler().getStackInSlot(0).getCount() == 1
                    && fixture.player.getInventory().getItem(1) == backpack
                    && count(fixture.player, new ItemStack(Items.IRON_INGOT)) == 0,
                    "The original FTB submit message did not submit the deficit from a real Sophisticated Backpack");
            }
        }

        private static ItemStack tagged(int count, String variant) {
            ItemStack stack = new ItemStack(Items.DIAMOND, count);
            stack.getOrCreateTag().putString("ftb_variant", variant);
            return stack;
        }

        private static int count(ServerPlayer player, ItemStack prototype) {
            return player.getInventory().items.stream()
                .filter(stack -> ItemStack.isSameItemSameTags(stack, prototype))
                .mapToInt(ItemStack::getCount).sum();
        }

        private static final class Fixture implements AutoCloseable {
            private final ServerQuestFile file;
            private final Chapter chapter;
            private final Quest quest;
            private final ItemTask task;
            private final ServerPlayer player;
            private final TeamData data;

            private Fixture(GameTestHelper helper, ItemStack prototype, int count) {
                file = ServerQuestFile.INSTANCE;
                helper.assertTrue(file != null, "Real FTB server quest file was not initialized");
                UUID playerId = UUID.randomUUID();
                player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(playerId, "ftb-submit"));
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
                task.setConsumeItems(Tristate.TRUE);
                quest.addTask(task);
                file.refreshIDMap();
            }

            private ItemTask addTask(ItemStack prototype, int count) {
                Quest otherQuest = new Quest(IDS.incrementAndGet(), chapter);
                chapter.addQuest(otherQuest);
                ItemTask other = new ItemTask(IDS.incrementAndGet(), otherQuest);
                other.setStackAndCount(prototype, count);
                other.setConsumeItems(Tristate.TRUE);
                otherQuest.addTask(other);
                file.refreshIDMap();
                return other;
            }

            private void reward(Consumer<ServerPlayer> callback) {
                quest.addReward(new CallbackReward(IDS.incrementAndGet(), quest, callback));
                file.refreshIDMap();
            }

            public void close() {
                file.getDefaultChapterGroup().removeChapter(chapter);
                file.refreshIDMap();
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
            private final Map<StackKey, Integer> stored = new LinkedHashMap<>();
            private int performBudget = Integer.MAX_VALUE;
            private boolean forbidPreview;

            private MemorySource(ItemStack... stacks) {
                for (ItemStack stack : stacks) stored.merge(StackKey.of(stack), stack.getCount(), Integer::sum);
            }

            private int count(ItemStack prototype) {
                return stored.getOrDefault(StackKey.of(prototype), 0);
            }

            public List<ItemStack> snapshot(Set<Item> wanted) {
                throw new AssertionError("FTB candidate discovery must use preview instead of a full storage snapshot");
            }

            public List<ItemStack> preview(Set<Item> wanted) {
                if (forbidPreview) throw new AssertionError("Exact item submission enumerated warehouse candidates");
                return stored.entrySet().stream().filter(entry -> wanted.contains(entry.getKey().item()))
                    .map(entry -> entry.getKey().stack(entry.getValue())).toList();
            }

            public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
                StackKey key = StackKey.of(prototype);
                int taken = Math.min(amount, stored.getOrDefault(key, 0));
                if (!simulate) taken = Math.min(taken, performBudget);
                if (taken <= 0) return ItemStack.EMPTY;
                if (!simulate) {
                    performBudget -= taken;
                    int remaining = stored.get(key) - taken;
                    if (remaining == 0) stored.remove(key);
                    else stored.put(key, remaining);
                }
                return prototype.copyWithCount(taken);
            }

            public ItemStack insert(ItemStack stack) {
                if (!stack.isEmpty()) stored.merge(StackKey.of(stack), stack.getCount(), Integer::sum);
                return ItemStack.EMPTY;
            }

            public void finish(int extracted) {}
        }
    }
}

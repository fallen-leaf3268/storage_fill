package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.StorageFill;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems;
import net.p3pp3rf1y.sophisticatedcore.settings.memory.MemorySettingsCategory;
import net.p3pp3rf1y.sophisticatedcore.upgrades.infinity.InfinityInventoryPart;
import net.p3pp3rf1y.sophisticatedcore.util.SlotRange;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class SophisticatedRewardGameTests {
    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void backpackRewardPartiallyMergesWithoutChangingLegacyRefund(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.backpackRewardPartiallyMergesWithoutChangingLegacyRefund(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void backpackRewardHonorsNativeMemoryFiltersAndNestedBackpackRules(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.backpackRewardHonorsNativeMemoryFiltersAndNestedBackpackRules(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void backpackRewardDoesNotReachVoidInsertResponse(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.backpackRewardDoesNotReachVoidInsertResponse(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void staleBackpackRewardSourceCannotWriteRemovedBackpack(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.staleBackpackRewardSourceCannotWriteRemovedBackpack(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void backpackRewardCallbackFailureReturnsOnlyUncommittedItems(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.backpackRewardCallbackFailureReturnsOnlyUncommittedItems(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void backpackRewardDoesNotCreateInfiniteTemplateFromEmptySlot(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.backpackRewardDoesNotCreateInfiniteTemplateFromEmptySlot(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void taggedBackpackRewardUsesEmptySlotWithoutMergingIdenticalVariant(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.taggedBackpackRewardUsesEmptySlotWithoutMergingIdenticalVariant(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void taggedBackpackRewardReturnsWholeStackWhenOnlyMergeCapacityRemains(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.taggedBackpackRewardReturnsWholeStackWhenOnlyMergeCapacityRemains(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void plainBackpackRewardDoesNotMergeIntoTaggedVariant(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.plainBackpackRewardDoesNotMergeIntoTaggedVariant(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void taggedBackpackRewardHonorsOneEmptySlotCapacityAndExactRemainder(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.taggedBackpackRewardHonorsOneEmptySlotCapacityAndExactRemainder(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void emptyCompoundTagBackpackRewardAlsoAvoidsExistingStack(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.emptyCompoundTagBackpackRewardAlsoAvoidsExistingStack(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void capabilityOnlyBackpackRewardUsesEmptySlotWithoutMerging(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.capabilityOnlyBackpackRewardUsesEmptySlotWithoutMerging(helper);
    }

    @GameTest(template = "empty", batch = "storage_sb_reward")
    public static void taggedBackpackRewardCallbackFailureReturnsOnlyUncommittedItems(GameTestHelper helper) {
        if (!available(helper)) return;
        Cases.taggedBackpackRewardCallbackFailureReturnsOnlyUncommittedItems(helper);
    }

    private static boolean available(GameTestHelper helper) {
        if (ModList.get().isLoaded("sophisticatedbackpacks")) return true;
        StorageFill.LOGGER.warn("SKIPPED backpack reward GameTest: Sophisticated Backpacks is absent");
        helper.succeed();
        return false;
    }

    private static final class Cases {
        public static void backpackRewardPartiallyMergesWithoutChangingLegacyRefund(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            var handler = f.wrapper.getInventoryHandler();
            fill(f, new ItemStack(Items.STONE, 64));
            handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 62));
            ItemStack reward = new ItemStack(Items.DIAMOND, 10);
            helper.assertTrue(f.source.insert(reward).getCount() == 10 && handler.getStackInSlot(0).getCount() == 62,
                "Reward support changed the existing JEI refund insertion contract");
            ItemStack remaining = f.source.insertReward(reward);
            helper.assertTrue(remaining.getCount() == 8 && StackKey.of(remaining).equals(StackKey.of(reward))
                && handler.getStackInSlot(0).getCount() == 64 && reward.getCount() == 10,
                "Partial reward insertion changed its input or lost the exact remaining quantity and NBT");
            helper.succeed();
        }

        public static void backpackRewardHonorsNativeMemoryFiltersAndNestedBackpackRules(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            var handler = f.wrapper.getInventoryHandler();
            var memory = f.wrapper.getSettingsHandler().getTypeCategory(MemorySettingsCategory.class);
            for (int slot = 0; slot < handler.getSlots(); slot++) memory.setFilter(slot, new ItemStack(Items.IRON_INGOT));
            helper.assertFalse(handler.isItemValid(0, tagged(1), f.player), "Native memory filter fixture did not reject diamonds");
            helper.assertTrue(f.source.insertReward(tagged(5)).getCount() == 5,
                "Reward insertion bypassed native backpack memory filters");
            memory.unselectAllSlots();
            ItemStack nested = new ItemStack(ModItems.BACKPACK.get());
            helper.assertFalse(handler.isItemValid(0, nested, f.player), "Native nested-backpack fixture did not reject nesting");
            helper.assertTrue(f.source.insertReward(nested).getCount() == 1 && handler.getStackInSlot(0).isEmpty(),
                "Reward insertion bypassed the native nested-backpack restriction");
            helper.succeed();
        }

        public static void backpackRewardDoesNotReachVoidInsertResponse(GameTestHelper helper) {
            if (!installed(helper)) return;
            boolean previous = net.p3pp3rf1y.sophisticatedbackpacks.Config.SERVER.voidUpgrade.voidAlwaysEnabled.get();
            net.p3pp3rf1y.sophisticatedbackpacks.Config.SERVER.voidUpgrade.voidAlwaysEnabled.set(true);
            try {
                Fixture f = new Fixture(helper);
                f.wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(ModItems.VOID_UPGRADE.get()));
                var upgrade = f.wrapper.getUpgradeHandler().getTypeWrappers(
                    net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeItem.TYPE).get(0);
                upgrade.setVoidType(net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidType.ALWAYS);
                upgrade.setShouldWorkdInGUI(false);
                upgrade.getFilterLogic().setAllowList(true);
                upgrade.getFilterLogic().setMatchNbt(false);
                upgrade.getFilterLogic().getFilterHandler().setStackInSlot(0, new ItemStack(Items.DIAMOND));
                var handler = f.wrapper.getInventoryHandler();
                helper.assertTrue(handler.insertItem(0, tagged(1), false).isEmpty() && handler.getStackInSlot(0).isEmpty(),
                    "Native void fixture did not swallow its insert-response input");
                helper.assertTrue(StorageSources.findForInsert(f.player, StorageSource.SOPHISTICATED_BACKPACKS)
                    .get(0).insertReward(tagged(9)).isEmpty()
                    && handler.getStackInSlot(0).getCount() == 9
                    && StackKey.of(handler.getStackInSlot(0)).equals(StackKey.of(tagged(1))),
                    "Reward insertion was destroyed or transformed by a void insert-response upgrade");
            } finally {
                net.p3pp3rf1y.sophisticatedbackpacks.Config.SERVER.voidUpgrade.voidAlwaysEnabled.set(previous);
            }
            helper.succeed();
        }

        public static void staleBackpackRewardSourceCannotWriteRemovedBackpack(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            f.player.getInventory().setItem(0, ItemStack.EMPTY);
            helper.assertTrue(f.source.insertReward(tagged(6)).getCount() == 6
                && f.wrapper.getInventoryHandler().getStackInSlot(0).isEmpty(),
                "Reward insertion wrote a backpack that was no longer carried");
            helper.succeed();
        }

        public static void backpackRewardCallbackFailureReturnsOnlyUncommittedItems(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            var handler = f.wrapper.getInventoryHandler();
            handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 62));
            f.wrapper.setInventorySlotChangeHandler(() -> { throw new IllegalStateException("Injected reward write-after-commit callback"); });
            ItemStack remaining = f.source.insertReward(new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(remaining.getCount() == 8 && handler.getStackInSlot(0).getCount() == 64
                && handler.getStackInSlot(1).isEmpty(),
                "A committed reward slot was returned again or insertion continued after its callback failed");
            helper.succeed();
        }

        public static void taggedBackpackRewardUsesEmptySlotWithoutMergingIdenticalVariant(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            var handler = f.wrapper.getInventoryHandler();
            fill(f, new ItemStack(Items.STONE, 64));
            ItemStack before = tagged(32);
            handler.setStackInSlot(0, before.copy());
            handler.setStackInSlot(1, ItemStack.EMPTY);
            ItemStack reward = tagged(7);
            ItemStack remaining = f.source.insertReward(reward);
            helper.assertTrue(remaining.isEmpty() && ItemStack.matches(before, handler.getStackInSlot(0))
                && ItemStack.matches(reward, handler.getStackInSlot(1)) && reward.getCount() == 7,
                "Tagged reward merged an existing identical variant instead of using the empty slot");
            helper.succeed();
        }

        public static void taggedBackpackRewardReturnsWholeStackWhenOnlyMergeCapacityRemains(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            var handler = f.wrapper.getInventoryHandler();
            fill(f, new ItemStack(Items.STONE, 64));
            ItemStack before = tagged(32);
            handler.setStackInSlot(0, before.copy());
            ItemStack reward = tagged(7);
            ItemStack remaining = f.source.insertReward(reward);
            helper.assertTrue(ItemStack.matches(reward, remaining) && ItemStack.matches(before, handler.getStackInSlot(0))
                && reward.getCount() == 7,
                "Tagged reward consumed merge-only capacity in a backpack with no empty slots");
            helper.succeed();
        }

        public static void plainBackpackRewardDoesNotMergeIntoTaggedVariant(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            var handler = f.wrapper.getInventoryHandler();
            fill(f, new ItemStack(Items.STONE, 64));
            ItemStack before = tagged(62);
            handler.setStackInSlot(0, before.copy());
            handler.setStackInSlot(1, ItemStack.EMPTY);
            ItemStack reward = new ItemStack(Items.DIAMOND, 10);
            ItemStack remaining = f.source.insertReward(reward);
            helper.assertTrue(remaining.isEmpty() && ItemStack.matches(before, handler.getStackInSlot(0))
                && ItemStack.matches(reward, handler.getStackInSlot(1)) && reward.getCount() == 10,
                "Plain reward mixed into a tagged variant or failed to use the ordinary empty slot");
            helper.succeed();
        }

        public static void taggedBackpackRewardHonorsOneEmptySlotCapacityAndExactRemainder(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            var handler = f.wrapper.getInventoryHandler();
            fill(f, new ItemStack(Items.STONE, 64));
            ItemStack before = tagged(62);
            handler.setStackInSlot(0, before.copy());
            handler.setStackInSlot(1, ItemStack.EMPTY);
            ItemStack reward = tagged(70);
            ItemStack remaining = f.source.insertReward(reward);
            helper.assertTrue(ItemStack.matches(before, handler.getStackInSlot(0))
                && ItemStack.matches(tagged(64), handler.getStackInSlot(1))
                && ItemStack.matches(tagged(6), remaining) && reward.getCount() == 70,
                "Tagged reward exceeded one empty slot, changed the existing stack, or returned an inexact remainder");
            helper.succeed();
        }

        public static void emptyCompoundTagBackpackRewardAlsoAvoidsExistingStack(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            var handler = f.wrapper.getInventoryHandler();
            fill(f, new ItemStack(Items.STONE, 64));
            ItemStack before = new ItemStack(Items.DIAMOND, 62);
            before.setTag(new CompoundTag());
            handler.setStackInSlot(0, before.copy());
            handler.setStackInSlot(1, ItemStack.EMPTY);
            ItemStack reward = new ItemStack(Items.DIAMOND, 2);
            reward.setTag(new CompoundTag());
            helper.assertTrue(StackKey.of(reward).data().contains("tag", Tag.TAG_COMPOUND)
                && reward.getTag() != null && reward.getTag().isEmpty(),
                "Empty-tag fixture did not preserve a serialized empty compound");
            ItemStack remaining = f.source.insertReward(reward);
            helper.assertTrue(remaining.isEmpty() && ItemStack.matches(before, handler.getStackInSlot(0))
                && ItemStack.matches(reward, handler.getStackInSlot(1)) && reward.getCount() == 2,
                "A reward with an explicitly empty compound tag merged into an existing stack");
            helper.succeed();
        }

        public static void capabilityOnlyBackpackRewardUsesEmptySlotWithoutMerging(GameTestHelper helper) {
            if (!installed(helper)) return;
            ResourceLocation id = new ResourceLocation(StorageFill.ID, "reward_test_capability");
            Map<ItemStack, RewardCapabilityData> providers = new IdentityHashMap<>();
            Consumer<AttachCapabilitiesEvent<ItemStack>> attach = event -> {
                if (event.getObject().is(Items.DIAMOND)) {
                    RewardCapabilityData provider = new RewardCapabilityData();
                    providers.put(event.getObject(), provider);
                    event.addCapability(id, provider);
                }
            };
            MinecraftForge.EVENT_BUS.addGenericListener(ItemStack.class, attach);
            try {
                Fixture f = new Fixture(helper);
                var handler = f.wrapper.getInventoryHandler();
                fill(f, new ItemStack(Items.STONE, 64));
                ItemStack before = new ItemStack(Items.DIAMOND, 32);
                ItemStack reward = new ItemStack(Items.DIAMOND, 7);
                before.getCapability(ForgeCapabilities.ITEM_HANDLER);
                reward.getCapability(ForgeCapabilities.ITEM_HANDLER);
                helper.assertTrue(providers.containsKey(before) && providers.containsKey(reward),
                    "Native attachment event did not provide the capability-only fixtures");
                providers.get(before).state = 37;
                providers.get(reward).state = 37;
                StackKey beforeKey = StackKey.of(before);
                StackKey rewardKey = StackKey.of(reward);
                ItemStack copiedReward = reward.copy();
                copiedReward.getCapability(ForgeCapabilities.ITEM_HANDLER);
                helper.assertTrue(before.getTag() == null && reward.getTag() == null
                    && beforeKey.data().contains("ForgeCaps", Tag.TAG_COMPOUND)
                    && rewardKey.data().getCompound("ForgeCaps").getCompound(id.toString()).getInt("state") == 37
                    && providers.containsKey(copiedReward) && providers.get(copiedReward).state == 37
                    && copiedReward.save(new CompoundTag()).getCompound("ForgeCaps")
                        .getCompound(id.toString()).getInt("state") == 37,
                    "Capability-only fixture lost serialized state during a native copy");
                handler.setStackInSlot(0, before.copy());
                handler.setStackInSlot(1, ItemStack.EMPTY);
                ItemStack remaining = f.source.insertReward(reward);
                helper.assertTrue(remaining.isEmpty() && reward.getCount() == 7
                    && handler.getStackInSlot(0).getCount() == 32
                    && beforeKey.equals(StackKey.of(handler.getStackInSlot(0)))
                    && handler.getStackInSlot(1).getCount() == 7
                    && rewardKey.equals(StackKey.of(handler.getStackInSlot(1)))
                    && rewardKey.equals(StackKey.of(reward)),
                    "Capability-only reward merged the existing stack or lost its serialized data or quantity");
                for (int slot = 2; slot < handler.getSlots(); slot++) helper.assertTrue(
                    ItemStack.matches(new ItemStack(Items.STONE, 64), handler.getStackInSlot(slot)),
                    "Capability-only reward changed an unrelated backpack slot");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(attach);
                providers.clear();
            }
            helper.succeed();
        }

        public static void taggedBackpackRewardCallbackFailureReturnsOnlyUncommittedItems(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            var handler = f.wrapper.getInventoryHandler();
            fill(f, new ItemStack(Items.STONE, 64));
            handler.setStackInSlot(0, ItemStack.EMPTY);
            f.wrapper.setInventorySlotChangeHandler(() -> { throw new IllegalStateException("Injected tagged reward write-after-commit callback"); });
            ItemStack reward = tagged(70);
            ItemStack remaining = f.source.insertReward(reward);
            helper.assertTrue(ItemStack.matches(tagged(64), handler.getStackInSlot(0))
                && ItemStack.matches(tagged(6), remaining) && reward.getCount() == 70,
                "A committed tagged reward was returned again or its exact remainder was lost");
            for (int slot = 1; slot < handler.getSlots(); slot++) helper.assertTrue(
                ItemStack.matches(new ItemStack(Items.STONE, 64), handler.getStackInSlot(slot)),
                "Tagged reward insertion continued after its write callback failed");
            helper.succeed();
        }

        public static void backpackRewardDoesNotCreateInfiniteTemplateFromEmptySlot(GameTestHelper helper) {
            if (!installed(helper)) return;
            Fixture f = new Fixture(helper);
            var handler = f.wrapper.getInventoryHandler();
            var range = new SlotRange(0, handler.getSlots());
            handler.getInventoryPartitioner().addInventoryPart(0, handler.getSlots(), new InfinityInventoryPart(handler, range, 0) {
                public String getName() { return "reward_test_infinity"; }
            });
            helper.assertTrue(f.source.insertReward(tagged(3)).getCount() == 3,
                "Reward insertion treated an empty infinite-template slot as ordinary finite storage");
            for (int slot = 0; slot < handler.getSlots(); slot++) helper.assertTrue(handler.getStackInSlot(slot).isEmpty(),
                "Reward insertion created an infinite item template");
            helper.succeed();
        }

        private static void fill(Fixture f, ItemStack stack) {
            var handler = f.wrapper.getInventoryHandler();
            for (int slot = 0; slot < handler.getSlots(); slot++) handler.setStackInSlot(slot, stack.copy());
        }

        private static ItemStack tagged(int count) {
            ItemStack stack = new ItemStack(Items.DIAMOND, count);
            stack.getOrCreateTag().putString("reward_variant", "exact");
            return stack;
        }

        private static boolean installed(GameTestHelper helper) {
            if (ModList.get().isLoaded("sophisticatedbackpacks")) return true;
            StorageFill.LOGGER.warn("SKIPPED backpack reward GameTest: Sophisticated Backpacks is absent");
            helper.succeed();
            return false;
        }

        private static final class RewardCapabilityData implements ICapabilitySerializable<CompoundTag> {
            private int state;

            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                return LazyOptional.empty();
            }

            @Override
            public CompoundTag serializeNBT() {
                CompoundTag tag = new CompoundTag();
                tag.putInt("state", state);
                return tag;
            }

            @Override
            public void deserializeNBT(CompoundTag tag) {
                state = tag.getInt("state");
            }
        }

        private static final class Fixture {
            private final ServerPlayer player;
            private final IBackpackWrapper wrapper;
            private final StorageSource source;

            private Fixture(GameTestHelper helper) {
                player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "sb-reward"));
                ItemStack backpack = new ItemStack(ModItems.BACKPACK.get());
                wrapper = backpack.getCapability(CapabilityBackpackWrapper.getCapabilityInstance()).orElseThrow(IllegalStateException::new);
                player.getInventory().setItem(0, backpack);
                var sources = StorageSources.findForInsert(player, StorageSource.SOPHISTICATED_BACKPACKS);
                helper.assertTrue(sources.size() == 1, "Reward fixture did not find its real backpack");
                source = sources.get(0);
            }
        }
    }
}

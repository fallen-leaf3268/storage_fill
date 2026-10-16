package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StorageFill;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class StorageCountGameTests {
    @GameTest(template = "empty")
    public static void fallbackUsesLongCountsAndDetachedSingleItemPrototypes(GameTestHelper helper) {
        List<ItemStack> contents = List.of(tagged(1, Integer.MAX_VALUE), tagged(2, Integer.MAX_VALUE),
            new ItemStack(Items.EMERALD, 8));
        StorageSource source = new StorageSource() {
            public List<ItemStack> snapshot(Set<Item> wanted) { throw new AssertionError("Count bypassed preview"); }
            public List<ItemStack> preview(Set<Item> wanted) {
                helper.assertTrue(wanted.equals(Set.of(Items.DIAMOND)), "Count requested unrelated item types");
                return contents;
            }
            public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
                throw new AssertionError("Fallback count extracted materials");
            }
            public ItemStack insert(ItemStack stack) { throw new AssertionError("Fallback count inserted materials"); }
            public void finish(int extracted) { throw new AssertionError("Fallback count completed a transfer"); }
        };
        int[] visits = {0};
        helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> {
            visits[0]++;
            helper.assertTrue(stack.getCount() == 1, "Count exposed a material quantity as a matching prototype");
            stack.setCount(0);
            return true;
        }, 3_000_000_000L) == 3_000_000_000L && visits[0] == 2,
            "Fallback count overflowed or did not stop at its long limit");
        helper.assertTrue(contents.get(0).getCount() == Integer.MAX_VALUE
            && contents.get(1).getCount() == Integer.MAX_VALUE, "Count exposed mutable stored material stacks");
        helper.assertTrue(source.countMatching(Items.DIAMOND,
            stack -> ItemStack.isSameItemSameTags(stack, tagged(2, 1)), Long.MAX_VALUE) == Integer.MAX_VALUE,
            "Fallback count ignored matching NBT");
        helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> {
            throw new AssertionError("Zero limit still matched materials");
        }, 0) == 0, "Zero limit returned materials");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bdNativeLongCountsSaturateWithoutSnapshotOrMembershipBypass(GameTestHelper helper) {
        if (!installed(helper, "beyonddimensions")) return;
        BeyondDimensionsCases.run(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void backpackCountCombinesSlotsAndRevalidatesCarriedStorage(GameTestHelper helper) {
        if (!installed(helper, "sophisticatedbackpacks")) return;
        BackpackCases.run(helper);
        helper.succeed();
    }

    private static boolean installed(GameTestHelper helper, String mod) {
        if (ModList.get().isLoaded(mod)) return true;
        StorageFill.LOGGER.warn("SKIPPED storage count GameTest: {} is absent", mod);
        helper.succeed();
        return false;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "storage-count"));
    }

    private static ItemStack tagged(int variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        stack.getOrCreateTag().putInt("count_variant", variant);
        return stack;
    }

    private static final class BeyondDimensionsCases {
        private static void run(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            ServerPlayer owner = player(helper);
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(owner, Long.MAX_VALUE, 64);
            helper.assertTrue(network != null, "Native BD count fixture did not create a network");
            try {
                network.addPlayer(player.getUUID());
                com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.setPrimaryNetForPlayer(player, network);
                var first = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(tagged(1, 1));
                var second = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(tagged(2, 1));
                var plain = new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(new ItemStack(Items.DIAMOND));
                var storage = network.getUnifiedStorage();
                helper.assertTrue(storage.insert(first, Long.MAX_VALUE - 50, false).isEmpty()
                    && storage.insert(second, 100, false).isEmpty()
                    && storage.insert(plain, 40, false).isEmpty(), "Native BD long quantity seed failed");
                List<StorageSource> sources = new ArrayList<>();
                BeyondDimensionsSource.find(player, sources);
                helper.assertTrue(sources.size() == 1, "Native BD count fixture did not discover one network");
                StorageSource source = sources.get(0);
                helper.assertTrue(source.changeIdentity() == network, "BD insertion and count identities differ");
                int[] plainVisits = {0};
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> {
                    plainVisits[0]++;
                    return true;
                }, 20) == 20 && plainVisits[0] == 1,
                    "BD plain-item count did not stop before matching other inventory variants");
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> !stack.hasTag(), Long.MAX_VALUE) == 40,
                    "BD plain-item fast lookup was counted again during the variant scan");
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> {
                    helper.assertTrue(stack.getCount() == 1, "BD matcher did not receive a single-item prototype");
                    return true;
                }, Long.MAX_VALUE) == Long.MAX_VALUE, "BD long count overflowed or retained the JEI cap");
                helper.assertTrue(source.countMatching(Items.DIAMOND,
                    stack -> ItemStack.isSameItemSameTags(stack, tagged(2, 1)), Long.MAX_VALUE) == 100,
                    "BD native count merged NBT variants");
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> true, 6_000) == 6_000,
                    "BD native count ignored the requested limit");
                helper.assertTrue(storage.getStackByKey(first).amount() == Long.MAX_VALUE - 50
                    && storage.getStackByKey(second).amount() == 100 && storage.getStackByKey(plain).amount() == 40,
                    "BD count changed stored quantities");
                network.removePlayer(player.getUUID());
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> true, Long.MAX_VALUE) == 0,
                    "BD native count retained revoked membership access");
            } finally {
                network.destroySelf();
            }
        }
    }

    private static final class BackpackCases {
        private static void run(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            ItemStack backpack = new ItemStack(net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems.BACKPACK.get());
            var wrapper = backpack.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper
                .getCapabilityInstance()).orElseThrow(IllegalStateException::new);
            var handler = wrapper.getInventoryHandler();
            handler.setStackInSlot(0, tagged(1, 6_000));
            handler.setStackInSlot(1, tagged(1, 40));
            handler.setStackInSlot(2, tagged(2, 19));
            handler.setStackInSlot(3, new ItemStack(Items.EMERALD, 7));
            player.getInventory().setItem(0, backpack);
            List<StorageSource> sources = new ArrayList<>();
            SophisticatedBackpacksSource.find(player, sources);
            helper.assertTrue(sources.size() == 1, "Native backpack count fixture did not discover one source");
            StorageSource source = sources.get(0);
            helper.assertTrue(source.changeIdentity() == handler, "Backpack insertion and count identities differ");
            helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> {
                helper.assertTrue(stack.getCount() == 1, "Backpack matcher received aggregate quantities");
                return true;
            }, Long.MAX_VALUE) == 6_059, "Backpack count retained JEI cap or included an unrelated item");
            helper.assertTrue(source.countMatching(Items.DIAMOND,
                stack -> ItemStack.isSameItemSameTags(stack, tagged(1, 1)), Long.MAX_VALUE) == 6_040,
                "Backpack count merged NBT variants or omitted a matching slot");
            helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> true, 6_010) == 6_010,
                "Backpack count ignored the requested limit");
            helper.assertTrue(handler.getStackInSlot(0).getCount() == 6_000
                && handler.getStackInSlot(1).getCount() == 40 && handler.getStackInSlot(2).getCount() == 19,
                "Backpack count performed an extraction");
            player.getInventory().setItem(0, ItemStack.EMPTY);
            helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> true, Long.MAX_VALUE) == 0,
                "Backpack count retained access after removal");
        }
    }
}

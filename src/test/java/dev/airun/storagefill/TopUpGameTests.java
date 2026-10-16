package dev.airun.storagefill;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.storage.StorageSource;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class TopUpGameTests {
    @GameTest(template = "empty")
    public static void localCountAndNbtAreRespected(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack local = tagged(Items.DIAMOND, 3, "a");
        player.getInventory().setItem(0, local);
        MemorySource source = new MemorySource(tagged(Items.DIAMOND, 8, "a"));
        helper.assertTrue(TransferService.topUp(player, player.containerMenu.containerId,
                player.containerMenu.getStateId(), List.of(tagged(Items.DIAMOND, 5, "a")), List.of(source)), "Top-up failed");
        helper.assertTrue(source.count() == 6 && player.getInventory().getItem(0).getCount() == 5, "Local count was not credited");
        MemorySource other = new MemorySource(tagged(Items.DIAMOND, 2, "b"));
        helper.assertTrue(TransferService.topUp(player, player.containerMenu.containerId,
                player.containerMenu.getStateId(), List.of(tagged(Items.DIAMOND, 2, "b")), List.of(other)), "NBT top-up failed");
        helper.assertTrue(!StackKey.of(player.getInventory().getItem(0)).equals(StackKey.of(player.getInventory().getItem(1))), "NBT variants merged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void combinesSourcesAndSupportsLargeTotals(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MemorySource first = new MemorySource(new ItemStack(Items.OAK_PLANKS, 64));
        MemorySource second = new MemorySource(new ItemStack(Items.OAK_PLANKS, 66));
        helper.assertTrue(TransferService.topUp(player, player.containerMenu.containerId,
                player.containerMenu.getStateId(), List.of(new ItemStack(Items.OAK_PLANKS, 130)), List.of(first, second)), "Multi-source top-up failed");
        helper.assertTrue(first.count() == 0 && second.count() == 0 && inventoryCount(player, Items.OAK_PLANKS) == 130, "Wrong combined amount");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void capacityAndSimulatedShortageDoNotConsume(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        MemorySource full = new MemorySource(new ItemStack(Items.DIAMOND, 1));
        helper.assertFalse(TransferService.topUp(player, player.containerMenu.containerId,
                player.containerMenu.getStateId(), List.of(new ItemStack(Items.DIAMOND)), List.of(full)), "Full inventory accepted");
        helper.assertTrue(full.count() == 1, "Capacity failure consumed storage");
        player.getInventory().setItem(0, ItemStack.EMPTY);
        MemorySource shortSource = new MemorySource(new ItemStack(Items.DIAMOND, 1));
        helper.assertFalse(TransferService.topUp(player, player.containerMenu.containerId,
                player.containerMenu.getStateId(), List.of(new ItemStack(Items.DIAMOND, 2)), List.of(shortSource)), "Shortage accepted");
        helper.assertTrue(shortSource.count() == 1, "Simulated shortage consumed storage");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void executionFailureRefundsEarlierSources(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MemorySource first = new MemorySource(new ItemStack(Items.OAK_PLANKS, 1));
        MemorySource stale = new MemorySource(new ItemStack(Items.OAK_PLANKS, 1));
        stale.failPerform = true;
        helper.assertFalse(TransferService.topUp(player, player.containerMenu.containerId,
                player.containerMenu.getStateId(), List.of(new ItemStack(Items.OAK_PLANKS, 2)), List.of(first, stale)), "Stale extraction accepted");
        helper.assertTrue(first.count() == 1 && stale.count() == 1 && inventoryCount(player, Items.OAK_PLANKS) == 0, "Earlier extraction was not refunded");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rejectsStaleStateAndAcceptsEmptyRequirements(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        MemorySource source = new MemorySource(new ItemStack(Items.DIAMOND, 1));
        helper.assertFalse(TransferService.topUp(player, player.containerMenu.containerId,
                player.containerMenu.getStateId() + 1, List.of(new ItemStack(Items.DIAMOND)), List.of(source)), "Stale state accepted");
        helper.assertTrue(source.count() == 1, "Stale state consumed storage");
        helper.assertTrue(TransferService.topUp(player, player.containerMenu.containerId,
                player.containerMenu.getStateId(), List.of(), List.of(source)), "Empty requirement rejected");
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "top-up-test"));
        player.containerMenu = new CraftingMenu(7, player.getInventory());
        return player;
    }

    private static ItemStack tagged(Item item, int count, String value) {
        ItemStack stack = new ItemStack(item, count);
        stack.getOrCreateTag().putString("variant", value);
        return stack;
    }

    private static int inventoryCount(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) total += stack.getCount();
        return total;
    }

    private static final class MemorySource implements StorageSource {
        private final ItemStack stored;
        private boolean failPerform;

        private MemorySource(ItemStack stored) { this.stored = stored.copy(); }
        private int count() { return stored.getCount(); }
        public List<ItemStack> snapshot(Set<Item> wanted) {
            return !stored.isEmpty() && wanted.contains(stored.getItem()) ? List.of(stored.copy()) : List.of();
        }
        public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
            if (!ItemStack.isSameItemSameTags(prototype, stored) || (!simulate && failPerform)) return ItemStack.EMPTY;
            int count = Math.min(amount, stored.getCount());
            ItemStack result = stored.copyWithCount(count);
            if (!simulate) stored.shrink(count);
            return result;
        }
        public ItemStack insert(ItemStack stack) {
            if (!ItemStack.isSameItemSameTags(stack, stored) && !stored.isEmpty()) return stack;
            stored.setCount(stored.getCount() + stack.getCount());
            return ItemStack.EMPTY;
        }
        public void finish(int extracted) { }
    }
}

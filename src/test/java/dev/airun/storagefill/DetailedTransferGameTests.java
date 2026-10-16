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

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class DetailedTransferGameTests {
    @GameTest(template = "empty")
    public static void actualCraftingGridStateChangesDoNotAbortOwnTransfer(GameTestHelper helper) {
        var pos = new net.minecraft.core.BlockPos(1, 1, 1);
        helper.setBlock(pos, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
        var absolute = helper.absolutePos(pos);
        ServerPlayer player = player(helper);
        player.setPos(absolute.getX() + 0.5, absolute.getY() + 1, absolute.getZ() + 0.5);
        player.containerMenu = new CraftingMenu(7, player.getInventory(),
            net.minecraft.world.inventory.ContainerLevelAccess.create(helper.getLevel(), absolute));
        player.containerMenu.getSlot(1).set(new ItemStack(Items.OAK_PLANKS));
        int before = player.containerMenu.getStateId();
        MemorySource source = new MemorySource(Items.OAK_PLANKS, 1, false);
        var request = new FillNetwork.Fill(7, before, 1, List.of(
            new FillNetwork.Input(1, List.of(new ItemStack(Items.OAK_PLANKS))),
            new FillNetwork.Input(2, List.of(new ItemStack(Items.OAK_PLANKS)))),
            java.util.stream.IntStream.range(10, 46).toArray(), false, true);
        TransferResult result = TransferService.fillDetailed(player, request, List.of(source));
        helper.assertTrue(result.success(), "Own crafting-grid update was rejected as stale menu: " + result.message());
        helper.assertTrue(player.containerMenu.getStateId() != before && source.amount == 0
            && player.containerMenu.getSlot(1).getItem().getCount() == 1
            && player.containerMenu.getSlot(2).getItem().getCount() == 1, "Real crafting grid did not fill and conserve materials");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void topUpReportsExactMissingAndExtractionFailure(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        TransferResult missing = TransferService.topUpDetailed(player, 7, player.containerMenu.getStateId(),
            List.of(new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.IRON_INGOT)), List.of());
        helper.assertFalse(missing.success(), "Missing materials succeeded");
        helper.assertTrue(messageArgument(missing).contains("Diamond × 2")
            && messageArgument(missing).contains("Iron Ingot × 1"), "Missing counts were not preserved");
        MemorySource stale = new MemorySource(Items.DIAMOND, 2, true);
        TransferResult extraction = TransferService.topUpDetailed(player, 7, player.containerMenu.getStateId(),
            List.of(new ItemStack(Items.DIAMOND, 2)), List.of(stale));
        helper.assertTrue(!extraction.success() && stale.amount == 2, "Stale extraction changed material");
        helper.assertTrue(messageArgument(extraction).contains("Diamond × 2"), "Stale extraction did not identify unavailable material and quantity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void topUpDistinguishesFullInventoryAndStaleMenu(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        TransferResult full = TransferService.topUpDetailed(player, 7, player.containerMenu.getStateId(),
            List.of(new ItemStack(Items.DIAMOND)), List.of(new MemorySource(Items.DIAMOND, 1, false)));
        helper.assertTrue(full.message().toString().contains("inventory_full"), "Full inventory was misreported");
        TransferResult stale = TransferService.topUpDetailed(player, 7, player.containerMenu.getStateId() + 1,
            List.of(new ItemStack(Items.DIAMOND)), List.of());
        helper.assertTrue(stale.message().toString().contains("menu_changed"), "Stale menu was misreported");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void standardFillReportsSharedMaterialDeficit(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        FillNetwork.Fill request = request(player, List.of(
            new FillNetwork.Input(1, List.of(new ItemStack(Items.OAK_PLANKS))),
            new FillNetwork.Input(2, List.of(new ItemStack(Items.OAK_PLANKS)))));
        TransferResult result = TransferService.fillDetailed(player, request,
            List.of(new MemorySource(Items.OAK_PLANKS, 1, false)));
        helper.assertTrue(!result.success() && messageArgument(result).contains("Oak Planks × 1"),
            "Shared material deficit was not aggregated accurately");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void standardFillKeepsNbtNameInDeficit(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack red = new ItemStack(Items.DIAMOND);
        red.setHoverName(net.minecraft.network.chat.Component.literal("Red Diamond"));
        ItemStack blue = new ItemStack(Items.DIAMOND);
        blue.setHoverName(net.minecraft.network.chat.Component.literal("Blue Diamond"));
        FillNetwork.Fill request = request(player, List.of(new FillNetwork.Input(1, List.of(red))));
        TransferResult result = TransferService.fillDetailed(player, request, List.of(new StackSource(blue)));
        helper.assertTrue(!result.success() && messageArgument(result).contains("Red Diamond × 1"),
            "NBT-specific missing name was lost");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void standardFillReportsAlternativeCombination(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        FillNetwork.Fill request = request(player, List.of(new FillNetwork.Input(1,
            List.of(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.BIRCH_PLANKS)))));
        TransferResult result = TransferService.fillDetailed(player, request, List.of());
        helper.assertTrue(!result.success() && result.message().toString().contains("missing_alternatives")
            && result.message().toString().contains("× 1"), "Alternative deficit was reported without a usable combination");
        helper.succeed();
    }

    private static FillNetwork.Fill request(ServerPlayer player, List<FillNetwork.Input> inputs) {
        return new FillNetwork.Fill(player.containerMenu.containerId, player.containerMenu.getStateId(), 1,
            inputs, IntStream.range(10, 46).toArray(), false, true);
    }

    private static String messageArgument(TransferResult result) {
        if (result.message().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
            && contents.getArgs().length > 0 && contents.getArgs()[0] instanceof net.minecraft.network.chat.Component component) {
            return component.getString();
        }
        return result.message().getString();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "detail-test"));
        player.containerMenu = new CraftingMenu(7, player.getInventory());
        return player;
    }

    private static final class MemorySource implements StorageSource {
        private final Item item;
        private int amount;
        private final boolean stale;
        private MemorySource(Item item, int amount, boolean stale) { this.item = item; this.amount = amount; this.stale = stale; }
        public List<ItemStack> snapshot(Set<Item> wanted) { return wanted.contains(item) ? List.of(new ItemStack(item, amount)) : List.of(); }
        public ItemStack extract(ItemStack prototype, int count, boolean simulate) {
            if (!prototype.is(item) || (!simulate && stale)) return ItemStack.EMPTY;
            int taken = Math.min(count, amount);
            if (!simulate) amount -= taken;
            return new ItemStack(item, taken);
        }
        public ItemStack insert(ItemStack stack) { amount += stack.getCount(); return ItemStack.EMPTY; }
        public void finish(int extracted) { }
    }

    private static final class StackSource implements StorageSource {
        private final ItemStack stack;
        private StackSource(ItemStack stack) { this.stack = stack.copy(); }
        public List<ItemStack> snapshot(Set<Item> wanted) { return wanted.contains(stack.getItem()) ? List.of(stack.copy()) : List.of(); }
        public ItemStack extract(ItemStack prototype, int count, boolean simulate) { return ItemStack.EMPTY; }
        public ItemStack insert(ItemStack stack) { return stack; }
        public void finish(int extracted) { }
    }
}

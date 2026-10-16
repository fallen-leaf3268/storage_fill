package dev.airun.storagefill;

import dev.airun.storagefill.storage.StorageSource;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class SnapshotGameTests {
    @GameTest(template = "empty")
    public static void snapshotsRemainIndependentUntilExplicitNewRequest(GameTestHelper helper) {
        ItemStack carrots = new ItemStack(Items.CARROT, 4);
        MemorySource source = new MemorySource(List.of(carrots, new ItemStack(Items.DIAMOND, 8)));
        var initial = FillNetwork.collectInitialSnapshot(7, 8, List.of(source));
        helper.assertTrue(source.reads == 1 && initial.get(0).stacks().size() == 2,
            "Initial snapshot did not read each source once");
        carrots.setCount(1);
        helper.assertTrue(initial.get(0).stacks().stream().filter(stack -> stack.is(Items.CARROT))
            .findFirst().orElseThrow().getCount() == 4, "Storage changes mutated the old snapshot");
        var refreshed = FillNetwork.collectInitialSnapshot(7, 9, List.of(source));
        helper.assertTrue(source.reads == 2 && refreshed.get(0).request() == 9
            && refreshed.get(0).stacks().stream().filter(stack -> stack.is(Items.CARROT))
                .findFirst().orElseThrow().getCount() == 1, "Explicit refresh did not capture current stock");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void allInventoryIsScannedOnceAndSplitWithoutLosingNbt(GameTestHelper helper) {
        List<ItemStack> stock = new ArrayList<>();
        for (int i = 0; i < 2050; i++) {
            ItemStack stack = new ItemStack(Items.DIAMOND, 128);
            stack.getOrCreateTag().putInt("variant", i);
            stock.add(stack);
        }
        stock.add(new ItemStack(Items.CARROT, 9));
        MemorySource source = new MemorySource(stock);
        List<FillNetwork.Snapshot> pages = FillNetwork.collectInitialSnapshot(7, 5, List.of(source));
        helper.assertTrue(source.reads == 1, "Session query rescanned one source");
        helper.assertTrue(pages.size() >= 3, "Inventory did not split into multiple bounded pages");
        List<ItemStack> restored = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i);
            helper.assertTrue(page.part() == i && page.last() == (i == pages.size() - 1)
                && !page.truncated(), "Invalid page envelope");
            FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                FillNetwork.Snapshot.encode(page, buffer);
                helper.assertTrue(buffer.readableBytes() < 524288, "Snapshot packet exceeded decoder limit");
                var decoded = FillNetwork.Snapshot.decode(buffer);
                helper.assertTrue(decoded.part() == page.part() && decoded.last() == page.last()
                    && decoded.sourceIds().equals(page.sourceIds()), "Page codec changed envelope or origins");
                restored.addAll(decoded.stacks());
            } finally { buffer.release(); }
        }
        helper.assertTrue(restored.size() == stock.size(), "Full snapshot lost records beyond the first page");
        for (int i = 0; i < stock.size(); i++) {
            helper.assertTrue(ItemStack.matches(stock.get(i), restored.get(i)), "Full snapshot changed item data");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void emptyAndOversizedSnapshotsFinishExplicitly(GameTestHelper helper) {
        var empty = FillNetwork.collectInitialSnapshot(1, 2, List.of());
        helper.assertTrue(empty.size() == 1 && empty.get(0).last() && empty.get(0).stacks().isEmpty()
            && !empty.get(0).truncated(), "Empty inventory must finish its one query");
        ItemStack large = new ItemStack(Items.DIAMOND);
        large.getOrCreateTag().putString("huge", "x".repeat(20000));
        var pages = FillNetwork.collectInitialSnapshot(1, 3,
            List.of(new MemorySource(List.of(large, new ItemStack(Items.CARROT, 3)))));
        helper.assertTrue(pages.get(pages.size() - 1).truncated(), "Oversized data was silently omitted");
        helper.assertTrue(pages.stream().flatMap(page -> page.stacks().stream()).anyMatch(stack -> stack.is(Items.CARROT)),
            "One oversized item hid safe snapshot entries");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void detailedFailureSurvivesClientPacket(GameTestHelper helper) {
        Component detail = Component.translatable("storage_fill.failed.missing",
            Component.translatable(Items.DIAMOND.getDescriptionId()).append(" × 2"));
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            FillNetwork.TopUpResult.encode(new FillNetwork.TopUpResult(7, 12, false, detail), buffer);
            var restored = FillNetwork.TopUpResult.decode(buffer);
            helper.assertTrue(!restored.success() && restored.menu() == 7 && restored.operation() == 12
                && restored.message().equals(detail), "Failure reason or translated item was lost in transit");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void preparationStopsReadingAtMemoryBudget(GameTestHelper helper) {
        int[] visited = {0};
        StorageSource source = new StorageSource() {
            public int sourceId() { return BEYOND_DIMENSIONS; }
            public boolean visitPreview(java.util.function.Predicate<ItemStack> visitor) {
                for (int i = 0; i < 100000; i++) {
                    ItemStack stack = new ItemStack(Items.DIAMOND, 64);
                    stack.getOrCreateTag().putInt("variant", i);
                    stack.getOrCreateTag().putString("payload", "x".repeat(4000));
                    visited[0]++;
                    if (!visitor.test(stack)) return false;
                }
                return true;
            }
            public List<ItemStack> snapshot(Set<Item> wanted) { throw new AssertionError("Preparation copied all inventory first"); }
            public ItemStack extract(ItemStack stack, int count, boolean simulate) { throw new AssertionError("Preparation extracted items"); }
            public ItemStack insert(ItemStack stack) { throw new AssertionError("Preparation inserted items"); }
            public void finish(int count) { }
        };
        var pages = FillNetwork.collectInitialSnapshot(7, 30, List.of(source));
        helper.assertTrue(visited[0] > 1 && visited[0] < 5000, "Preparation traversed the entire large inventory");
        helper.assertTrue(pages.get(pages.size() - 1).truncated(), "Budget exhaustion was not reported");
        long bytes = pages.stream().flatMap(page -> page.stacks().stream())
            .mapToLong(stack -> StackKey.of(stack).data().sizeInBytes() + 64L).sum();
        helper.assertTrue(bytes <= RequestLimits.MAX_SESSION_BYTES, "Initial snapshot exceeded the session budget");
        for (var page : pages) {
            helper.assertTrue(page.revision() == 0 && page.covered().isEmpty(), "Initial inventory used partial replacement semantics");
            var buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                FillNetwork.Snapshot.encode(page, buffer);
                FillNetwork.Snapshot.decode(buffer);
            } finally { buffer.release(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void preparationAndCloseRemainDistinctOnWire(GameTestHelper helper) {
        for (boolean prepare : List.of(false, true)) {
            var buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                FillNetwork.Query.encode(new FillNetwork.Query(7, 31, List.of(), prepare), buffer);
                var restored = FillNetwork.Query.decode(buffer);
                helper.assertTrue(restored.prepare() == prepare && restored.menu() == 7 && restored.request() == 31
                    && restored.candidates().isEmpty(), "Snapshot request became a close request");
            } finally { buffer.release(); }
        }
        var invalid = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            FillNetwork.Query.encode(new FillNetwork.Query(7, 32, List.of(new ItemStack(Items.DIAMOND)), false), invalid);
            boolean rejected = false;
            try { FillNetwork.Query.decode(invalid); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "One-shot protocol accepted an obsolete watched-material query");
        } finally { invalid.release(); }
        helper.succeed();
    }

    private static final class MemorySource implements StorageSource {
        private final List<ItemStack> stock;
        private int reads;
        private MemorySource(List<ItemStack> stock) { this.stock = stock; }
        public int sourceId() { return APPLIED_ENERGISTICS; }
        public List<ItemStack> snapshot(Set<Item> wanted) {
            reads++;
            return stock.stream().filter(stack -> wanted.contains(stack.getItem())).toList();
        }
        public ItemStack extract(ItemStack prototype, int amount, boolean simulate) { throw new AssertionError("Preview extracted items"); }
        public ItemStack insert(ItemStack stack) { throw new AssertionError("Preview inserted items"); }
        public void finish(int extracted) { }
    }
}

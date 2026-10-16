package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StackKey;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class RecipeQueryServiceGameTests {
    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void preferredQueryCopiesInputAndReturnsFullShiftStockWithoutFallback(GameTestHelper helper) {
        ItemStack hint = tagged(7, 1);
        MemorySource source = new MemorySource(1, new Object(), List.of(tagged(7, 192)));
        var session = RecipeQueryService.start(List.of(hint));
        hint.getOrCreateTag().putInt("variant", 99);
        var batch = RecipeQueryService.next(player(helper), session, List.of(source));
        helper.assertTrue(batch.stacks().size() == 1 && batch.stacks().get(0).getCount() == 192
            && batch.stacks().get(0).getTag().getInt("variant") == 7, "Preferred query did not copy hints or retain full shift quantity");
        helper.assertTrue(source.scans == 0 && source.performed == 0 && source.stocks.get(0).getCount() == 192,
            "Exact preferred query scanned unknown keys or consumed stock");
        RecipeQueryService.cancel(session);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void oneKeyReturnsEveryOriginTogetherAndDeduplicatesNativeIdentity(GameTestHelper helper) {
        Object identity = new Object();
        MemorySource first = new MemorySource(1, identity, List.of(new ItemStack(Items.IRON_INGOT, 1)));
        MemorySource duplicate = new MemorySource(1, identity, List.of(new ItemStack(Items.IRON_INGOT, 1)));
        MemorySource second = new MemorySource(2, new Object(), List.of(new ItemStack(Items.IRON_INGOT, 63)));
        var session = RecipeQueryService.start(List.of(new ItemStack(Items.IRON_INGOT)));
        var batch = RecipeQueryService.next(player(helper), session, List.of(first, duplicate, second));
        helper.assertTrue(batch.stacks().size() == 2 && batch.sourceIds().equals(List.of(1, 2))
            && batch.stacks().get(0).getCount() == 1 && batch.stacks().get(1).getCount() == 63,
            "One key did not return all source quantities together");
        helper.assertTrue(duplicate.simulated == 0, "Duplicate native source was simulated twice");
        RecipeQueryService.cancel(session);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void unknownNbtVariantsStaySeparateAndAreConfirmedByNativeSimulation(GameTestHelper helper) {
        List<ItemStack> stocks = new ArrayList<>();
        for (int i = 0; i < 20; i++) stocks.add(tagged(i, 1));
        MemorySource source = new MemorySource(1, new Object(), stocks);
        var session = RecipeQueryService.start(List.of(new ItemStack(Items.IRON_INGOT)));
        var batch = RecipeQueryService.next(player(helper), session, List.of(source));
        helper.assertTrue(!batch.stacks().isEmpty() && batch.stacks().size() <= 16
            && batch.stacks().stream().allMatch(stack -> stack.getCount() == 1 && stack.hasTag()),
            "Different NBT keys were merged into a stack or not returned");
        helper.assertTrue(new LinkedHashSet<>(batch.stacks().stream().map(StackKey::of).toList()).size() == batch.stacks().size()
            && source.simulated >= batch.stacks().size(), "Unknown candidates were duplicated or not simulated");
        RecipeQueryService.cancel(session);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void rawPreviewQuantityCannotBypassNativeExtractionRestrictions(GameTestHelper helper) {
        MemorySource source = new MemorySource(2, new Object(), List.of(tagged(8, 64)));
        source.allowed = false;
        var session = RecipeQueryService.start(List.of(new ItemStack(Items.IRON_INGOT)));
        var batch = RecipeQueryService.next(player(helper), session, List.of(source));
        helper.assertTrue(batch.stacks().isEmpty() && source.simulated > 0, "Raw visible stock bypassed native extraction restrictions");
        RecipeQueryService.cancel(session);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void laterBatchReadsChangedAmountsAndRevokedAccess(GameTestHelper helper) {
        List<ItemStack> stocks = new ArrayList<>();
        for (int i = 0; i < 17; i++) stocks.add(tagged(i, 64));
        MemorySource source = new MemorySource(1, new Object(), stocks);
        var session = RecipeQueryService.start(stocks);
        ServerPlayer player = player(helper);
        var first = RecipeQueryService.next(player, session, List.of(source));
        helper.assertTrue(!first.stacks().isEmpty() && first.stacks().size() <= 16, "First preferred batch ignored its key bound");
        source.allowed = false;
        var next = RecipeQueryService.next(player, session, List.of(source));
        helper.assertTrue(next.stacks().isEmpty(), "Later batch reused quantities after access was revoked");
        RecipeQueryService.cancel(session);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void laterBatchReadsFreshLowerAmountsInsteadOfHintCounts(GameTestHelper helper) {
        List<ItemStack> stocks = new ArrayList<>();
        for (int i = 0; i < 17; i++) stocks.add(tagged(i, 64));
        MemorySource source = new MemorySource(1, new Object(), stocks);
        var session = RecipeQueryService.start(stocks);
        ServerPlayer player = player(helper);
        var first = RecipeQueryService.next(player, session, List.of(source));
        helper.assertTrue(!first.stacks().isEmpty() && first.stacks().size() <= 16, "First batch did not leave a bounded later query");
        stocks.forEach(stack -> stack.setCount(2));
        var second = RecipeQueryService.next(player, session, List.of(source));
        helper.assertTrue(!second.stacks().isEmpty() && second.stacks().stream().allMatch(stack -> stack.getCount() == 2),
            "Later batch reused original hint or previous native quantities");
        RecipeQueryService.cancel(session);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void wideOriginKeyGroupsAreNeverSplitByTheByteBudget(GameTestHelper helper) {
        List<ItemStack> stocks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            ItemStack stack = tagged(i, 64);
            stack.getOrCreateTag().putString("padding", "x".repeat(6000));
            stocks.add(stack);
        }
        List<StorageSource> sources = new ArrayList<>();
        for (int i = 1; i <= 4; i++) sources.add(new MemorySource(i, new Object(), stocks));
        ServerPlayer player = player(helper);
        for (int round = 0; round < 32; round++) {
            var session = RecipeQueryService.start(stocks);
            var batch = RecipeQueryService.next(player, session, sources);
            java.util.Map<StackKey, Set<Integer>> origins = new java.util.HashMap<>();
            long decodedBytes = 0;
            for (int i = 0; i < batch.stacks().size(); i++) {
                StackKey key = StackKey.of(batch.stacks().get(i));
                decodedBytes += key.data().sizeInBytes() + 64;
                origins.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(batch.sourceIds().get(i));
            }
            helper.assertTrue(decodedBytes <= 262144, "Server query page exceeded the client decoded NBT budget");
            helper.assertTrue(!origins.isEmpty() && origins.values().stream().allMatch(ids -> ids.equals(Set.of(1, 2, 3, 4))),
                "Byte budget split one concrete key across response batches");
            RecipeQueryService.cancel(session);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void cancelEndsQueryWithoutFurtherNativeReads(GameTestHelper helper) {
        MemorySource source = new MemorySource(1, new Object(), List.of(tagged(1, 64)));
        var session = RecipeQueryService.start(List.of(new ItemStack(Items.IRON_INGOT)));
        RecipeQueryService.cancel(session);
        var batch = RecipeQueryService.next(player(helper), session, List.of(source));
        helper.assertTrue(batch.exhausted() && batch.stacks().isEmpty() && source.simulated == 0 && source.scans == 0,
            "Cancelled query still retained work or read native stock");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void disappearingSourcesEndAsUnknownInsteadOfDefiniteMissing(GameTestHelper helper) {
        List<ItemStack> stocks = new ArrayList<>();
        for (int i = 0; i < 17; i++) stocks.add(tagged(i, 64));
        MemorySource source = new MemorySource(1, new Object(), stocks);
        var session = RecipeQueryService.start(stocks);
        ServerPlayer player = player(helper);
        helper.assertTrue(!RecipeQueryService.next(player, session, List.of(source)).stacks().isEmpty(), "Source-change fixture had no initial readable stock");
        var batch = RecipeQueryService.next(player, session, List.of());
        helper.assertTrue(batch.exhausted() && batch.truncated(), "Disappearing source was reported as definite missing stock");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void tooManyHintsAndUnsupportedOriginIdsAreBounded(GameTestHelper helper) {
        List<ItemStack> hints = new ArrayList<>();
        for (int i = 0; i < 513; i++) hints.add(tagged(i, 64));
        MemorySource source = new MemorySource(1, new Object(), hints);
        var batch = RecipeQueryService.next(player(helper), RecipeQueryService.start(hints), List.of(source));
        helper.assertTrue(batch.exhausted() && batch.truncated() && source.simulated == 0, "Query accepted more than its total key bound");
        MemorySource unsupported = new MemorySource(5, new Object(), List.of(new ItemStack(Items.IRON_INGOT, 64)));
        var other = RecipeQueryService.next(player(helper), RecipeQueryService.start(List.of(new ItemStack(Items.IRON_INGOT))), List.of(unsupported));
        helper.assertTrue(other.stacks().isEmpty() && unsupported.simulated == 0, "Query emitted an unsupported protocol origin id");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void oversizedHintAndTotalKeysEndAsUnknownWithoutNativeReads(GameTestHelper helper) {
        ItemStack huge = new ItemStack(Items.IRON_INGOT);
        huge.getOrCreateTag().putString("huge", "x".repeat(20000));
        MemorySource source = new MemorySource(1, new Object(), List.of(huge));
        var session = RecipeQueryService.start(List.of(huge));
        var batch = RecipeQueryService.next(player(helper), session, List.of(source));
        helper.assertTrue(batch.exhausted() && batch.truncated() && batch.stacks().isEmpty() && source.simulated == 0,
            "Oversized query hint was not bounded as unknown");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_service")
    public static void scanningBudgetReturnsMoreInsteadOfMissingAndResumesAtLateCandidate(GameTestHelper helper) {
        List<ItemStack> stocks = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            ItemStack stack = new ItemStack(Items.QUARTZ);
            stack.getOrCreateTag().putInt("variant", i);
            stocks.add(stack);
        }
        stocks.add(tagged(22, 64));
        MemorySource source = new MemorySource(1, new Object(), stocks);
        var session = RecipeQueryService.start(List.of(new ItemStack(Items.IRON_INGOT)));
        ServerPlayer player = player(helper);
        boolean found = false, sawMore = false;
        for (int i = 0; i < 64 && !found; i++) {
            var batch = RecipeQueryService.next(player, session, List.of(source));
            if (batch.stacks().isEmpty() && !batch.exhausted()) sawMore = true;
            found = batch.stacks().stream().anyMatch(stack -> stack.is(Items.IRON_INGOT) && stack.getCount() == 64);
            if (batch.exhausted() && !found) break;
        }
        helper.assertTrue(found && sawMore, "Bounded scan declared missing or could not resume at a late wanted key");
        RecipeQueryService.cancel(session);
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "recipe-query"));
    }

    private static ItemStack tagged(int variant, int count) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count);
        stack.getOrCreateTag().putInt("variant", variant);
        return stack;
    }

    private static final class MemorySource implements StorageSource, NativeRecipeQuerySource {
        final int id;
        final Object identity;
        final List<ItemStack> stocks;
        int simulated, performed, scans;
        boolean allowed = true;
        MemorySource(int id, Object identity, List<ItemStack> stocks) { this.id = id; this.identity = identity; this.stocks = stocks; }
        public int sourceId() { return id; }
        public Object identity() { return identity; }
        public List<ItemStack> snapshot(Set<Item> wanted) { return stocks; }
        public Object queryKey(ItemStack stack) { return StackKey.of(stack); }
        public boolean queryUsesPosition() { return true; }
        public Scan scanQuery(Set<Item> wanted, Set<Object> seen, int position, int limit) {
            scans++;
            int scanned = 0;
            while (position < stocks.size() && scanned < limit) {
                ItemStack stack = stocks.get(position++); scanned++;
                if (wanted.contains(stack.getItem()) && !seen.contains(queryKey(stack))) return new Scan(stack.copyWithCount(1), position, scanned, false);
            }
            return new Scan(ItemStack.EMPTY, position, scanned, position >= stocks.size());
        }
        public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
            if (!simulate) { performed++; throw new AssertionError("Query performed real extraction"); }
            simulated++;
            if (!allowed) return ItemStack.EMPTY;
            for (ItemStack stack : stocks) if (ItemStack.isSameItemSameTags(stack, prototype)) return stack.copyWithCount(Math.min(amount, stack.getCount()));
            return ItemStack.EMPTY;
        }
        public ItemStack insert(ItemStack stack) { throw new AssertionError("Query attempted insertion"); }
        public void finish(int extracted) { }
    }
}

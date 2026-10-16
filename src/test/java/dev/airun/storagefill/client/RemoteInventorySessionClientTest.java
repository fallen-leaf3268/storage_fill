package dev.airun.storagefill.client;

import dev.airun.storagefill.FillNetwork;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.RequestLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Mod.EventBusSubscriber(modid = StorageFill.ID, value = Dist.CLIENT)
public final class RemoteInventorySessionClientTest {
    private static boolean done;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END || Minecraft.getInstance().getOverlay() != null
            || (!Boolean.getBoolean("storage_fill.smoke") && !Boolean.getBoolean("storage_fill.integration"))) return;
        completeOnlyAfterLastPart();
        rejectDisorderedAndStaleParts();
        emptyAndTruncatedAreComplete();
        rejectAggregateOverflow();
        updatesReplaceOnlyCoveredItems();
        historyEvictsWithinMemoryBudget();
        preparedInventoryAcceptsTargetedUpdates();
        sharedViewsIsolatePublicStackChanges();
        viewHistoryLimitsRetainedReferences();
        done = true;
        StorageFill.LOGGER.info("STORAGE_FILL_SESSION_CACHE_TESTS_PASSED");
    }

    private static void completeOnlyAfterLastPart() {
        var cache = new RemoteInventory.SnapshotCache(7, 10);
        ItemStack tagged = new ItemStack(Items.DIAMOND, 3);
        tagged.getOrCreateTag().putInt("variant", 2);
        check(cache.accept(part(10, 0, false, false, List.of(new ItemStack(Items.CARROT, 4)), List.of(1)))
            == RemoteInventory.Outcome.PENDING, "First part was not pending");
        check(cache.view(Set.of(Items.CARROT)).request() < 0, "Partial snapshot became visible");
        check(cache.accept(part(10, 1, true, false, List.of(new ItemStack(Items.CARROT, 2), tagged,
            new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.CARROT, 8)), List.of(1, 2, 2, 3)))
            == RemoteInventory.Outcome.COMPLETE, "Final part did not complete snapshot");
        var carrots = cache.view(Set.of(Items.CARROT));
        check(carrots == cache.view(Set.of(Items.CARROT)), "Unchanged recipe view was rebuilt instead of reused");
        check(carrots.request() == 10 && carrots.stacks().size() == 2
            && carrots.stacks().get(0).getCount() == 6 && carrots.stacks().get(1).getCount() == 8
            && carrots.sourceIds().equals(List.of(1, 3)), "Cache lost source attribution or duplicate counts");
        var diamonds = cache.view(Set.of(Items.DIAMOND));
        check(diamonds.stacks().size() == 2 && diamonds.stacks().stream().mapToInt(ItemStack::getCount).sum() == 8,
            "Item index merged NBT variants or retained unrelated items");
        carrots.stacks().get(0).setCount(99);
        for (int i = 0; i < 200; i++) {
            check(cache.view(Set.of(Items.CARROT)).stacks().get(0).getCount() == 6,
                "Repeated recipe reads changed cached inventory");
        }
        check(cache.accept(part(10, 1, true, false, List.of(new ItemStack(Items.CARROT, 90)), List.of(1)))
            == RemoteInventory.Outcome.IGNORED, "Late duplicate changed a completed snapshot");
        check(cache.view(Set.of(Items.CARROT)).stacks().get(0).getCount() == 6, "Duplicate part polluted complete cache");
    }

    private static void rejectDisorderedAndStaleParts() {
        var missing = new RemoteInventory.SnapshotCache(7, 11);
        check(missing.accept(part(11, 1, true, false, List.of(), List.of())) == RemoteInventory.Outcome.INVALID,
            "Missing initial part was accepted");
        check(missing.accept(part(11, 0, true, false, List.of(), List.of())) == RemoteInventory.Outcome.IGNORED,
            "Failed session accepted an automatic retry");
        var duplicate = new RemoteInventory.SnapshotCache(7, 12);
        duplicate.accept(part(12, 0, false, false, List.of(new ItemStack(Items.CARROT)), List.of(1)));
        check(duplicate.accept(part(12, 0, false, false, List.of(new ItemStack(Items.CARROT)), List.of(1)))
            == RemoteInventory.Outcome.INVALID, "Duplicate pending part was accepted");
        check(duplicate.view(Set.of(Items.CARROT)).request() < 0, "Failed partial cache remained visible");
        var fresh = new RemoteInventory.SnapshotCache(7, 13);
        check(fresh.accept(part(12, 0, true, false, List.of(new ItemStack(Items.CARROT)), List.of(1)))
            == RemoteInventory.Outcome.IGNORED, "Previous session polluted new request");
        check(fresh.accept(part(13, 0, true, false, List.of(new ItemStack(Items.DIAMOND)), List.of(2)))
            == RemoteInventory.Outcome.COMPLETE, "Ignored old packet broke fresh session");
        check(fresh.view(Set.of(Items.CARROT)).stacks().isEmpty(), "Previous session material leaked into cache");
        fresh.fail();
        check(fresh.view(Set.of(Items.DIAMOND)).request() < 0, "Explicit failure did not invalidate completed cache");
    }

    private static void emptyAndTruncatedAreComplete() {
        var empty = new RemoteInventory.SnapshotCache(7, 14);
        check(empty.accept(part(14, 0, true, false, List.of(), List.of())) == RemoteInventory.Outcome.COMPLETE
            && empty.view(Set.of(Items.CARROT)).request() == 14 && !empty.isPending(),
            "Empty completed snapshot would trigger another query");
        var truncated = new RemoteInventory.SnapshotCache(7, 15);
        check(truncated.accept(part(15, 0, true, true, List.of(new ItemStack(Items.CARROT)), List.of(1)))
            == RemoteInventory.Outcome.COMPLETE && truncated.truncated(), "Truncation flag was lost");
    }

    private static void rejectAggregateOverflow() {
        var many = new RemoteInventory.SnapshotCache(7, 16);
        var stacks = Collections.nCopies(512, new ItemStack(Items.CARROT));
        var ids = Collections.nCopies(512, 1);
        RemoteInventory.Outcome result = RemoteInventory.Outcome.PENDING;
        for (int part = 0; part <= RequestLimits.MAX_SESSION_STACKS / 512 + 1 && result == RemoteInventory.Outcome.PENDING; part++) {
            result = many.accept(part(16, part, false, false, stacks, ids));
        }
        check(result == RemoteInventory.Outcome.INVALID && many.view(Set.of(Items.CARROT)).request() < 0,
            "Aggregate session limits were not enforced");
        var bytes = new RemoteInventory.SnapshotCache(7, 17);
        ItemStack large = new ItemStack(Items.DIAMOND);
        large.getOrCreateTag().putString("payload", "x".repeat(1024));
        var payload = Collections.nCopies(32, large);
        var sources = Collections.nCopies(32, 1);
        result = RemoteInventory.Outcome.PENDING;
        for (int part = 0; part < 512 && result == RemoteInventory.Outcome.PENDING; part++) {
            result = bytes.accept(part(17, part, false, false, payload, sources));
        }
        check(result == RemoteInventory.Outcome.INVALID && bytes.view(Set.of(Items.DIAMOND)).request() < 0,
            "Aggregate NBT byte budget was not enforced");
    }

    private static void preparedInventoryAcceptsTargetedUpdates() {
        var cache = new RemoteInventory.SnapshotCache(7, 32);
        cache.accept(part(32, 0, true, false, List.of(new ItemStack(Items.CARROT, 8), new ItemStack(Items.DIAMOND, 4)), List.of(1, 2)));
        check(cache.view(Set.of(Items.CARROT, Items.DIAMOND, Items.NETHER_STAR)).request() == 32,
            "Prepared inventory could not answer an unseen recipe locally");
        var diamonds = cache.view(Set.of(Items.DIAMOND));
        cache.accept(new FillNetwork.Snapshot(7, 32, List.of(new ItemStack(Items.CARROT, 7)), List.of(1),
            0, true, false, 1, List.of(Items.CARROT)));
        check(cache.view(Set.of(Items.CARROT)).inventory().get(0).getCount() == 7 && cache.view(Set.of(Items.DIAMOND)) == diamonds,
            "First targeted update invalidated unrelated prepared items");
        cache.accept(new FillNetwork.Snapshot(7, 32, List.of(), List.of(), 0, true, false, 2, List.of(Items.CARROT), true));
        check(cache.view(Set.of(Items.DIAMOND)).request() < 0, "Source change retained a prepared inventory from the old network");
        var truncated = new RemoteInventory.SnapshotCache(7, 33);
        truncated.accept(part(33, 0, true, true, List.of(new ItemStack(Items.CARROT)), List.of(1)));
        check(truncated.view(Set.of(Items.CARROT)).request() == 33 && truncated.view(Set.of(Items.DIAMOND)).request() < 0,
            "Truncated preparation treated unreceived materials as known empty");
    }

    private static void updatesReplaceOnlyCoveredItems() {
        var cache = new RemoteInventory.SnapshotCache(7, 20);
        cache.accept(new FillNetwork.Snapshot(7, 20, List.of(new ItemStack(Items.CARROT, 8), new ItemStack(Items.DIAMOND, 4)),
            List.of(1, 2), 0, true, false, 1, List.of(Items.CARROT, Items.DIAMOND)));
        var diamonds = cache.view(Set.of(Items.DIAMOND));
        var carrots = cache.view(Set.of(Items.CARROT));
        check(cache.view(Set.of(Items.NETHER_STAR)).request() < 0, "Unqueried material was treated as known empty");
        cache.accept(new FillNetwork.Snapshot(7, 20, List.of(), List.of(), 0, true, false, 2, List.of(Items.CARROT)));
        check(cache.view(Set.of(Items.CARROT)).stacks().isEmpty(), "Depleted material survived replacement update");
        check(cache.view(Set.of(Items.DIAMOND)) == diamonds, "Unrelated update rebuilt the cached recipe view");
        check(cache.view(Set.of(Items.CARROT)) != carrots, "Changed inventory retained an obsolete preview view");
        check(cache.accept(new FillNetwork.Snapshot(7, 20, List.of(new ItemStack(Items.CARROT, 99)), List.of(1),
            0, true, false, 1, List.of(Items.CARROT))) == RemoteInventory.Outcome.IGNORED, "Old update overwrote newer inventory");
        cache.accept(new FillNetwork.Snapshot(7, 20, List.of(new ItemStack(Items.CARROT, 2)), List.of(1),
            0, false, false, 3, List.of(Items.CARROT)));
        check(cache.view(Set.of(Items.CARROT)).stacks().isEmpty(), "Incomplete replacement changed visible inventory");
        cache.accept(new FillNetwork.Snapshot(7, 20, List.of(new ItemStack(Items.CARROT, 3)), List.of(1),
            1, true, false, 3, List.of()));
        check(cache.view(Set.of(Items.CARROT)).stacks().get(0).getCount() == 5, "Replacement pages were not atomically combined");
        var stable = cache.view(Set.of(Items.CARROT));
        cache.accept(new FillNetwork.Snapshot(7, 20, List.of(new ItemStack(Items.CARROT, 5)), List.of(1),
            0, true, false, 4, List.of(Items.CARROT)));
        check(cache.view(Set.of(Items.CARROT)) == stable, "Unchanged resubscription invalidated the recipe simulation");
        cache.accept(new FillNetwork.Snapshot(7, 20, List.of(new ItemStack(Items.CARROT, 5)), List.of(1),
            0, true, false, 5, List.of(Items.CARROT), true));
        check(cache.view(Set.of(Items.DIAMOND)).request() < 0, "Rebound storage retained an old inactive material");
    }

    private static void historyEvictsWithinMemoryBudget() {
        var cache = new RemoteInventory.SnapshotCache(7, 21);
        var items = List.of(Items.DIAMOND, Items.CARROT, Items.GOLD_INGOT);
        for (int revision = 1; revision <= items.size(); revision++) {
            for (int part = 0; part < 30; part++) {
                var stacks = new java.util.ArrayList<ItemStack>();
                for (int i = 0; i < 30; i++) {
                    ItemStack stack = new ItemStack(items.get(revision - 1));
                    stack.getOrCreateTag().putString("payload", "x".repeat(3500));
                    stack.getOrCreateTag().putInt("variant", part * 30 + i);
                    stacks.add(stack);
                }
                check(stacks.stream().mapToLong(stack -> dev.airun.storagefill.StackKey.of(stack).data().sizeInBytes() + 64L).sum()
                    <= RequestLimits.MAX_SNAPSHOT_BYTES, "History fixture exceeded one packet budget");
                var outcome = cache.accept(new FillNetwork.Snapshot(7, 21, stacks, Collections.nCopies(30, 1),
                    part, part == 29, false, revision, part == 0 ? List.of(items.get(revision - 1)) : List.of()));
                check(outcome == (part == 29 ? RemoteInventory.Outcome.COMPLETE : RemoteInventory.Outcome.PENDING),
                    "Bounded history cache failed instead of evicting old materials");
            }
        }
        check(cache.view(Set.of(Items.DIAMOND)).request() < 0, "Old large material remained beyond the cache memory budget");
        check(cache.view(Set.of(Items.GOLD_INGOT)).stacks().size() == 900, "New material was lost when evicting history");
    }

    private static void sharedViewsIsolatePublicStackChanges() {
        var cache = new RemoteInventory.SnapshotCache(7, 40);
        ItemStack stack = new ItemStack(Items.DIAMOND, 8);
        stack.getOrCreateTag().putByteArray("payload", new byte[2048]);
        cache.accept(part(40, 0, true, false, List.of(stack), List.of(1)));
        var first = cache.view(Set.of(Items.DIAMOND, Items.CARROT));
        var second = cache.view(Set.of(Items.DIAMOND, Items.GOLD_INGOT));
        check(first.entries().get(0) == second.entries().get(0), "Overlapping views duplicated their storage records");
        first.inventory().get(0).setCount(99);
        first.inventory().get(0).getTag().getByteArray("payload")[0] = 7;
        check(first.inventory().get(0).getCount() == 8 && second.inventory().get(0).getTag().getByteArray("payload")[0] == 0,
                "Public inventory reads exposed a mutable shared storage record");
        cache.accept(new FillNetwork.Snapshot(7, 40, List.of(stack), List.of(1), 0, true, false, 1, List.of(Items.DIAMOND)));
        check(cache.view(Set.of(Items.DIAMOND, Items.CARROT)) == first,
                "An unchanged replacement rebuilt shared storage records or their views");
    }

    private static void viewHistoryLimitsRetainedReferences() {
        var cache = new RemoteInventory.SnapshotCache(7, 41);
        for (int part = 0; part < 64; part++) {
            var stacks = new java.util.ArrayList<ItemStack>();
            for (int i = 0; i < 256; i++) {
                ItemStack stack = new ItemStack(Items.DIAMOND);
                stack.getOrCreateTag().putInt("variant", part * 256 + i);
                stacks.add(stack);
            }
            long actualBytes = stacks.stream().mapToLong(stack -> dev.airun.storagefill.StackKey.of(stack).data().sizeInBytes() + 64L).sum();
            check(actualBytes <= RequestLimits.MAX_SNAPSHOT_BYTES,
                    "Large view fixture page exceeds byte budget: part=" + part + ", actualBytes=" + actualBytes);
            var outcome = cache.accept(new FillNetwork.Snapshot(7, 41, stacks, Collections.nCopies(256, 1), part, part == 63, false));
            check(outcome == (part == 63 ? RemoteInventory.Outcome.COMPLETE : RemoteInventory.Outcome.PENDING),
                    "Large view fixture did not complete: part=" + part + ", outcome=" + outcome + ", actualBytes=" + actualBytes);
        }
        check(cache.view(Set.of(Items.DIAMOND)).entries().size() == 16384,
                "Large view fixture did not retain its full record count");
        var others = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValues().stream()
                .filter(item -> item != Items.DIAMOND && item != Items.AIR).limit(20).toList();
        var oldest = cache.view(Set.of(Items.DIAMOND, others.get(0)));
        for (int i = 1; i < others.size(); i++) cache.view(Set.of(Items.DIAMOND, others.get(i)));
        check(cache.view(Set.of(Items.DIAMOND, others.get(0))) != oldest,
                "Large recipe views exceeded the reference budget without evicting old views");
    }

    private static FillNetwork.Snapshot part(int request, int part, boolean last, boolean truncated,
            List<ItemStack> stacks, List<Integer> ids) {
        return new FillNetwork.Snapshot(7, request, stacks, ids, part, last, truncated);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

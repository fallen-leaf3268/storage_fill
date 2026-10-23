package dev.airun.storagefill.storage;

import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.FillPriority;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RecipeQueryService {
    private static final int MAX_KEYS = 512;
    private static final int MAX_KEY_BYTES = 16384;
    private static final int MAX_BYTES = 262144;
    private static final int MAX_BATCH_KEYS = 16;
    private static final int MAX_ROWS = 256;
    private static final int MAX_SOURCES = 256;
    private static final int MAX_SCANNED = 2048;
    private static final int MAX_TOTAL_SCANNED = 65536;
    private static final int MAX_BATCHES = 64;
    private static final long SOFT_NANOS = 2000000;

    private RecipeQueryService() { }

    public static Session start(List<ItemStack> preferred, HolderLookup.Provider registries) {
        return start(preferred, FillPriority.DEFAULT, registries);
    }

    public static Session start(List<ItemStack> preferred, FillPriority priority, HolderLookup.Provider registries) {
        Session session = new Session(priority, registries);
        if (preferred == null || preferred.isEmpty()) { cancel(session); return session; }
        if (preferred.size() > MAX_KEYS) { session.truncated = true; cancel(session); return session; }
        Set<StackKey> unique = new LinkedHashSet<>();
        int bytes = 0;
        try {
            for (ItemStack stack : preferred) {
                if (stack == null || stack.isEmpty()) continue;
                StackKey key = StackKey.of(stack);
                if (unique.contains(key)) continue;
                int size = keyBytes(key, session);
                if (size > MAX_KEY_BYTES || size < 0 || bytes + size + 64 > MAX_BYTES) {
                    session.truncated = true; cancel(session); return session;
                }
                if (unique.add(key)) {
                    bytes += size + 64;
                    session.preferred.addLast(key);
                    session.wanted.add(key.item());
                }
            }
        } catch (RuntimeException | LinkageError error) {
            session.truncated = true; cancel(session); return session;
        }
        if (session.preferred.isEmpty()) cancel(session);
        return session;
    }

    public static Batch next(ServerPlayer player, Session session) {
        if (session == null || session.closed) return empty(session);
        if (player == null) return next(null, session, List.of());
        return next(player, session, StorageSources.find(player));
    }

    static Batch next(ServerPlayer player, Session session, List<StorageSource> discovered) {
        if (session == null || session.closed) return empty(session);
        long deadline = System.nanoTime() + SOFT_NANOS;
        List<ItemStack> stacks = new ArrayList<>();
        List<Integer> origins = new ArrayList<>();
        if (player == null || player.isRemoved() || !player.containerMenu.stillValid(player)
            || session.owner != null && (!session.owner.equals(player.getUUID()) || session.menu != player.containerMenu)) {
            session.truncated = true;
            return end(session, stacks, origins);
        }
        session.owner = player.getUUID();
        session.menu = player.containerMenu;
        if (++session.batches > MAX_BATCHES) { session.truncated = true; return end(session, stacks, origins); }
        List<StorageSource> sources = sources(discovered, session);
        if (sources.size() > MAX_SOURCES) { session.truncated = true; return end(session, stacks, origins); }
        Set<SourceIdentity> identities = new LinkedHashSet<>();
        for (StorageSource source : sources) identities.add(identity(source));
        if (session.sourceIdentities != null && !session.sourceIdentities.equals(identities)) {
            session.uncertain = true;
            session.positions.clear();
        }
        session.sourceIdentities = identities;
        if (sources.isEmpty()) return end(session, stacks, origins);
        for (StorageSource source : sources) {
            Cursor cursor = session.positions.computeIfAbsent(identity(source), ignored -> new Cursor());
            if (source instanceof NativeRecipeQuerySource nativeSource) {
                try {
                    long revision = nativeSource.queryRevision();
                    if (cursor.revisionSeen && revision != Long.MIN_VALUE && cursor.revision != revision) {
                        cursor.position = 0; cursor.exhausted = false; session.uncertain = true;
                    }
                    if (session.batches > 1 && revision == Long.MIN_VALUE) session.uncertain = true;
                    cursor.revision = revision;
                    cursor.revisionSeen = true;
                } catch (RuntimeException | LinkageError error) {
                    cursor.exhausted = true; session.truncated = true;
                }
            } else {
                cursor.exhausted = true; session.truncated = true;
            }
        }
        int processed = 0, scanned = 0, bytes = 32;
        boolean attempted = false;
        boolean preferredPhase = !session.preferred.isEmpty();
        while (processed < MAX_BATCH_KEYS && scanned < MAX_SCANNED) {
            if (attempted && System.nanoTime() >= deadline) break;
            if (session.seen.size() >= MAX_KEYS || session.totalScanned >= MAX_TOTAL_SCANNED) {
                session.truncated = true; return end(session, stacks, origins);
            }
            attempted = true;
            StackKey key = session.pending != null ? session.pending : session.preferred.peekFirst();
            if (key == null) {
                if (preferredPhase && !stacks.isEmpty()) break;
                boolean allExhausted = true;
                for (StorageSource source : sources) {
                    Cursor cursor = session.positions.get(identity(source));
                    if (cursor.exhausted) continue;
                    allExhausted = false;
                    NativeRecipeQuerySource nativeSource = (NativeRecipeQuerySource) source;
                    Set<Object> seen = session.nativeSeen.computeIfAbsent(source.sourceId(), ignored -> new HashSet<>());
                    int limit = Math.min(MAX_SCANNED - scanned, MAX_TOTAL_SCANNED - session.totalScanned);
                    if (limit <= 0) break;
                    NativeRecipeQuerySource.Scan scan;
                    try {
                        scan = nativeSource.scanQuery(session.wanted, seen, nativeSource.queryUsesPosition() ? cursor.position : 0, limit);
                    } catch (RuntimeException | LinkageError error) {
                        cursor.exhausted = true; session.truncated = true; continue;
                    }
                    int used = Math.max(0, Math.min(limit, scan.scanned()));
                    scanned += used; session.totalScanned += used;
                    cursor.position = Math.max(0, scan.position());
                    cursor.exhausted = scan.exhausted();
                    if (!scan.candidate().isEmpty()) {
                        try { key = StackKey.of(scan.candidate()); }
                        catch (RuntimeException | LinkageError error) { session.truncated = true; return end(session, stacks, origins); }
                        session.pending = key;
                        break;
                    }
                    if (scanned >= MAX_SCANNED || System.nanoTime() >= deadline) break;
                }
                if (key == null) {
                    if (allExhausted || session.positions.values().stream().allMatch(cursor -> cursor.exhausted))
                        return end(session, stacks, origins);
                    break;
                }
            }
            if (session.seen.contains(key)) {
                consume(session, key);
                processed++;
                continue;
            }
            if (!session.wanted.contains(key.item())) { session.truncated = true; return end(session, stacks, origins); }
            int size;
            try { size = keyBytes(key, session); }
            catch (RuntimeException | LinkageError error) { session.truncated = true; return end(session, stacks, origins); }
            if (size < 0 || size > MAX_KEY_BYTES) { session.truncated = true; return end(session, stacks, origins); }
            Map<Integer, Integer> amounts = new LinkedHashMap<>();
            for (StorageSource source : sources) {
                try {
                    ItemStack available = source.extract(key.stack(1), StorageSources.MAX_COUNT, true);
                    if (available.isEmpty()) continue;
                    if (!key.equals(StackKey.of(available))) { session.truncated = true; continue; }
                    int amount = Math.max(0, Math.min(StorageSources.MAX_COUNT, available.getCount()));
                    if (amount > 0) amounts.merge(source.sourceId(), amount,
                        (first, second) -> Math.min(StorageSources.MAX_COUNT, first + second));
                } catch (RuntimeException | LinkageError error) {
                    session.truncated = true;
                }
            }
            int groupBytes = (size + 64) * amounts.size();
            int retainedBytes = Math.max(size + 64, groupBytes);
            if (session.bytes + retainedBytes > MAX_BYTES) { session.truncated = true; return end(session, stacks, origins); }
            if (origins.size() + amounts.size() > MAX_ROWS || bytes + groupBytes > MAX_BYTES - 1024) {
                if (stacks.isEmpty()) { session.truncated = true; return end(session, stacks, origins); }
                if (session.pending == null && session.preferred.peekFirst() != key) session.pending = key;
                break;
            }
            session.seen.add(key);
            session.bytes += retainedBytes;
            Set<Integer> remembered = new HashSet<>();
            for (StorageSource source : sources) {
                if (!(source instanceof NativeRecipeQuerySource nativeSource) || !remembered.add(source.sourceId())) continue;
                try {
                    session.nativeSeen.computeIfAbsent(source.sourceId(), ignored -> new HashSet<>()).add(nativeSource.queryKey(key.stack(1)));
                } catch (RuntimeException | LinkageError error) {
                    session.truncated = true; return end(session, stacks, origins);
                }
            }
            consume(session, key);
            for (var amount : amounts.entrySet()) {
                stacks.add(key.stack(amount.getValue())); origins.add(amount.getKey());
            }
            bytes += groupBytes;
            processed++;
            if (preferredPhase && session.preferred.isEmpty() && session.pending == null && !stacks.isEmpty()) break;
        }
        return new Batch(List.copyOf(stacks), List.copyOf(origins), false, session.truncated);
    }

    private static List<StorageSource> sources(List<StorageSource> discovered, Session session) {
        Map<SourceIdentity, StorageSource> unique = new LinkedHashMap<>();
        for (StorageSource source : discovered) {
            try {
                if (source != null && source.sourceId() >= StorageSource.BEYOND_DIMENSIONS
                    && source.sourceId() <= StorageSource.SOPHISTICATED_BACKPACKS) unique.putIfAbsent(identity(source), source);
            } catch (RuntimeException | LinkageError error) {
                session.truncated = true;
            }
        }
        return session.priority.ordered(new ArrayList<>(unique.values()));
    }

    private static SourceIdentity identity(StorageSource source) {
        Object identity = source.identity();
        return new SourceIdentity(source.sourceId(), identity == null ? source : identity);
    }

    private static int keyBytes(StackKey key, Session session) {
        return key.encodedSize(session.registries);
    }

    private static void consume(Session session, StackKey key) {
        if (session.pending == key) session.pending = null;
        else if (session.preferred.peekFirst() == key) session.preferred.removeFirst();
    }

    private static Batch empty(Session session) {
        return new Batch(List.of(), List.of(), true, session != null && session.truncated);
    }

    private static Batch end(Session session, List<ItemStack> stacks, List<Integer> origins) {
        boolean truncated = session.truncated || session.uncertain;
        session.truncated = truncated;
        cancel(session);
        return new Batch(List.copyOf(stacks), List.copyOf(origins), true, truncated);
    }

    public static void cancel(Session session) {
        if (session == null) return;
        session.closed = true;
        session.preferred.clear(); session.wanted.clear(); session.seen.clear(); session.nativeSeen.clear(); session.positions.clear();
        session.pending = null; session.sourceIdentities = null; session.owner = null; session.menu = null;
    }

    public static final class Session {
        private final FillPriority priority;
        private final HolderLookup.Provider registries;
        private Session(FillPriority priority, HolderLookup.Provider registries) {
            this.priority = priority;
            this.registries = registries;
        }
        private final Deque<StackKey> preferred = new ArrayDeque<>();
        private final Set<Item> wanted = new LinkedHashSet<>();
        private final Set<StackKey> seen = new HashSet<>();
        private final Map<Integer, Set<Object>> nativeSeen = new HashMap<>();
        private final Map<SourceIdentity, Cursor> positions = new LinkedHashMap<>();
        private Set<SourceIdentity> sourceIdentities;
        private StackKey pending;
        private UUID owner;
        private AbstractContainerMenu menu;
        private int bytes, totalScanned, batches;
        private boolean closed, truncated, uncertain;
    }

    private static final class Cursor {
        private int position;
        private long revision;
        private boolean exhausted, revisionSeen;
    }

    private record SourceIdentity(int sourceId, Object identity) { }
    public record Batch(List<ItemStack> stacks, List<Integer> sourceIds, boolean exhausted, boolean truncated) { }
}

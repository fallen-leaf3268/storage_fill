package dev.airun.storagefill.storage;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StorageChanges {
    private static final Map<Object, Pending> PENDING = new IdentityHashMap<>();
    private static boolean enabled;
    private static boolean overflow;

    private StorageChanges() { }

    public static void enable() { enabled = true; }

    public static void record(int sourceId, Object identity, Item item) {
        if (!enabled || identity == null) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return;
        Pending pending = PENDING.get(identity);
        if (pending == null) {
            if (PENDING.size() >= 256) { overflow = true; return; }
            pending = new Pending(sourceId, identity);
            PENDING.put(identity, pending);
        }
        if (item == null || (!pending.allItems && pending.items.add(item) && pending.items.size() > 128)) {
            pending.allItems = true;
            pending.items.clear();
        }
    }

    public static boolean hasChanges() { return overflow || !PENDING.isEmpty(); }

    public static Batch drain() {
        List<Change> changes = new ArrayList<>(PENDING.size());
        for (Pending pending : PENDING.values()) {
            changes.add(new Change(pending.sourceId, pending.identity, Set.copyOf(pending.items), pending.allItems));
        }
        Batch batch = new Batch(List.copyOf(changes), overflow);
        clear();
        return batch;
    }

    public static void clear() {
        PENDING.clear();
        overflow = false;
    }

    public record Change(int sourceId, Object identity, Set<Item> items, boolean allItems) { }
    public record Batch(List<Change> changes, boolean allSources) { }

    private static final class Pending {
        private final int sourceId;
        private final Object identity;
        private final Set<Item> items = new HashSet<>();
        private boolean allItems;

        private Pending(int sourceId, Object identity) {
            this.sourceId = sourceId;
            this.identity = identity;
        }
    }
}

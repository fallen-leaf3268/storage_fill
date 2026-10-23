package dev.airun.storagefill.storage;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import dev.airun.storagefill.mixin.BeyondDimensionsItemKeyAccessor;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class BeyondDimensionsSource implements StorageSource, NativeRecipeQuerySource {
    private static final Map<DimensionsNet, Index> INDEXES = new IdentityHashMap<>();
    public int sourceId() { return BEYOND_DIMENSIONS; }
    private final ServerPlayer player;
    private final DimensionsNet network;

    private BeyondDimensionsSource(ServerPlayer player, DimensionsNet network) {
        this.player = player;
        this.network = network;
    }

    static void find(ServerPlayer player, List<StorageSource> sources) {
        DimensionsNet network = DimensionsNet.getNetFromPlayer(player);
        if (network != null && !network.deleted && network.getPlayers().contains(player.getUUID())) {
            sources.add(new BeyondDimensionsSource(player, network));
        }
    }

    private boolean available() {
        return !network.deleted && network.getPlayers().contains(player.getUUID())
            && DimensionsNet.getNetFromPlayer(player) == network;
    }

    @Override
    public Object identity() { return network; }

    @Override
    public Object queryKey(ItemStack prototype) { return new ItemStackKey(prototype); }

    @Override
    public boolean queryUsesPosition() { return true; }

    @Override
    public long queryRevision() {
        return network.getUnifiedStorage() instanceof StorageRevision changes ? changes.storageFillRevision() : Long.MIN_VALUE;
    }

    @Override
    public Scan scanQuery(Set<Item> wanted, Set<Object> seen, int position, int scanLimit) {
        if (!available() || wanted.isEmpty()) return new Scan(ItemStack.EMPTY, position, 0, true);
        var storage = network.getUnifiedStorage();
        var bucket = storage.getBucket(ItemStackKey.ID);
        if (bucket.isEmpty()) return new Scan(ItemStack.EMPTY, position, 0, true);
        var keys = bucket.get();
        int scanned = 0;
        while (position < keys.size() && scanned < scanLimit) {
            var entry = keys.get(position++);
            scanned++;
            if (entry instanceof ItemStackKey key && wanted.contains(key.getSource()) && !seen.contains(key)
                && storage.getStackByKey(key).amount() > 0)
                return new Scan(key.copyStackWithCount(1), position, scanned, false);
        }
        return new Scan(ItemStack.EMPTY, position, scanned, position >= keys.size());
    }

    @Override
    public CountQuery prepareCount(ItemStack prototype, java.util.function.Predicate<ItemStack> matches) {
        if (prototype.isEmpty()) return limit -> 0;
        return new PreparedCount(new ItemStackKey(prototype), matches, null);
    }

    @Override
    public CountQuery prepareStrictCount(ItemStack prototype, java.util.function.Predicate<ItemStack> matches) {
        if (prototype.isEmpty() || prototype.getComponentsPatch().isEmpty()) return prepareCount(prototype, matches);
        return new PreparedCount(new ItemStackKey(prototype), matches, prototype.getComponentsPatch());
    }

    @Override
    public long countMatching(Item item, java.util.function.Predicate<ItemStack> matches, long limit) {
        if (item == null || limit <= 0 || !available()) return 0;
        long count = 0;
        Index index = INDEXES.get(network);
        if (index != null && !index.dirty) {
            Map<ItemStackKey, Long> variants = index.items.get(item);
            if (variants == null) return 0;
            for (var entry : variants.entrySet()) {
                if (entry.getValue() > 0 && matches.test(entry.getKey().copyStackWithCount(1))) {
                    count += Math.min(limit - count, entry.getValue());
                    if (count >= limit) break;
                }
            }
            return count;
        }
        var storage = network.getUnifiedStorage();
        ItemStackKey plainKey = new ItemStackKey(new ItemStack(item));
        long plainAmount = storage.getStackByKey(plainKey).amount();
        if (plainAmount > 0 && matches.test(plainKey.copyStackWithCount(1))) {
            count = Math.min(limit, plainAmount);
            if (count >= limit) return count;
        }
        for (KeyAmount entry : storage.getStorage()) {
            if (entry.key() instanceof ItemStackKey key && !plainKey.equals(key) && key.getSource() == item && entry.amount() > 0
                && matches.test(key.copyStackWithCount(1))) {
                count += Math.min(limit - count, entry.amount());
                if (count >= limit) break;
            }
        }
        return count;
    }

    @Override
    public AutoCloseable watch(Consumer<Item> changed) {
        Index index = INDEXES.computeIfAbsent(network, Index::new);
        Consumer<Item> listener = changed::accept;
        index.listeners.add(listener);
        return () -> {
            if (!index.listeners.remove(listener) || !index.listeners.isEmpty()) return;
            INDEXES.remove(network, index);
            try {
                index.any.close();
            } finally {
                index.delta.close();
                index.items.clear();
            }
        };
    }

    @Override
    public List<ItemStack> snapshot(Set<Item> wanted) {
        List<ItemStack> result = new ArrayList<>();
        if (available()) {
            Index index = INDEXES.get(network);
            if (index != null) {
                index.ensureReady();
                for (Item item : wanted) {
                    Map<ItemStackKey, Long> variants = index.items.get(item);
                    if (variants != null) variants.forEach((key, amount) ->
                        result.add(key.copyStackWithCount(Math.min(StorageSources.MAX_COUNT, amount))));
                }
                return result;
            }
            for (KeyAmount entry : network.getUnifiedStorage().getStorage()) {
                if (entry.key() instanceof ItemStackKey key && wanted.contains(key.getSource()) && entry.amount() > 0) {
                    result.add(key.copyStackWithCount(Math.min(StorageSources.MAX_COUNT, entry.amount())));
                }
            }
        }
        return result;
    }

    @Override
    public boolean visitPreview(java.util.function.Predicate<ItemStack> visitor) {
        if (!available()) return true;
        Index index = INDEXES.get(network);
        if (index != null && !index.dirty) {
            for (Map<ItemStackKey, Long> variants : index.items.values()) {
                for (var entry : variants.entrySet()) {
                    if (!visitor.test(entry.getKey().copyStackWithCount(Math.min(StorageSources.MAX_COUNT, entry.getValue())))) {
                        return false;
                    }
                }
            }
            return true;
        }
        for (KeyAmount entry : network.getUnifiedStorage().getStorage()) {
            if (entry.key() instanceof ItemStackKey key && entry.amount() > 0
                && !visitor.test(key.copyStackWithCount(Math.min(StorageSources.MAX_COUNT, entry.amount())))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean visitPreview(Set<Item> wanted, java.util.function.Predicate<ItemStack> visitor) {
        if (wanted.isEmpty() || !available()) return true;
        Index index = INDEXES.get(network);
        if (index != null && !index.dirty) {
            for (Item item : wanted) {
                Map<ItemStackKey, Long> variants = index.items.get(item);
                if (variants == null) continue;
                for (var entry : variants.entrySet()) {
                    if (entry.getValue() > 0 && !visitor.test(entry.getKey()
                        .copyStackWithCount(Math.min(StorageSources.MAX_COUNT, entry.getValue())))) return false;
                }
            }
            return true;
        }
        var storage = network.getUnifiedStorage();
        var bucket = storage.getBucket(ItemStackKey.ID);
        if (bucket.isEmpty()) return true;
        var keys = bucket.get();
        for (int position = 0; position < keys.size(); position++) {
            var entry = keys.get(position);
            if (!(entry instanceof ItemStackKey key) || !wanted.contains(key.getSource())) continue;
            long amount = storage.getStackByKey(key).amount();
            if (amount > 0 && !visitor.test(key.copyStackWithCount(Math.min(StorageSources.MAX_COUNT, amount)))) return false;
        }
        return true;
    }

    @Override
    public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
        if (prototype.isEmpty() || amount <= 0 || !available()) {
            return ItemStack.EMPTY;
        }
        return (ItemStack) network.getUnifiedStorage().extract(new ItemStackKey(prototype),
            Math.min(amount, StorageSources.MAX_COUNT), simulate, false).toStack();
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        if (stack.isEmpty() || !available()) {
            return stack;
        }
        return (ItemStack) network.getUnifiedStorage().insert(new ItemStackKey(stack), stack.getCount(), false).toStack();
    }

    @Override
    public void finish(int extracted) {
    }

    private final class PreparedCount implements CountQuery {
        private final ItemStackKey preferred;
        private final Item item;
        private final java.util.function.Predicate<ItemStack> matches;
        private final DataComponentPatch strictPatch;
        private List<ItemStackKey> candidates;
        private int scanCursor;
        private boolean scanComplete;
        private long revision;
        private boolean revisionSeen;
        private boolean preferredChecked;
        private boolean preferredMatches;

        private PreparedCount(ItemStackKey preferred, java.util.function.Predicate<ItemStack> matches, DataComponentPatch strictPatch) {
            this.preferred = preferred;
            this.item = preferred.getSource();
            this.matches = matches;
            this.strictPatch = strictPatch;
        }

        @Override
        public long count(long limit) {
            if (limit <= 0 || !available()) return 0;
            var storage = network.getUnifiedStorage();
            if (storage instanceof StorageRevision changes) {
                long current = changes.storageFillRevision();
                if (!revisionSeen || revision != current) {
                    candidates = null;
                    scanCursor = 0;
                    scanComplete = false;
                    preferredChecked = false;
                    preferredMatches = false;
                    revision = current;
                    revisionSeen = true;
                }
            } else {
                candidates = null;
                scanCursor = 0;
                scanComplete = false;
                preferredChecked = false;
                preferredMatches = false;
                revisionSeen = false;
            }
            long count = 0;
            long preferredAmount = storage.getStackByKey(preferred).amount();
            if (preferredAmount > 0) {
                if (!preferredChecked) {
                    Object present = storage.getOutStackByKey(preferred);
                    if (present instanceof ItemStack stack && !stack.isEmpty() && stack.is(item)) {
                        preferredMatches = matches.test(stack.copyWithCount(1));
                        preferredChecked = true;
                    }
                }
                if (preferredMatches) {
                    count = Math.min(limit, preferredAmount);
                    if (count >= limit) return count;
                }
            } else {
                preferredChecked = true;
                preferredMatches = false;
            }
            if (candidates == null) candidates = new ArrayList<>();
            for (ItemStackKey key : candidates) {
                long amount = storage.getStackByKey(key).amount();
                if (amount <= 0) continue;
                count += Math.min(limit - count, amount);
                if (count >= limit) return count;
            }
            if (scanComplete) return count;
            var bucket = storage.getBucket(ItemStackKey.ID);
            if (bucket.isPresent()) {
                var keys = bucket.get();
                while (scanCursor < keys.size()) {
                    if (!(keys.get(scanCursor) instanceof ItemStackKey key) || key.getSource() != item
                        || preferredChecked && preferred.equals(key)) {
                        scanCursor++;
                        continue;
                    }
                    if (strictPatch != null && (Object) key instanceof BeyondDimensionsItemKeyAccessor access) {
                        if (!strictPatch.equals(access.storage_fill$patch())) {
                            scanCursor++;
                            continue;
                        }
                    }
                    if (!matches.test(key.copyStackWithCount(1))) {
                        scanCursor++;
                        continue;
                    }
                    candidates.add(key);
                    scanCursor++;
                    long amount = storage.getStackByKey(key).amount();
                    if (amount <= 0) continue;
                    count += Math.min(limit - count, amount);
                    if (count >= limit) return count;
                }
            }
            scanComplete = true;
            return count;
        }
    }

    private static final class Index {
        private final DimensionsNet network;
        private final Map<Item, Map<ItemStackKey, Long>> items = new IdentityHashMap<>();
        private final List<Consumer<Item>> listeners = new ArrayList<>();
        private final AutoCloseable any;
        private final AutoCloseable delta;
        private boolean dirty = true;

        private Index(DimensionsNet network) {
            this.network = network;
            var storage = network.getUnifiedStorage();
            any = storage.subscribeAny(this, () -> {
                dirty = true;
                notifyChanged(null);
            });
            delta = storage.subscribeDelta(this, (type, amount, inserted) -> {
                if (!(type instanceof ItemStackKey key)) return;
                if (!dirty) update(key, storage.getStackByKey(key).amount());
                notifyChanged(key.getSource());
            });
        }

        private void ensureReady() {
            if (!dirty) return;
            items.clear();
            for (KeyAmount entry : network.getUnifiedStorage().getStorage()) {
                if (entry.key() instanceof ItemStackKey key) update(key, entry.amount());
            }
            dirty = false;
        }

        private void update(ItemStackKey key, long amount) {
            if (amount > 0) {
                items.computeIfAbsent(key.getSource(), ignored -> new LinkedHashMap<>()).put(key, amount);
            } else {
                Map<ItemStackKey, Long> variants = items.get(key.getSource());
                if (variants != null) {
                    variants.remove(key);
                    if (variants.isEmpty()) items.remove(key.getSource());
                }
            }
        }

        private void notifyChanged(Item item) {
            for (Consumer<Item> listener : List.copyOf(listeners)) {
                try { listener.accept(item); }
                catch (RuntimeException ignored) { }
            }
        }
    }
}

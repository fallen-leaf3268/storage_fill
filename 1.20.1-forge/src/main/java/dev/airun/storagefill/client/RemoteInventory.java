package dev.airun.storagefill.client;

import dev.airun.storagefill.FillNetwork;
import dev.airun.storagefill.FillPriority;
import dev.airun.storagefill.FillPriorityConfig;
import dev.airun.storagefill.AllocationPlanner;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.RequestLimits;
import dev.airun.storagefill.StackKey;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.RandomAccess;
import java.util.function.Supplier;

@Mod.EventBusSubscriber(modid = StorageFill.ID, value = Dist.CLIENT)
public final class RemoteInventory {
    private static final int MAX_STACK_BYTES = 16384;
    private static final long RESPONSE_TIMEOUT_NANOS = 30_000_000_000L;
    private static ActiveQuery active;
    private static boolean collecting;
    private static int sequence;

    private RemoteInventory() { }

    public static final class View {
        private final List<Stock> entries;
        private final List<ItemStack> inventory;
        private final List<Integer> sourceIds;
        private final int request;
        private final long revision;
        private final FillPriority priority;

        public View(List<ItemStack> stacks, List<Integer> sourceIds, int request) {
            this(stacks, sourceIds, request, 0);
        }
        public View(List<ItemStack> stacks, List<Integer> sourceIds, int request, long revision) {
            this(freeze(stacks, sourceIds), request, revision);
        }
        private View(List<Stock> entries, int request, long revision) {
            this(entries, request, revision, FillPriorityConfig.current());
        }
        private View(List<Stock> entries, int request, long revision, FillPriority priority) {
            this.priority = priority;
            this.entries = entries.stream().sorted(java.util.Comparator.comparingInt(row -> priority.rank(row.sourceId()))).toList();
            this.inventory = new StackList(this.entries);
            this.sourceIds = new AbstractList<>() {
                public Integer get(int index) { return View.this.entries.get(index).sourceId(); }
                public int size() { return View.this.entries.size(); }
            };
            this.request = request;
            this.revision = revision;
        }
        public List<ItemStack> inventory() { return inventory; }
        public List<Integer> sourceIds() { return sourceIds; }
        public int request() { return request; }
        public long revision() { return revision; }
        public FillPriority priority() { return priority; }
        public List<ItemStack> stacks() { return inventory.stream().toList(); }
        List<Stock> entries() { return entries; }

        private static List<Stock> freeze(List<ItemStack> stacks, List<Integer> sources) {
            if (stacks.size() != sources.size()) throw new IllegalArgumentException("Mismatched storage origins");
            List<Stock> entries = new ArrayList<>(stacks.size());
            for (int i = 0; i < stacks.size(); i++) {
                entries.add(new Stock(StackKey.of(stacks.get(i)), stacks.get(i).getCount(), sources.get(i)));
            }
            return entries;
        }
        private static final View EMPTY = new View(List.of(), -1, -1);
    }

    static final class Stock {
        private final StackKey key;
        private final int count;
        private final int sourceId;

        private Stock(StackKey key, int count, int sourceId) {
            this.key = key;
            this.count = count;
            this.sourceId = sourceId;
        }
        StackKey key() { return key; }
        int count() { return count; }
        int sourceId() { return sourceId; }
        ItemStack stack() { return key.stack(count); }
    }

    private static final class StackList extends AbstractList<ItemStack> implements RandomAccess {
        private final List<Stock> entries;
        private StackList(List<Stock> entries) { this.entries = entries; }
        public ItemStack get(int index) { return entries.get(index).stack(); }
        public int size() { return entries.size(); }
    }

    public enum Status { IDLE, QUERYING, READY, MISSING, UNKNOWN, FAILED }

    public record State(Status status, Component reason) { }

    interface Evaluator {
        List<ItemStack> preferred();
        boolean accepts(StackKey key);
        Selection select(View candidates);
        default boolean ruleRejected() { return false; }
    }

    record Selection(List<ItemStack> required, Map<Integer, StackKey> inputs) {
        Selection {
            required = required.stream().map(ItemStack::copy).toList();
            inputs = Map.copyOf(inputs);
        }
        Set<StackKey> keys() {
            Set<StackKey> result = new LinkedHashSet<>(inputs.values());
            required.forEach(stack -> result.add(StackKey.of(stack)));
            return result;
        }
    }

    public static boolean query(AbstractContainerMenu current, Object recipe, IRecipeSlotsView views, Runnable nativePrecheck) {
        closeSession();
        Minecraft client = Minecraft.getInstance();
        if (sequence == Integer.MAX_VALUE || client.player == null || client.player.containerMenu != current
                || !(client.screen instanceof RecipesGui) || client.level == null) return false;
        ActiveQuery query = new ActiveQuery(current, recipe, views, ++sequence, client);
        active = query;
        try {
            collecting = true;
            try { nativePrecheck.run(); }
            finally { collecting = false; }
            if (active != query || !sessionMatches(query)) return false;
            if (query.evaluator == null || client.getConnection() == null
                    || !FillNetwork.CHANNEL.isRemotePresent(client.getConnection().getConnection())) {
                terminate(query, Status.FAILED, "storage_fill.query.unsupported");
                return false;
            }
            Map<StackKey, ItemStack> preferred = new LinkedHashMap<>();
            long bytes = 0;
            for (ItemStack prototype : query.evaluator.preferred()) {
                if (prototype.isEmpty()) continue;
                StackKey key = StackKey.of(prototype);
                if (preferred.containsKey(key)) continue;
                int size = key.data().sizeInBytes();
                bytes += size;
                if (size > MAX_STACK_BYTES || bytes > 262144 || preferred.size() >= RequestLimits.MAX_CANDIDATES) {
                    terminate(query, Status.UNKNOWN, "storage_fill.query.incomplete");
                    return false;
                }
                preferred.put(key, key.stack(1));
            }
            if (preferred.isEmpty()) {
                terminate(query, Status.FAILED, "storage_fill.query.unsupported");
                return false;
            }
            if (active != query || !sessionMatches(query)) return false;
            query.sentAt = System.nanoTime();
            FillNetwork.CHANNEL.sendToServer(new FillNetwork.RecipeQuery(current.containerId, query.request,
                    List.copyOf(preferred.values()), FillNetwork.QUERY_START, query.priority));
            return true;
        } catch (RuntimeException | LinkageError error) {
            collecting = false;
            if (active == query) terminate(query, Status.FAILED, "storage_fill.snapshot_failed");
            StorageFill.LOGGER.error("Unable to query recipe ingredients", error);
            return false;
        }
    }

    static boolean collecting(AbstractContainerMenu current, Object recipe, IRecipeSlotsView views) {
        return collecting && matches(active, current, recipe, views);
    }

    static IRecipeSlotsView playerRecipeSlots(AbstractContainerMenu current, Object recipe, IRecipeSlotsView source,
            Supplier<IRecipeSlotsView> create) {
        ActiveQuery query = active;
        if (!matches(query, current, recipe, source) || query.views != source) return null;
        if (query.playerRecipeSlots == null) query.playerRecipeSlots = create.get();
        return query.playerRecipeSlots;
    }

    static void bind(AbstractContainerMenu current, Object recipe, IRecipeSlotsView views, Evaluator evaluator) {
        if (collecting(current, recipe, views)) active.evaluator = evaluator;
    }

    static AllocationPlanner.SearchBudget searchBudget(AbstractContainerMenu current, Object recipe, IRecipeSlotsView views) {
        if (!collecting(current, recipe, views)) throw new IllegalStateException("No active recipe collector");
        return active.budget;
    }

    public static State state(AbstractContainerMenu current, Object recipe, IRecipeSlotsView views) {
        return matches(active, current, recipe, views) ? new State(active.status, active.reason) : new State(Status.IDLE, Component.empty());
    }

    public static View get(AbstractContainerMenu current, Object recipe, IRecipeSlotsView views, List<ItemStack> candidates) {
        return matches(active, current, recipe, views) && active.status == Status.READY ? active.view : View.EMPTY;
    }

    static Selection selection(AbstractContainerMenu current, Object recipe, IRecipeSlotsView views) {
        return matches(active, current, recipe, views) && active.status == Status.READY ? active.selection : null;
    }

    static boolean shouldWait(AbstractContainerMenu current, Object recipe, IRecipeSlotsView views) {
        return matches(active, current, recipe, views) && active.status == Status.QUERYING;
    }

    static void stopWaiting(AbstractContainerMenu current, Object recipe, IRecipeSlotsView views) {
        if (matches(active, current, recipe, views) && active.status == Status.QUERYING) {
            sendAction(active, FillNetwork.QUERY_CANCEL);
            terminate(active, Status.UNKNOWN, "storage_fill.query.timeout");
        }
    }

    static void reportUnavailable(AbstractContainerMenu current, Object recipe, IRecipeSlotsView views) {
        if (!matches(active, current, recipe, views) || active.notified || active.status == Status.READY
                || active.status == Status.QUERYING || active.status == Status.MISSING) return;
        active.notified = true;
        Minecraft.getInstance().player.displayClientMessage(active.reason, false);
    }

    public static void acceptRecipe(FillNetwork.RecipeQueryBatch batch) {
        ActiveQuery query = active;
        if (query == null || !sessionMatches(query) || query.menu.containerId != batch.menu() || query.request != batch.request()
                || query.status != Status.QUERYING || batch.batch() < query.nextBatch) return;
        if (batch.batch() != query.nextBatch || batch.batch() >= 64 || batch.stacks().size() != batch.sourceIds().size()
                || batch.stacks().size() > 256) {
            sendAction(query, FillNetwork.QUERY_CANCEL);
            terminate(query, Status.UNKNOWN, "storage_fill.query.incomplete");
            return;
        }
        try {
            Map<StackKey, Map<Integer, Integer>> grouped = new LinkedHashMap<>();
            long batchBytes = 0;
            for (int i = 0; i < batch.stacks().size(); i++) {
                ItemStack stack = batch.stacks().get(i);
                int source = batch.sourceIds().get(i);
                if (stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > RequestLimits.MAX_COUNT || source < 1 || source > 4)
                    throw new IllegalArgumentException("Invalid recipe candidate");
                StackKey key = StackKey.of(stack);
                int size = key.data().sizeInBytes();
                batchBytes += size + 64L;
                if (size > MAX_STACK_BYTES || batchBytes > RequestLimits.MAX_SNAPSHOT_BYTES)
                    throw new IllegalArgumentException("Recipe candidate page exceeds budget");
                grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(source, stack.getCount(),
                        (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
            }
            query.nextBatch++;
            boolean evaluated = false;
            for (var group : grouped.entrySet()) {
                StackKey key = group.getKey();
                boolean accepted = query.evaluator.accepts(key);
                if (active != query || !sessionMatches(query) || query.status != Status.QUERYING) return;
                if (!accepted) continue;
                if (!query.pool.containsKey(key)) {
                    int size = key.data().sizeInBytes();
                    if (query.pool.size() >= RequestLimits.MAX_CANDIDATES || query.bytes + size > 262144) {
                        sendAction(query, FillNetwork.QUERY_CANCEL);
                        terminate(query, Status.UNKNOWN, "storage_fill.query.incomplete");
                        return;
                    }
                    query.pool.put(key, new LinkedHashMap<>());
                    query.bytes += size;
                }
                query.pool.put(key, new LinkedHashMap<>(group.getValue()));
                evaluated = true;
                if (finishSelection(query)) return;
            }
            if (!evaluated && finishSelection(query)) return;
            if (batch.truncated()) {
                sendAction(query, FillNetwork.QUERY_CANCEL);
                terminate(query, Status.UNKNOWN, "storage_fill.query.incomplete");
            } else if (batch.exhausted()) {
                sendAction(query, FillNetwork.QUERY_CANCEL);
                boolean rejected = query.evaluator.ruleRejected();
                terminate(query, rejected ? Status.UNKNOWN : Status.MISSING,
                        rejected ? "storage_fill.query.rule_rejected" : "jei.tooltip.error.recipe.transfer.missing");
            } else {
                query.sentAt = System.nanoTime();
                if (!sendAction(query, FillNetwork.QUERY_NEXT)) terminate(query, Status.FAILED, "storage_fill.snapshot_failed");
            }
        } catch (RuntimeException | LinkageError error) {
            sendAction(query, FillNetwork.QUERY_CANCEL);
            terminate(query, Status.FAILED, "storage_fill.snapshot_failed");
            StorageFill.LOGGER.error("Unable to validate recipe query candidates", error);
        }
    }

    private static boolean finishSelection(ActiveQuery query) {
        List<Stock> rows = new ArrayList<>();
        query.pool.forEach((key, origins) -> origins.forEach((source, count) -> rows.add(new Stock(key, count, source))));
        Selection selected = query.evaluator.select(new View(rows, query.request, 0, query.priority));
        if (active != query || !sessionMatches(query) || query.status != Status.QUERYING) return true;
        if (selected == null) {
            if (query.budget.exhausted() || query.budget.remaining() <= 0) {
                sendAction(query, FillNetwork.QUERY_CANCEL);
                terminate(query, Status.UNKNOWN, "storage_fill.query.incomplete");
                return true;
            }
            return false;
        }
        Set<StackKey> keys = selected.keys();
        query.selection = selected;
        query.view = new View(rows.stream().filter(row -> keys.contains(row.key())).toList(), query.request, 0, query.priority);
        query.pool.clear();
        query.bytes = 0;
        query.evaluator = null;
        query.status = Status.READY;
        query.reason = Component.empty();
        query.sentAt = 0;
        if (!sendAction(query, FillNetwork.QUERY_FINISH)) terminate(query, Status.FAILED, "storage_fill.snapshot_failed");
        return true;
    }

    public static void accept(FillNetwork.Snapshot snapshot) { }

    public static void fail(int menuId, int request, Component message) {
        if (active == null || !sessionMatches(active) || active.menu.containerId != menuId || active.request != request) return;
        terminate(active, Status.FAILED, "storage_fill.snapshot_failed");
        active.reason = message;
    }

    private static void terminate(ActiveQuery query, Status status, String reason) {
        query.pool.clear();
        query.bytes = 0;
        query.selection = null;
        query.evaluator = null;
        query.view = View.EMPTY;
        query.status = status;
        query.reason = Component.translatable(reason);
        query.sentAt = 0;
    }

    private static boolean matches(ActiveQuery query, AbstractContainerMenu menu, Object recipe, IRecipeSlotsView views) {
        return query != null && query.menu == menu && query.recipe == recipe
            && (query.views == views || query.playerRecipeSlots != null && query.playerRecipeSlots == views)
            && sessionMatches(query);
    }

    private static boolean sessionMatches(ActiveQuery query) {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.containerMenu == query.menu && client.getConnection() == query.connection
                && client.level == query.level && client.screen == query.screen && query.screen instanceof RecipesGui;
    }

    private static boolean sendAction(ActiveQuery query, int action) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || connection != query.connection) return false;
        try {
            FillNetwork.CHANNEL.sendToServer(new FillNetwork.RecipeQuery(query.menu.containerId, query.request, List.of(), action, query.priority));
            return true;
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Unable to update recipe query", error);
            return false;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) return;
        if (!sessionMatches(active)) closeSession();
        else if (active.status == Status.QUERYING && active.sentAt != 0
                && System.nanoTime() - active.sentAt > RESPONSE_TIMEOUT_NANOS) {
            sendAction(active, FillNetwork.QUERY_CANCEL);
            terminate(active, Status.UNKNOWN, "storage_fill.query.timeout");
        }
    }

    @SubscribeEvent
    public static void screenClosed(ScreenEvent.Closing event) {
        if (active != null && event.getScreen() == active.screen) closeSession();
    }

    private static void closeSession() {
        ActiveQuery previous = active;
        active = null;
        collecting = false;
        if (previous != null) {
            previous.pool.clear();
            previous.view = View.EMPTY;
            previous.selection = null;
            previous.evaluator = null;
            previous.playerRecipeSlots = null;
            sendAction(previous, FillNetwork.QUERY_CANCEL);
        }
        ClientTransfer.cancelForQuery();
    }

    public static void invalidate() { closeSession(); }

    private static final class ActiveQuery {
        final AbstractContainerMenu menu;
        final Object recipe;
        final IRecipeSlotsView views;
        final int request;
        final Screen screen;
        final Object connection;
        final Object level;
        final FillPriority priority;
        final Map<StackKey, Map<Integer, Integer>> pool = new LinkedHashMap<>();
        final AllocationPlanner.SearchBudget budget = new AllocationPlanner.SearchBudget();
        Evaluator evaluator;
        IRecipeSlotsView playerRecipeSlots;
        Selection selection;
        View view = View.EMPTY;
        Status status = Status.QUERYING;
        Component reason = Component.empty();
        int nextBatch;
        long bytes;
        long sentAt;
        boolean notified;
        ActiveQuery(AbstractContainerMenu menu, Object recipe, IRecipeSlotsView views, int request, Minecraft client) {
            this.menu = menu;
            this.recipe = recipe;
            this.views = views;
            this.request = request;
            this.screen = client.screen;
            this.connection = client.getConnection();
            this.level = client.level;
            this.priority = FillPriorityConfig.current();
        }
    }

    enum Outcome { IGNORED, PENDING, COMPLETE, INVALID }

    static final class SnapshotCache {
        private static final long MAX_VIEW_BYTES = 1_048_576;
        private final int menuId;
        private final int request;
        private final Map<Item, Map<CacheKey, Integer>> items = new LinkedHashMap<>();
        private final Map<Item, List<Stock>> rows = new LinkedHashMap<>();
        private final Map<Item, Map<CacheKey, Integer>> stagedItems = new LinkedHashMap<>();
        private final Map<Set<Item>, View> views = new LinkedHashMap<>(16, 0.75f, true);
        private Set<Item> covered = Set.of();
        private final Set<Item> known = new LinkedHashSet<>();
        private final Map<Item, Long> itemBytes = new LinkedHashMap<>();
        private long storedBytes;
        private long viewBytes;
        private int storedEntries;
        private int receiving = -1;
        private int lastRevision = -1;
        private long viewRevision;
        private boolean allKnown;
        private boolean reset;
        private int nextPart;
        private int entries;
        private long bytes;
        private boolean complete;
        private boolean failed;
        private boolean truncated;
        private boolean failureNotified;

        SnapshotCache(int menuId, int request) {
            this.menuId = menuId;
            this.request = request;
        }

        Outcome accept(FillNetwork.Snapshot snapshot) {
            if (failed || snapshot.menu() != menuId || snapshot.request() != request
                || snapshot.revision() <= lastRevision) return Outcome.IGNORED;
            if (snapshot.revision() != receiving) {
                if (snapshot.revision() < receiving) return Outcome.IGNORED;
                if (snapshot.part() != 0) return reject();
                receiving = snapshot.revision();
                reset = snapshot.reset();
                covered = new LinkedHashSet<>(snapshot.covered());
                nextPart = 0;
                entries = 0;
                bytes = 0;
                stagedItems.clear();
            }
            if (snapshot.part() != nextPart || nextPart > RequestLimits.MAX_SESSION_STACKS
                || (snapshot.truncated() && !snapshot.last())
                || snapshot.stacks().size() != snapshot.sourceIds().size()
                || snapshot.stacks().size() > RequestLimits.MAX_SNAPSHOT
                || snapshot.stacks().size() > RequestLimits.MAX_SESSION_STACKS - entries) return reject();
            List<Counted> staged = new ArrayList<>(snapshot.stacks().size());
            long partBytes = 0;
            try {
                for (int i = 0; i < snapshot.stacks().size(); i++) {
                    ItemStack stack = snapshot.stacks().get(i);
                    int sourceId = snapshot.sourceIds().get(i);
                    if (stack == null || stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > RequestLimits.MAX_COUNT
                        || sourceId < 1 || sourceId > 4 || (receiving > 0 && !covered.contains(stack.getItem()))) return reject();
                    StackKey key = StackKey.of(stack);
                    long stackBytes = key.data().sizeInBytes();
                    if (stackBytes > MAX_STACK_BYTES) return reject();
                    partBytes += stackBytes + 64;
                    if (partBytes > RequestLimits.MAX_SNAPSHOT_BYTES || bytes + partBytes > RequestLimits.MAX_SESSION_BYTES) return reject();
                    staged.add(new Counted(new CacheKey(key, sourceId), stack.getCount()));
                }
            } catch (RuntimeException | LinkageError error) {
                return reject();
            }
            for (Counted counted : staged) {
                Map<CacheKey, Integer> variants = stagedItems.computeIfAbsent(counted.key().stack().item(), ignored -> new LinkedHashMap<>());
                variants.merge(counted.key(), counted.count(), (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
            }
            bytes += partBytes;
            entries += staged.size();
            nextPart++;
            if (!snapshot.last()) return Outcome.PENDING;
            if (receiving == 0) {
                covered = new LinkedHashSet<>(stagedItems.keySet());
                allKnown = !snapshot.truncated();
            }
            if (reset) {
                items.clear();
                rows.clear();
                known.clear();
                itemBytes.clear();
                views.clear();
                viewBytes = 0;
                storedBytes = 0;
                storedEntries = 0;
                allKnown = false;
            }
            long newBytes = storedBytes;
            int newEntries = storedEntries;
            Map<Item, Long> sizes = new LinkedHashMap<>();
            Set<Item> changed = new LinkedHashSet<>();
            for (Item item : covered) {
                var old = items.getOrDefault(item, Map.of());
                var replacement = stagedItems.getOrDefault(item, Map.of());
                if (!known.contains(item) || !old.equals(replacement)) changed.add(item);
                long size = replacement.keySet().stream().mapToLong(key -> key.stack().data().sizeInBytes() + 64L).sum();
                newBytes += size - itemBytes.getOrDefault(item, 0L);
                newEntries += replacement.size() - old.size();
                sizes.put(item, size);
            }
            int newKnown = known.size();
            for (Item item : covered) if (!known.contains(item)) newKnown++;
            var evictions = known.iterator();
            while ((newBytes > RequestLimits.MAX_SESSION_BYTES || newEntries > RequestLimits.MAX_SESSION_STACKS || newKnown > 4096)
                    && evictions.hasNext()) {
                Item item = evictions.next();
                if (covered.contains(item)) continue;
                evictions.remove();
                allKnown = false;
                newKnown--;
                newBytes -= itemBytes.getOrDefault(item, 0L);
                itemBytes.remove(item);
                var removed = items.remove(item);
                rows.remove(item);
                if (removed != null) newEntries -= removed.size();
                changed.add(item);
            }
            if (newBytes > RequestLimits.MAX_SESSION_BYTES || newEntries > RequestLimits.MAX_SESSION_STACKS || newKnown > 4096) return reject();
            for (Item item : covered) {
                var replacement = stagedItems.get(item);
                if (changed.contains(item)) {
                    items.remove(item);
                    if (replacement != null) items.put(item, replacement);
                    if (replacement == null) rows.remove(item);
                    else rows.put(item, replacement.entrySet().stream().map(entry ->
                            new Stock(entry.getKey().stack(), entry.getValue(), entry.getKey().sourceId())).toList());
                }
                itemBytes.put(item, sizes.get(item));
            }
            storedBytes = newBytes;
            storedEntries = newEntries;
            known.addAll(covered);
            var cachedViews = views.entrySet().iterator();
            while (cachedViews.hasNext()) {
                var entry = cachedViews.next();
                if (entry.getKey().stream().anyMatch(changed::contains)) {
                    viewBytes -= viewBytes(entry.getKey(), entry.getValue());
                    cachedViews.remove();
                }
            }
            stagedItems.clear();
            lastRevision = receiving;
            if (!changed.isEmpty()) viewRevision++;
            complete = true;
            truncated = snapshot.truncated();
            return Outcome.COMPLETE;
        }

        View view(Set<Item> wanted) {
            if (!complete || failed || (!allKnown && !known.containsAll(wanted))) return View.EMPTY;
            View existing = views.get(wanted);
            if (existing != null) return existing;
            List<Stock> entries = new ArrayList<>();
            for (Item item : wanted) {
                List<Stock> variants = rows.get(item);
                if (variants == null) continue;
                entries.addAll(variants);
            }
            View result = new View(entries, request, viewRevision);
            Set<Item> key = Set.copyOf(wanted);
            long size = viewBytes(key, result);
            if (size > MAX_VIEW_BYTES) return result;
            views.put(key, result);
            viewBytes += size;
            var evictions = views.entrySet().iterator();
            while (views.size() > 128 || viewBytes > MAX_VIEW_BYTES) {
                var removed = evictions.next();
                viewBytes -= viewBytes(removed.getKey(), removed.getValue());
                evictions.remove();
            }
            return result;
        }

        private static long viewBytes(Set<Item> wanted, View view) {
            return 128L + wanted.size() * 16L + view.entries().size() * 8L;
        }

        boolean isPending() {
            return !failed && (!complete || receiving > lastRevision);
        }

        boolean truncated() {
            return truncated;
        }

        void fail() {
            failed = true;
            complete = false;
            items.clear();
            rows.clear();
            stagedItems.clear();
            views.clear();
            viewBytes = 0;
            known.clear();
            itemBytes.clear();
        }

        private Outcome reject() {
            fail();
            return Outcome.INVALID;
        }

        private record CacheKey(StackKey stack, int sourceId) { }
        private record Counted(CacheKey key, int count) { }
    }
}

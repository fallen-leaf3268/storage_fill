package dev.airun.storagefill.compat.ftb;

import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.storage.StorageChanges;
import dev.airun.storagefill.storage.StorageSource;
import dev.airun.storagefill.storage.StorageSources;
import dev.ftb.mods.ftbquests.events.ClearFileCacheEvent;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbquests.events.ObjectProgressEvent;
import dev.ftb.mods.ftbquests.events.ObjectStartedEvent;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class FtbDetectionQueue {
    private static final Map<UUID, Request> PENDING = new LinkedHashMap<>();
    private static final ArrayDeque<Work> ACTIVE = new ArrayDeque<>();
    private static final Map<UUID, Work> ACTIVE_PLAYERS = new HashMap<>();
    private static final Map<UUID, List<SourceRef>> ACCESS = new HashMap<>();
    private static final PriorityQueue<ManualRepeat> MANUAL_REPEATS = new PriorityQueue<>(
        Comparator.comparingLong(repeat -> repeat.readyAt));
    private static final Map<Item, List<ItemTask>> BY_ITEM = new HashMap<>();
    private static final Map<Long, List<ItemTask>> DEPENDENTS = new HashMap<>();
    private static List<ItemTask> allTasks = List.of();
    private static List<Task> indexedTasks;
    private static ServerQuestFile indexedFile;
    private static long dispatchedAt;
    private static Wave currentWave;

    private FtbDetectionQueue() { }

    public static void register() {
        StorageChanges.enable();
        NeoForge.EVENT_BUS.addListener(FtbDetectionQueue::tick);
        NeoForge.EVENT_BUS.addListener(FtbDetectionQueue::logout);
        NeoForge.EVENT_BUS.addListener(FtbDetectionQueue::dimensionChanged);
        NeoForge.EVENT_BUS.addListener(FtbDetectionQueue::containerOpened);
        NeoForge.EVENT_BUS.addListener(FtbDetectionQueue::stopped);
        TeamEvent.PLAYER_LOGGED_IN.register(event -> refresh(event.getPlayer()));
        TeamEvent.PLAYER_CHANGED.register(event -> {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                discard(event.getPlayerId());
                refresh(server.getPlayerList().getPlayer(event.getPlayerId()));
            }
        });
        ObjectCompletedEvent.GENERIC.register(event -> {
            progression(event, true);
            return dev.architectury.event.EventResult.pass();
        });
        ObjectStartedEvent.GENERIC.register(event -> {
            progression(event, false);
            return dev.architectury.event.EventResult.pass();
        });
        ClearFileCacheEvent.EVENT.register(file -> {
            if (file instanceof ServerQuestFile) {
                indexedTasks = null;
                MANUAL_REPEATS.removeIf(repeat -> repeat.data.getFile() != file || file.getQuest(repeat.quest.id) != repeat.quest);
                var server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) for (ServerPlayer player : server.getPlayerList().getPlayers()) refresh(player);
            }
        });
    }

    private static void refresh(ServerPlayer player) {
        if (player == null || player instanceof FakePlayer) return;
        request(player, new Wave()).full = true;
    }

    public static void afterManualRewardClaim(ServerPlayer player, TeamData data, Quest quest, int completedBefore) {
        if (!quest.canBeRepeated() || player instanceof FakePlayer) return;
        int completed = data.getCompletionCount(quest);
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (completed <= completedBefore || file == null || data.getFile() != file || file.getQuest(quest.id) != quest
            || TeamData.get(player) != data || data.isLocked()) return;
        Set<Completion> completedTasks = new HashSet<>();
        for (Task task : quest.getTasksAsList()) {
            if (task instanceof ItemTask item && FtbAutoDetection.supports(item))
                completedTasks.add(new Completion(data.getTeamId(), task.id));
        }
        if (completedTasks.isEmpty()) return;
        for (Request pending : PENDING.values()) pending.wave.completed.addAll(completedTasks);
        for (Work active : ACTIVE) if (active.data == data) active.wave.completed.addAll(completedTasks);
        UUID playerId = player.getUUID();
        MANUAL_REPEATS.removeIf(repeat -> repeat.playerId.equals(playerId) && repeat.quest.id == quest.id);
        MANUAL_REPEATS.add(new ManualRepeat(playerId, data, quest, completed,
            System.currentTimeMillis() + data.getMilliSecondsUntilRepeatable(quest)));
    }

    private static int prepareManualRepeats(MinecraftServer server, ServerQuestFile file, long deadline) {
        int prepared = 0;
        while (!MANUAL_REPEATS.isEmpty() && prepared < 2 && System.nanoTime() < deadline) {
            long now = System.currentTimeMillis();
            if (MANUAL_REPEATS.peek().readyAt > now) break;
            ManualRepeat repeat = MANUAL_REPEATS.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(repeat.playerId);
            if (player == null || player instanceof FakePlayer) continue;
            TeamData data = TeamData.get(player);
            Quest quest = repeat.quest;
            if (data != repeat.data || data.getFile() != file || file.getQuest(quest.id) != quest || data.isLocked()
                || !quest.canBeRepeated() || data.getCompletionCount(quest) != repeat.completed || data.isCompleted(quest)
                || quest.getTasksAsList().stream().noneMatch(task -> task instanceof ItemTask item && FtbAutoDetection.supports(item))) continue;
            long remaining = data.getMilliSecondsUntilRepeatable(quest);
            if (remaining > 0 || ACTIVE_PLAYERS.containsKey(repeat.playerId)) {
                repeat.readyAt = now + Math.max(50, remaining);
                MANUAL_REPEATS.add(repeat);
                continue;
            }
            prepared++;
            try {
                List<StorageSource> sources = StorageSources.find(player);
                Work work = new Work(player, data, sources, new Wave());
                for (Task task : quest.getTasksAsList()) work.add(task);
                if (!work.tasks.isEmpty()) {
                    ACTIVE.addLast(work);
                    ACTIVE_PLAYERS.put(player.getUUID(), work);
                }
            } catch (RuntimeException | LinkageError error) {
                StorageFill.LOGGER.error("Unable to prepare FTB storage detection after a manual repeat reward", error);
            }
        }
        return prepared;
    }

    private static Request request(ServerPlayer player, Wave wave) {
        return PENDING.computeIfAbsent(player.getUUID(), ignored -> new Request(player.server.getTickCount() + 10L, wave));
    }

    private static void progression(ObjectProgressEvent<?> event, boolean completed) {
        if (!(event.getData().getFile() instanceof ServerQuestFile file) || file.isLoading()) return;
        index(file);
        Wave wave = currentWave == null ? new Wave() : currentWave;
        Set<Long> ids = new LinkedHashSet<>();
        if (completed && event.getObject() instanceof Task task) {
            wave.completed.add(new Completion(event.getData().getTeamId(), task.id));
            var sequence = task.getQuest().getTasksAsList();
            int position = sequence.indexOf(task);
            if (task.getQuest().getRequireSequentialTasks() && position >= 0 && position + 1 < sequence.size()
                && sequence.get(position + 1) instanceof ItemTask next && FtbAutoDetection.supports(next)) ids.add(next.id);
        }
        for (ItemTask task : DEPENDENTS.getOrDefault(event.getObject().id, List.of())) ids.add(task.id);
        if (ids.isEmpty()) return;
        for (ServerPlayer player : event.getOnlineMembers()) {
            if (player instanceof FakePlayer) continue;
            Work active = ACTIVE_PLAYERS.get(player.getUUID());
            if (active != null && active.wave == wave) {
                for (long id : ids) active.add(file.getTask(id));
            } else {
                Request pending = request(player, wave);
                pending.wave.completed.addAll(wave.completed);
                pending.taskIds.addAll(ids);
            }
        }
    }

    private static void index(ServerQuestFile file) {
        List<Task> tasks = file.getSubmitTasks();
        if (file == indexedFile && tasks == indexedTasks) return;
        indexedFile = file;
        indexedTasks = tasks;
        BY_ITEM.clear();
        DEPENDENTS.clear();
        List<ItemTask> accepted = new ArrayList<>();
        for (Task candidate : tasks) {
            if (!(candidate instanceof ItemTask task) || !FtbAutoDetection.supports(task)) continue;
            accepted.add(task);
            BY_ITEM.computeIfAbsent(task.getItemStack().getItem(), ignored -> new ArrayList<>()).add(task);
            task.getQuest().streamDependencies().forEach(dependency ->
                DEPENDENTS.computeIfAbsent(dependency.id, ignored -> new ArrayList<>()).add(task));
        }
        allTasks = List.copyOf(accepted);
    }

    private static void tick(ServerTickEvent.Post event) {
        serverTick(event.getServer());
    }

    public static void serverTick(MinecraftServer server) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || file.isLoading()) return;
        boolean repeatReady = !MANUAL_REPEATS.isEmpty() && MANUAL_REPEATS.peek().readyAt <= System.currentTimeMillis();
        if (!StorageChanges.hasChanges() && PENDING.isEmpty() && ACTIVE.isEmpty() && !repeatReady) return;
        index(file);
        if (allTasks.isEmpty()) {
            StorageChanges.clear();
            PENDING.clear();
            ACTIVE.clear();
            ACTIVE_PLAYERS.clear();
            MANUAL_REPEATS.clear();
            return;
        }
        long now = server.getTickCount();
        if (StorageChanges.hasChanges() && now - dispatchedAt >= 10) {
            StorageChanges.Batch batch = StorageChanges.drain();
            List<StorageChanges.Change> relevant = batch.changes().stream()
                .filter(change -> change.allItems() || change.items().stream().anyMatch(BY_ITEM::containsKey)).toList();
            if (batch.allSources() || !relevant.isEmpty()) {
                Wave wave = new Wave();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player instanceof FakePlayer) continue;
                    Request request = request(player, wave);
                    request.full |= batch.allSources();
                    for (StorageChanges.Change change : relevant) request.merge(change);
                }
            }
            dispatchedAt = now;
        }
        if (PENDING.isEmpty() && ACTIVE.isEmpty() && !repeatReady) return;
        index(file);
        long deadline = System.nanoTime() + 2_000_000L;
        int prepared = prepareManualRepeats(server, file, deadline);
        var requests = PENDING.entrySet().iterator();
        while (requests.hasNext() && prepared < 2 && System.nanoTime() < deadline) {
            var entry = requests.next();
            if (entry.getValue().due > now || ACTIVE_PLAYERS.containsKey(entry.getKey())) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Request request = entry.getValue();
            requests.remove();
            prepared++;
            if (player == null || player instanceof FakePlayer) continue;
            try {
                List<StorageSource> sources = StorageSources.find(player);
                List<SourceRef> refs = sourceRefs(sources);
                List<SourceRef> previous = ACCESS.put(player.getUUID(), refs);
                if (request.recheckAccess && !refs.equals(previous)) request.full = true;
                TeamData data = TeamData.get(player);
                if (data == null || data.isLocked()) continue;
                Work work = new Work(player, data, sources, request.wave);
                if (request.full) for (ItemTask task : allTasks) work.add(task);
                else {
                    for (long id : request.taskIds) work.add(file.getTask(id));
                    for (StorageSource source : sources) {
                        StorageChanges.Change change = request.changes.get(source.changeIdentity());
                        if (change == null || change.sourceId() != source.sourceId()) continue;
                        if (change.allItems()) for (ItemTask task : allTasks) work.add(task);
                        else for (Item item : change.items()) for (ItemTask task : BY_ITEM.getOrDefault(item, List.of())) work.add(task);
                    }
                }
                if (!work.tasks.isEmpty()) {
                    ACTIVE.addLast(work);
                    ACTIVE_PLAYERS.put(player.getUUID(), work);
                }
            } catch (RuntimeException | LinkageError error) {
                StorageFill.LOGGER.error("Unable to prepare FTB storage detection for an online player", error);
            }
        }
        int deferred = 0;
        for (int attempts = 0; attempts < 128 && !ACTIVE.isEmpty() && System.nanoTime() < deadline; attempts++) {
            Work work = ACTIVE.removeFirst();
            boolean online = server.getPlayerList().getPlayers().contains(work.player);
            if (online && work.position < work.tasks.size() && TeamData.get(work.player) == work.data) {
                if (work.sessionTick != now) {
                    if (work.accessDirty) {
                        try {
                            List<StorageSource> refreshed = StorageSources.find(work.player);
                            List<SourceRef> refs = sourceRefs(refreshed);
                            if (!refs.equals(ACCESS.put(work.player.getUUID(), refs))) request(work.player, work.wave).full = true;
                            work.sources = refreshed;
                            work.accessDirty = false;
                        } catch (RuntimeException | LinkageError error) {
                            StorageFill.LOGGER.error("Unable to refresh FTB storage access after a task callback", error);
                            work.position = work.tasks.size();
                            ACTIVE_PLAYERS.remove(work.player.getUUID(), work);
                            continue;
                        }
                    }
                    work.session = new FtbDetectionSession(16);
                    work.sessionTick = now;
                }
                ItemTask task = work.tasks.get(work.position);
                boolean eligible = !work.wave.completed.contains(new Completion(work.data.getTeamId(), task.id))
                    && !work.tried.contains(task.id) && FtbAutoDetection.valid(work.player, work.data, task);
                if (eligible && !work.session.accepts(task)) {
                    ACTIVE.addLast(work);
                    if (++deferred >= ACTIVE.size()) break;
                    continue;
                }
                deferred = 0;
                work.position++;
                work.scheduled.remove(task.id);
                if (eligible) {
                    work.tried.add(task.id);
                    currentWave = work.wave;
                    try { FtbAutoDetection.detect(work.player, work.data, List.of(task), work.sources, work.session); }
                    catch (RuntimeException | LinkageError error) {
                        StorageFill.LOGGER.error("Unable to update a non-consuming FTB storage task", error);
                    } finally {
                        currentWave = null;
                        if (work.session.wroteProgress()) {
                            work.accessDirty = true;
                            request(work.player, work.wave).recheckAccess = true;
                        }
                    }
                }
            } else work.position = work.tasks.size();
            if (work.position < work.tasks.size()) ACTIVE.addLast(work);
            else ACTIVE_PLAYERS.remove(work.player.getUUID(), work);
        }
    }

    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        discard(event.getEntity().getUUID());
    }

    private static void discard(UUID id) {
        PENDING.remove(id);
        MANUAL_REPEATS.removeIf(repeat -> repeat.playerId.equals(id));
        ACTIVE.removeIf(work -> work.player.getUUID().equals(id));
        ACTIVE_PLAYERS.remove(id);
        ACCESS.remove(id);
    }

    private static void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) refresh(player);
    }

    private static void containerOpened(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || file.isLoading()) return;
        index(file);
        if (allTasks.isEmpty()) return;
        try {
            List<SourceRef> sources = sourceRefs(StorageSources.find(player));
            if (!sources.equals(ACCESS.put(player.getUUID(), sources))) refresh(player);
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Unable to check storage access after opening a container", error);
        }
    }

    private static List<SourceRef> sourceRefs(List<StorageSource> sources) {
        return sources.stream().map(source -> new SourceRef(source.sourceId(), source.changeIdentity())).toList();
    }

    private static void stopped(ServerStoppedEvent event) {
        PENDING.clear();
        MANUAL_REPEATS.clear();
        ACTIVE.clear();
        ACTIVE_PLAYERS.clear();
        ACCESS.clear();
        BY_ITEM.clear();
        DEPENDENTS.clear();
        allTasks = List.of();
        indexedTasks = null;
        indexedFile = null;
        dispatchedAt = 0;
        currentWave = null;
        StorageChanges.clear();
    }

    private static final class Wave {
        private final Set<Completion> completed = new HashSet<>();
    }

    private record Completion(UUID teamId, long taskId) { }
    private record SourceRef(int sourceId, Object identity) { }

    private static final class ManualRepeat {
        private final UUID playerId;
        private final TeamData data;
        private final Quest quest;
        private final int completed;
        private long readyAt;

        private ManualRepeat(UUID playerId, TeamData data, Quest quest, int completed, long readyAt) {
            this.playerId = playerId;
            this.data = data;
            this.quest = quest;
            this.completed = completed;
            this.readyAt = readyAt;
        }
    }

    private static final class Request {
        private final long due;
        private final Wave wave;
        private final Map<Object, StorageChanges.Change> changes = new IdentityHashMap<>();
        private final Set<Long> taskIds = new LinkedHashSet<>();
        private boolean full;
        private boolean recheckAccess;

        private Request(long due, Wave wave) { this.due = due; this.wave = wave; }

        private void merge(StorageChanges.Change change) {
            if (full) return;
            StorageChanges.Change previous = changes.get(change.identity());
            if (previous == null) {
                if (changes.size() >= 256) { full = true; changes.clear(); return; }
                changes.put(change.identity(), change);
            } else {
                if (previous.allItems() || previous.items().containsAll(change.items()) && !change.allItems()) return;
                if (change.allItems()) {
                    changes.put(change.identity(), change);
                    return;
                }
                Set<Item> items = new HashSet<>(previous.items());
                items.addAll(change.items());
                boolean all = items.size() > 128;
                changes.put(change.identity(), new StorageChanges.Change(change.sourceId(), change.identity(),
                    all ? Set.of() : Set.copyOf(items), all));
            }
        }
    }

    private static final class Work {
        private final ServerPlayer player;
        private final TeamData data;
        private List<StorageSource> sources;
        private final Wave wave;
        private final List<ItemTask> tasks = new ArrayList<>();
        private final Set<Long> tried = new HashSet<>();
        private final Set<Long> scheduled = new HashSet<>();
        private int position;
        private long sessionTick = Long.MIN_VALUE;
        private FtbDetectionSession session;
        private boolean accessDirty;

        private Work(ServerPlayer player, TeamData data, List<StorageSource> sources, Wave wave) {
            this.player = player;
            this.data = data;
            this.sources = sources;
            this.wave = wave;
        }

        private void add(Task candidate) {
            if (candidate instanceof ItemTask task && FtbAutoDetection.supports(task) && !tried.contains(task.id)
                && !wave.completed.contains(new Completion(data.getTeamId(), task.id)) && scheduled.add(task.id)) tasks.add(task);
        }
    }
}

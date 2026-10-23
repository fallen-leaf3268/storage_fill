package dev.airun.storagefill.compat.ftb;

import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.storage.StorageSource;
import dev.airun.storagefill.storage.StorageSources;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import dev.ftb.mods.ftbquests.item.MissingItem;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

public final class FtbQuestSubmission {
    private static final Map<ServerPlayer, Request> REQUESTS = new WeakHashMap<>();
    private static final int MAX_EXTRACTIONS = 64;
    private static final int MAX_VARIANTS = 512;

    private FtbQuestSubmission() { }

    public static boolean supports(ItemTask task) {
        ItemStack stack = task.getItemStack();
        return task.getClass() == ItemTask.class && task.consumesResources() && !task.isTaskScreenOnly()
            && !stack.isEmpty() && !(stack.getItem() instanceof MissingItem)
            && !ItemMatchingSystem.INSTANCE.isItemFilter(stack);
    }

    public static Result submit(ServerPlayer player, TeamData data, ItemTask task,
                                Supplier<List<StorageSource>> sources) {
        long budget = Math.max(0, task.getMaxProgress() - data.getProgress(task));
        if (!player.server.isSameThread()) return new Result(0, budget, true);
        long now = player.server.getTickCount();
        Request previous = REQUESTS.get(player);
        if (previous != null && (previous.active || now - previous.tick < 4)) return new Result(0, budget, true);
        Request request = new Request(now);
        REQUESTS.put(player, request);
        Submission submission = new Submission(player, data, task, budget);
        try {
            if (!submission.valid()) return new Result(0, budget, true);
            submission.backpack();
            if (submission.remaining() > 0 && !submission.stopped) submission.storage(sources.get());
            return new Result(submission.submitted, submission.remaining(), submission.failed);
        } catch (RuntimeException | LinkageError error) {
            StorageFill.LOGGER.error("Unable to prepare FTB storage submission", error);
            return new Result(submission.submitted, submission.remaining(), true);
        } finally {
            request.active = false;
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
    }

    public record Result(long submitted, long remaining, boolean failed) { }

    private static final class Request {
        private final long tick;
        private boolean active = true;
        private Request(long tick) { this.tick = tick; }
    }

    private static final class Submission {
        private final ServerPlayer player;
        private final TeamData data;
        private final ItemTask task;
        private final long budget;
        private long submitted;
        private boolean failed;
        private boolean stopped;
        private int operations;
        private int variants;

        private Submission(ServerPlayer player, TeamData data, ItemTask task, long budget) {
            this.player = player;
            this.data = data;
            this.task = task;
            this.budget = budget;
        }

        private long remaining() { return Math.max(0, budget - submitted); }

        private boolean valid() {
            if (!supports(task) || !(data.getFile() instanceof ServerQuestFile file)
                || file != ServerQuestFile.INSTANCE || file.isLoading() || file.getTask(task.id) != task
                || TeamData.get(player) != data || data.isLocked() || data.isCompleted(task)
                || task.isTaskScreenOnly() || !data.canStartTasks(task.getQuest())
                || !player.isAlive() || player.isRemoved()) return false;
            if (!task.getQuest().getRequireSequentialTasks()) return true;
            var tasks = task.getQuest().getTasksAsList();
            int index = tasks.indexOf(task);
            return index >= 0 && (index == 0 || data.isCompleted(tasks.get(index - 1)));
        }

        private void backpack() {
            for (int slot = 0; slot < player.getInventory().items.size() && remaining() > 0 && !stopped; slot++) {
                if (!valid()) { failed = true; stopped = true; break; }
                ItemStack present = player.getInventory().items.get(slot);
                if (present.isEmpty() || !task.test(present)) continue;
                ItemStack held = player.getInventory().removeItem(slot, (int) Math.min(remaining(), present.getCount()));
                try { consume(held); }
                finally { refund(null, held); }
            }
        }

        private void storage(List<StorageSource> sources) {
            Map<StorageSource, Integer> consumed = new IdentityHashMap<>();
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                for (StorageSource source : sources) {
                    if (remaining() <= 0 || stopped || operations >= MAX_EXTRACTIONS || variants >= MAX_VARIANTS) break;
                    if (!visited.add(source.identity())) continue;
                    consumed.put(source, 0);
                    ItemStack prototype = task.getItemStack().copyWithCount(1);
                    extract(source, prototype, consumed);
                    if (remaining() <= 0 || stopped || operations >= MAX_EXTRACTIONS) break;
                    List<ItemStack> candidates = new ArrayList<>();
                    source.visitPreview(Set.of(task.getItemStack().getItem()), candidate -> {
                        if (variants >= MAX_VARIANTS) return false;
                        variants++;
                        if (!candidate.isEmpty() && !ItemStack.isSameItemSameComponents(prototype, candidate) && task.test(candidate)) {
                            candidates.add(candidate.copyWithCount(1));
                        }
                        return variants < MAX_VARIANTS;
                    });
                    for (ItemStack candidate : candidates) {
                        if (remaining() <= 0 || stopped || operations >= MAX_EXTRACTIONS) break;
                        extract(source, candidate, consumed);
                    }
                }
            } catch (RuntimeException | LinkageError error) {
                failed = true;
                stopped = true;
                StorageFill.LOGGER.error("FTB storage submission stopped after confirmed withdrawals", error);
            } finally {
                for (var entry : consumed.entrySet()) {
                    try { entry.getKey().finish(entry.getValue()); }
                    catch (RuntimeException | LinkageError error) {
                        failed = true;
                        StorageFill.LOGGER.error("Unable to finish an FTB storage source", error);
                    }
                }
            }
        }

        private void extract(StorageSource source, ItemStack candidate, Map<StorageSource, Integer> consumed) {
            while (remaining() > 0 && !stopped && operations < MAX_EXTRACTIONS) {
                if (!valid()) { failed = true; stopped = true; break; }
                int requested = (int) Math.min(remaining(), StorageSources.MAX_COUNT);
                ItemStack preview = source.extract(candidate.copyWithCount(1), requested, true);
                if (preview.isEmpty()) break;
                if (!matches(candidate, preview, requested)) throw new IllegalStateException("Invalid storage extraction preview");
                operations++;
                ItemStack held = source.extract(candidate.copyWithCount(1), preview.getCount(), false);
                if (held.isEmpty()) break;
                int accepted = 0;
                try {
                    if (!matches(candidate, held, preview.getCount())) throw new IllegalStateException("Invalid storage withdrawal");
                    accepted = consume(held);
                } finally {
                    consumed.merge(source, accepted, Integer::sum);
                    refund(source, held);
                }
                if (accepted == 0) break;
            }
        }

        private int consume(ItemStack held) {
            if (held.isEmpty() || !valid()) { failed = true; stopped = true; return 0; }
            ItemStack offered = held.copy();
            long before = data.getProgress(task);
            ItemStack preview = task.insert(data, offered, true);
            int accepted = offered.getCount() - preview.getCount();
            if (accepted <= 0) return 0;
            held.shrink(accepted);
            submitted += accepted;
            try {
                ItemStack[] result = new ItemStack[1];
                ((ServerQuestFile) data.getFile()).withPlayerContext(player,
                    () -> result[0] = task.insert(data, offered, false));
                if (!matches(offered, result[0], offered.getCount()) && !result[0].isEmpty()) {
                    throw new IllegalStateException("Invalid FTB submission remainder");
                }
                int actual = offered.getCount() - result[0].getCount();
                submitted += actual - accepted;
                held.setCount(result[0].getCount());
                accepted = actual;
            } catch (RuntimeException | LinkageError error) {
                failed = true;
                stopped = true;
                StorageFill.LOGGER.error("FTB submission callback failed after reserving its accepted materials", error);
            }
            if (data.getProgress(task) != before + accepted || !valid()) stopped = true;
            return accepted;
        }

        private static boolean matches(ItemStack prototype, ItemStack stack, int maximum) {
            return !stack.isEmpty() && stack.getCount() <= maximum && ItemStack.isSameItemSameComponents(prototype, stack);
        }

        private void refund(StorageSource source, ItemStack held) {
            if (held.isEmpty()) return;
            ItemStack remainder = held;
            if (source != null) {
                try { remainder = source.insert(held.copy()); }
                catch (RuntimeException | LinkageError error) {
                    failed = true;
                    StorageFill.LOGGER.error("Unable to return unsubmitted FTB materials to their source", error);
                }
            }
            if (!remainder.isEmpty()) {
                player.getInventory().add(remainder);
                if (!remainder.isEmpty()) player.drop(remainder, false);
            }
            held.setCount(0);
        }
    }
}

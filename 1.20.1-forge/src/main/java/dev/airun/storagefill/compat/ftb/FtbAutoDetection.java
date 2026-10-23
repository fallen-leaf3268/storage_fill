package dev.airun.storagefill.compat.ftb;

import dev.airun.storagefill.storage.StorageSource;
import dev.airun.storagefill.StorageFill;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import dev.ftb.mods.ftbquests.item.MissingItem;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class FtbAutoDetection {
    private static final Set<UUID> CHECKING = new HashSet<>();
    private FtbAutoDetection() { }

    public static boolean supports(ItemTask task) {
        var stack = task.getItemStack();
        return task.getClass() == ItemTask.class && !task.consumesResources() && !task.isOnlyFromCrafting()
            && !task.isTaskScreenOnly() && !stack.isEmpty() && !(stack.getItem() instanceof MissingItem)
            && !ItemMatchingSystem.INSTANCE.isItemFilter(stack);
    }

    static boolean valid(ServerPlayer player, TeamData data, ItemTask task) {
        if (!supports(task) || !(data.getFile() instanceof ServerQuestFile file) || file != ServerQuestFile.INSTANCE
            || file.isLoading() || file.getTask(task.id) != task || data.isLocked() || data.isCompleted(task)
            || !data.canStartTasks(task.getQuest()) || TeamData.get(player) != data
            || !player.isAlive() || player.isRemoved()) return false;
        if (!task.getQuest().getRequireSequentialTasks()) return true;
        var sequence = task.getQuest().getTasksAsList();
        int index = sequence.indexOf(task);
        return index >= 0 && (index == 0 || data.isCompleted(sequence.get(index - 1)));
    }

    public static void detect(ServerPlayer player, TeamData data, List<ItemTask> tasks, List<StorageSource> sources) {
        detect(player, data, tasks, sources, new FtbDetectionSession(Integer.MAX_VALUE));
    }

    static void detect(ServerPlayer player, TeamData data, List<ItemTask> tasks, List<StorageSource> sources,
                       FtbDetectionSession session) {
        if (!player.server.isSameThread() || !CHECKING.add(data.getTeamId())) return;
        try {
            for (ItemTask task : tasks) {
                if (!valid(player, data, task) || data.getProgress(task) >= task.getMaxProgress()) continue;
                long maximum = task.getMaxProgress();
                long count = 0;
                for (var stack : player.getInventory().items) {
                    if (!stack.isEmpty() && task.test(stack)) {
                        count += Math.min(maximum - count, stack.getCount());
                        if (count >= maximum) break;
                    }
                }
                for (StorageSource source : sources) {
                    if (count >= maximum) break;
                    try {
                        count = Math.max(count, Math.min(maximum,
                            session.count(task, source, maximum)));
                    } catch (RuntimeException | LinkageError error) {
                        StorageFill.LOGGER.error("Unable to count an accessible storage view for FTB", error);
                    }
                }
                if (count > data.getProgress(task) && valid(player, data, task)) {
                    long confirmed = count;
                    try {
                        ((ServerQuestFile) data.getFile()).withPlayerContext(player, () -> data.setProgress(task, confirmed));
                    } finally {
                        session.afterProgressWrite();
                    }
                }
            }
        } finally {
            CHECKING.remove(data.getTeamId());
        }
    }
}

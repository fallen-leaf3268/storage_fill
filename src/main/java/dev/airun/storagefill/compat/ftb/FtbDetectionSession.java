package dev.airun.storagefill.compat.ftb;

import dev.airun.storagefill.mixin.FtbItemTaskAccessor;
import dev.airun.storagefill.storage.StorageSource;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;

final class FtbDetectionSession {
    private static long progressRevision;
    private final int maximumRules;
    private final Map<Rule, Group> groups = new HashMap<>();
    private final Map<ItemTask, Rule> rules = new IdentityHashMap<>();
    private boolean wroteProgress;
    private long observedRevision;

    FtbDetectionSession(int maximumRules) {
        this.maximumRules = maximumRules;
    }

    boolean accepts(ItemTask task) {
        Rule rule = rule(task);
        if (groups.containsKey(rule)) return true;
        if (groups.size() >= maximumRules) return false;
        groups.put(rule, new Group(task));
        return true;
    }

    long count(ItemTask task, StorageSource source, long limit) {
        if (limit <= 0) return 0;
        Rule rule = rule(task);
        Group group = groups.computeIfAbsent(rule, ignored -> new Group(task));
        Read read = group.reads.computeIfAbsent(source,
            ignored -> new Read(group.strictNbt ? source.prepareStrictCount(group.prototype, group.matches)
                : source.prepareCount(group.prototype, group.matches)));
        if (!read.present || read.quantity >= read.limit && limit > read.limit) {
            read.present = false;
            read.quantity = Math.max(0, Math.min(limit, read.query.count(limit)));
            read.limit = limit;
            read.present = true;
        }
        return Math.min(limit, read.quantity);
    }

    void afterProgressWrite() {
        wroteProgress = true;
        progressRevision++;
        invalidateAfterProgress();
    }

    private void invalidateAfterProgress() {
        if (observedRevision == progressRevision) return;
        observedRevision = progressRevision;
        rules.clear();
        for (Group group : groups.values()) for (Read read : group.reads.values()) read.present = false;
    }

    boolean wroteProgress() { return wroteProgress; }

    private Rule rule(ItemTask task) {
        invalidateAfterProgress();
        return rules.computeIfAbsent(task, candidate -> {
            ItemStack prototype = candidate.getItemStack();
            FtbItemTaskAccessor access = (FtbItemTaskAccessor) candidate;
            CompoundTag tag = access.storage_fill$shouldMatchNBT() ? prototype.getTag() : null;
            boolean fuzzy = access.storage_fill$weakNBTmatch();
            if (tag == null || fuzzy && tag.isEmpty()) return new Rule(prototype.getItem(), null, false);
            return new Rule(prototype.getItem(), tag.copy(), fuzzy);
        });
    }

    private record Rule(Item item, CompoundTag tag, boolean fuzzy) { }

    private static final class Group {
        private final ItemStack prototype;
        private final Predicate<ItemStack> matches;
        private final boolean strictNbt;
        private final Map<StorageSource, Read> reads = new IdentityHashMap<>();

        private Group(ItemTask task) {
            ItemStack filter = task.getItemStack().copyWithCount(1);
            FtbItemTaskAccessor access = (FtbItemTaskAccessor) task;
            boolean nbt = access.storage_fill$shouldMatchNBT();
            boolean fuzzy = access.storage_fill$weakNBTmatch();
            prototype = nbt && filter.getTag() != null ? filter.copy() : new ItemStack(filter.getItem());
            matches = stack -> ItemMatchingSystem.INSTANCE.doesItemMatch(filter, stack, nbt, fuzzy);
            strictNbt = nbt && !fuzzy && filter.hasTag() && !ItemMatchingSystem.INSTANCE.isItemFilter(filter);
        }
    }

    private static final class Read {
        private final StorageSource.CountQuery query;
        private long limit;
        private long quantity;
        private boolean present;

        private Read(StorageSource.CountQuery query) { this.query = query; }
    }
}

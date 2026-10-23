package dev.airun.storagefill.storage;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.storage.root.FuzzyRootStorage;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.function.Predicate;

final class RefinedStorageCounting {
    private RefinedStorageCounting() { }

    static long count(FuzzyRootStorage storage, Actor actor, Item item, Predicate<ItemStack> matches, long limit) {
        var prototype = new ItemResource(item);
        var variants = storage.getFuzzy(prototype);
        long count = 0;
        for (var entry : variants) {
            if (!(entry instanceof ItemResource key) || key.item() != item || !matches.test(key.toItemStack())) continue;
            long requested = limit - count;
            count += Math.min(requested, Math.max(0, storage.extract(key, requested, Action.SIMULATE, actor)));
            if (count >= limit) break;
        }
        return count;
    }
}

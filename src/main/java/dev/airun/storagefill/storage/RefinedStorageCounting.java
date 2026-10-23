package dev.airun.storagefill.storage;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.ItemDriveWrapperStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.network.node.storage.ItemStorageWrapperStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.storage.disk.ItemStorageDisk;
import dev.airun.storagefill.StorageFill;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

final class RefinedStorageCounting {
    private static final Field STORAGE_PARENT = parentField(ItemStorageWrapperStorageDisk.class);
    private static final Field DRIVE_PARENT = parentField(ItemDriveWrapperStorageDisk.class);
    private static final AtomicBoolean STORAGE_READ_WARNING = new AtomicBoolean();
    private static final AtomicBoolean DRIVE_READ_WARNING = new AtomicBoolean();

    private RefinedStorageCounting() { }

    static long count(INetwork network, Item item, Predicate<ItemStack> matches, long limit) {
        long count = 0;
        Set<ItemStorageDisk> counted = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<IStorage<ItemStack>> unknown = null;
        for (IStorage<ItemStack> storage : network.getItemStorageCache().getStorages()) {
            if (storage.getAccessType() == AccessType.INSERT) continue;
            ItemStorageDisk disk = nativeDisk(storage);
            if (disk == null) {
                if (unknown == null) unknown = Collections.newSetFromMap(new IdentityHashMap<>());
                unknown.add(storage);
                continue;
            }
            if (!counted.add(disk)) continue;
            for (ItemStack stack : disk.getRawStacks().get(item)) {
                if (stack.isEmpty() || !stack.is(item)) continue;
                int amount = stack.getCount();
                if (!matches.test(stack.copyWithCount(1))) continue;
                count += Math.min(limit - count, amount);
                if (count >= limit) return count;
            }
        }
        if (unknown == null) return count;
        Set<IStorage<ItemStack>> simulated = unknown;
        for (var entry : network.getItemStorageCache().getList().getStacks(new ItemStack(item))) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty() || !stack.is(item)) continue;
            ItemStack prototype = stack.copyWithCount(1);
            if (!matches.test(prototype.copy())) continue;
            int requested = (int) Math.min(limit - count, stack.getCount());
            ItemStack available = network.extractItem(prototype, requested, IComparer.COMPARE_NBT,
                Action.SIMULATE, simulated::contains);
            if (!available.isEmpty() && ItemStack.isSameItemSameTags(stack, available)) {
                count += Math.min(requested, available.getCount());
                if (count >= limit) return count;
            }
        }
        return count;
    }

    private static ItemStorageDisk nativeDisk(IStorage<ItemStack> storage) {
        if (storage.getClass() == ItemStorageDisk.class) return (ItemStorageDisk) storage;
        Field parent = storage.getClass() == ItemStorageWrapperStorageDisk.class ? STORAGE_PARENT
            : storage.getClass() == ItemDriveWrapperStorageDisk.class ? DRIVE_PARENT : null;
        if (parent == null) return null;
        try {
            Object disk = parent.get(storage);
            return disk != null && disk.getClass() == ItemStorageDisk.class ? (ItemStorageDisk) disk : null;
        } catch (IllegalAccessException | RuntimeException error) {
            AtomicBoolean warning = storage.getClass() == ItemStorageWrapperStorageDisk.class
                ? STORAGE_READ_WARNING : DRIVE_READ_WARNING;
            if (warning.compareAndSet(false, true)) {
                StorageFill.LOGGER.warn("Unable to read native RS disk parent for {}; using simulated counting",
                    storage.getClass().getName(), error);
            }
            return null;
        }
    }

    private static Field parentField(Class<?> wrapper) {
        try {
            Field field = wrapper.getDeclaredField("parent");
            if (field.trySetAccessible()) return field;
            StorageFill.LOGGER.warn("Unable to access native RS disk parent for {}; using simulated counting", wrapper.getName());
            return null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            StorageFill.LOGGER.warn("Unable to resolve native RS disk parent for {}; using simulated counting",
                wrapper.getName(), error);
            return null;
        }
    }
}

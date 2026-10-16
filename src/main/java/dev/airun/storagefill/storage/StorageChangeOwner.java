package dev.airun.storagefill.storage;

import java.util.function.Supplier;

public interface StorageChangeOwner {
    void storageFillSetChangeOwner(Supplier<Object> owner);
    Object storageFillChangeIdentity();
}

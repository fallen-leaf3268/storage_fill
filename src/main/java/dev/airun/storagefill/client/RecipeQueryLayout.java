package dev.airun.storagefill.client;

import java.util.function.BooleanSupplier;

public interface RecipeQueryLayout {
    void storageFillReserveQueryButtons(BooleanSupplier visible);
    boolean storageFillQueryButtonVisible();
    void storageFillSetTransferButtonVisible(boolean visible);
    boolean storageFillTransferButtonVisible();
}

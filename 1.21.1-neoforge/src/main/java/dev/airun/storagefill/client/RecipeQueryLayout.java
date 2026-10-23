package dev.airun.storagefill.client;

import net.minecraft.client.renderer.Rect2i;

import java.util.function.BooleanSupplier;

public interface RecipeQueryLayout {
    Rect2i storageFillBaseButtonArea();
    void storageFillReserveQueryButtons(BooleanSupplier visible);
    boolean storageFillQueryButtonVisible();
    void storageFillSetTransferButtonVisible(boolean visible);
    boolean storageFillTransferButtonVisible();
}

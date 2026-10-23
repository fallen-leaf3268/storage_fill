package dev.airun.storagefill;

import net.minecraft.network.chat.Component;

public record TransferResult(boolean success, Component message) {
    public static TransferResult ok() {
        return new TransferResult(true, Component.empty());
    }

    public static TransferResult failure(Component message) {
        return new TransferResult(false, message);
    }
}

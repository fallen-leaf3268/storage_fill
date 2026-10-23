package dev.airun.storagefill.mixin;

import dev.airun.storagefill.storage.StorageChanges;
import dev.airun.storagefill.storage.StorageSource;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.IInventoryPartHandler;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import org.apache.commons.lang3.function.TriFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiConsumer;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler", remap = false)
public abstract class SophisticatedBackpacksInsertMixin {
    @Shadow @Final protected IStorageWrapper storageWrapper;

    @Redirect(method = "insertItemInternal",
        at = @At(value = "INVOKE", target = "Lnet/p3pp3rf1y/sophisticatedcore/inventory/IInventoryPartHandler;insertItem(ILnet/minecraft/world/item/ItemStack;ZLorg/apache/commons/lang3/function/TriFunction;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack storage_fill$insert(IInventoryPartHandler part, int slot, ItemStack stack, boolean simulate,
                                          TriFunction<Integer, ItemStack, Boolean, ItemStack> inserter) {
        int requested = stack.getCount();
        var item = stack.getItem();
        ItemStack remainder = part.insertItem(slot, stack, simulate, inserter);
        if (!simulate && storageWrapper instanceof IBackpackWrapper && requested > remainder.getCount()) {
            StorageChanges.record(StorageSource.SOPHISTICATED_BACKPACKS, this, item);
        }
        return remainder;
    }

    @Redirect(method = "setStackInSlot",
        at = @At(value = "INVOKE", target = "Lnet/p3pp3rf1y/sophisticatedcore/inventory/IInventoryPartHandler;setStackInSlot(ILnet/minecraft/world/item/ItemStack;Ljava/util/function/BiConsumer;)V"))
    private void storage_fill$setStack(IInventoryPartHandler part, int slot, ItemStack stack,
                                       BiConsumer<Integer, ItemStack> setter) {
        if (!(storageWrapper instanceof IBackpackWrapper)) {
            part.setStackInSlot(slot, stack, setter);
            return;
        }
        InventoryHandler handler = (InventoryHandler) (Object) this;
        ItemStack previous = handler.getStackInSlot(slot).copy();
        part.setStackInSlot(slot, stack, setter);
        ItemStack current = handler.getStackInSlot(slot);
        if (!current.isEmpty() && (!ItemStack.isSameItemSameComponents(previous, current)
            || current.getCount() > previous.getCount())) {
            StorageChanges.record(StorageSource.SOPHISTICATED_BACKPACKS, this, current.getItem());
        }
    }
}

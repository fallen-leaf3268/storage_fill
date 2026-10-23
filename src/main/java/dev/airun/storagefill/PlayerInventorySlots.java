package dev.airun.storagefill;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.SlotItemHandler;

final class PlayerInventorySlots {
    private static final boolean LEGENDARY = ModList.get().isLoaded("legendaryinventory");

    private PlayerInventorySlots() { }

    record Physical(Object owner, int index) {
        boolean hidden() { return index < 0; }
    }

    static Physical inventory(ServerPlayer player, AbstractContainerMenu menu, Slot slot) {
        if (LEGENDARY && Legendary.isSlot(slot)) return Legendary.resolve(player, menu, slot);
        return slot.container == player.getInventory() ? ordinary(slot) : null;
    }

    static Physical physical(ServerPlayer player, AbstractContainerMenu menu, Slot slot) {
        if (LEGENDARY && Legendary.isSlot(slot)) return Legendary.resolve(player, menu, slot);
        return ordinary(slot);
    }

    private static Physical ordinary(Slot slot) {
        Object owner = slot instanceof SlotItemHandler handler ? handler.getItemHandler() : slot.container;
        return new Physical(owner, slot.getContainerSlot());
    }

    private static final class Legendary {
        private static boolean isSlot(Slot slot) {
            return slot instanceof com.krampus.legendaryinventory.menu.LISlot;
        }

        private static Physical resolve(ServerPlayer player, AbstractContainerMenu menu, Slot slot) {
            if (slot.getClass() != com.krampus.legendaryinventory.menu.LISlot.class) return null;
            var scrolling = (com.krampus.legendaryinventory.menu.LISlot) slot;
            var context = scrolling.context();
            if (context == null || context.getOwner() != player || context.getMenu() != menu
                    || com.krampus.legendaryinventory.menu.ScrollRegistry.get(menu) != context
                    || context.getBacking() == null || scrolling.window() < 0
                    || scrolling.window() >= com.krampus.legendaryinventory.menu.ScrollContext.WINDOW) return null;
            int index = scrolling.backingIndex();
            if (index == -1) return new Physical(null, -1);
            if (index < 0 || index >= context.getBacking().getSlots()) return null;
            if (index < com.krampus.legendaryinventory.inventory.CombinedInventoryHandler.MAIN_COUNT) {
                return new Physical(player.getInventory(),
                    com.krampus.legendaryinventory.inventory.CombinedInventoryHandler.MAIN_OFFSET + index);
            }
            var extended = com.krampus.legendaryinventory.api.LegendaryInventoryApi.getExtendedInventory(player);
            int physical = index - com.krampus.legendaryinventory.inventory.CombinedInventoryHandler.MAIN_COUNT;
            return extended != null && physical < extended.getSlots() ? new Physical(extended, physical) : null;
        }
    }
}

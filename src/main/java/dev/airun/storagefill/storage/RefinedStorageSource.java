package dev.airun.storagefill.storage;

import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.INetworkNodeGraphEntry;
import com.refinedmods.refinedstorage.api.network.IWirelessTransmitter;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import com.refinedmods.refinedstorage.item.NetworkItem;
import com.refinedmods.refinedstorage.item.WirelessGridItem;
import dev.airun.storagefill.StorageFill;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.ModList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class RefinedStorageSource implements StorageSource, NativeRecipeQuerySource {
    public int sourceId() { return REFINED_STORAGE; }
    private final ServerPlayer player;
    private final INetwork network;
    private final List<ItemStack> terminals = new ArrayList<>();
    private final List<ItemStack> borrowed = new ArrayList<>();
    private ItemStack openedTerminal = ItemStack.EMPTY;
    private boolean energyFailed;

    private RefinedStorageSource(ServerPlayer player, INetwork network) {
        this.player = player;
        this.network = network;
    }

    static void find(ServerPlayer player, List<StorageSource> sources) {
        find(player, sources, Permission.EXTRACT, false);
    }

    static void findForInsert(ServerPlayer player, List<StorageSource> sources) {
        find(player, sources, Permission.INSERT, true);
    }

    private static void find(ServerPlayer player, List<StorageSource> sources, Permission permission, boolean inserting) {
        Map<INetwork, RefinedStorageSource> networks = new LinkedHashMap<>();
        for (ItemStack terminal : carriedStacks(player)) {
            NetworkItem item = terminalItem(terminal);
            if (item != null) {
                item.applyNetwork(player.server, terminal, network -> {
                    RefinedStorageSource source = networks.computeIfAbsent(network,
                        key -> new RefinedStorageSource(player, key));
                    source.terminals.add(terminal);
                }, ignored -> {});
            }
        }
        for (RefinedStorageSource source : networks.values()) {
            if (source.available(permission) && !source.selectTerminal(inserting ? source.insertCost() : source.extractCost()).isEmpty()) {
                sources.add(source);
            }
        }
    }

    private static List<ItemStack> carriedStacks(ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) stacks.add(stack);
        }
        if (ModList.get().isLoaded("curios")) CuriosAccess.addEquipped(player, stacks);
        return stacks;
    }

    private static final class CuriosAccess {
        private static void addEquipped(ServerPlayer player, List<ItemStack> stacks) {
            top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).ifPresent(inventory -> {
                var equipped = inventory.getEquippedCurios();
                for (int slot = 0; slot < equipped.getSlots(); slot++) {
                    ItemStack stack = equipped.getStackInSlot(slot);
                    if (!stack.isEmpty()) stacks.add(stack);
                }
            });
        }
    }

    private static NetworkItem terminalItem(ItemStack terminal) {
        if (!(terminal.getItem() instanceof NetworkItem item)) return null;
        if (item instanceof WirelessGridItem) return item;
        return ModList.get().isLoaded("refinedstorageaddons") ? RefinedStorageAddonsAccess.terminalItem(item) : null;
    }

    private static final class RefinedStorageAddonsAccess {
        private static NetworkItem terminalItem(NetworkItem item) {
            return item instanceof com.refinedmods.refinedstorageaddons.item.WirelessCraftingGridItem ? item : null;
        }

        private static boolean usesEnergy(ItemStack terminal) {
            return com.refinedmods.refinedstorageaddons.RSAddons.SERVER_CONFIG.getWirelessCraftingGrid().getUseEnergy()
                && ((com.refinedmods.refinedstorageaddons.item.WirelessCraftingGridItem) terminal.getItem()).getType()
                    != com.refinedmods.refinedstorageaddons.item.WirelessCraftingGridItem.Type.CREATIVE;
        }

        private static int openCost() {
            return Math.max(0, com.refinedmods.refinedstorageaddons.RSAddons.SERVER_CONFIG.getWirelessCraftingGrid().getOpenUsage());
        }
    }

    private boolean available(Permission permission) {
        if (!network.canRun() || !network.getLevel().isLoaded(network.getPosition())
            || !network.getSecurityManager().hasPermission(Permission.MODIFY, player)
            || !network.getSecurityManager().hasPermission(permission, player)) {
            return false;
        }
        for (INetworkNodeGraphEntry entry : network.getNodeGraph().all()) {
            INetworkNode node = entry.getNode();
            if (node instanceof IWirelessTransmitter transmitter && node.isActive()
                && transmitter.getDimension().equals(player.level().dimension())
                && node.getLevel().isLoaded(node.getPos())) {
                double dx = transmitter.getOrigin().getX() - player.getX();
                double dy = transmitter.getOrigin().getY() - player.getY();
                double dz = transmitter.getOrigin().getZ() - player.getZ();
                double range = transmitter.getRange();
                if (dx * dx + dy * dy + dz * dz < range * range) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean carried(ItemStack terminal) {
        for (ItemStack stack : carriedStacks(player)) {
            if (stack == terminal) {
                return true;
            }
        }
        return false;
    }

    private void refreshTerminals() {
        terminals.clear();
        for (ItemStack stack : carriedStacks(player)) {
            NetworkItem item = terminalItem(stack);
            if (item != null) {
                item.applyNetwork(player.server, stack, linked -> {
                    if (linked == network) terminals.add(stack);
                }, ignored -> {});
            }
        }
    }

    private boolean usesEnergy(ItemStack terminal) {
        if (terminal.getItem() instanceof WirelessGridItem item) {
            return RS.SERVER_CONFIG.getWirelessGrid().getUseEnergy() && item.getType() != WirelessGridItem.Type.CREATIVE;
        }
        return ModList.get().isLoaded("refinedstorageaddons") && RefinedStorageAddonsAccess.usesEnergy(terminal);
    }

    private int openCost(ItemStack terminal) {
        if (terminal.getItem() instanceof WirelessGridItem) {
            return Math.max(0, RS.SERVER_CONFIG.getWirelessGrid().getOpenUsage());
        }
        return ModList.get().isLoaded("refinedstorageaddons") ? RefinedStorageAddonsAccess.openCost() : 0;
    }

    private int extractCost() {
        return Math.max(0, RS.SERVER_CONFIG.getWirelessGrid().getExtractUsage());
    }

    private int insertCost() {
        return Math.max(0, RS.SERVER_CONFIG.getWirelessGrid().getInsertUsage());
    }

    private long cost(ItemStack terminal, int operationCost) {
        return (long) operationCost + (terminal == openedTerminal ? 0 : openCost(terminal));
    }

    private ItemStack selectTerminal(int operationCost) {
        if (energyFailed) {
            return ItemStack.EMPTY;
        }
        refreshTerminals();
        for (ItemStack terminal : terminals) {
            if (!carried(terminal)) {
                continue;
            }
            if (!usesEnergy(terminal)) {
                return terminal;
            }
            IEnergyStorage energy = terminal.getCapability(ForgeCapabilities.ENERGY).orElse(null);
            long cost = cost(terminal, operationCost);
            if (energy != null && cost <= Integer.MAX_VALUE && energy.getEnergyStored() > 0
                && energy.extractEnergy((int) cost, true) == cost) {
                return terminal;
            }
        }
        return ItemStack.EMPTY;
    }

    private void charge(ItemStack terminal, int operationCost) {
        try {
            if (usesEnergy(terminal)) {
                IEnergyStorage energy = terminal.getCapability(ForgeCapabilities.ENERGY).orElse(null);
                int required = (int) cost(terminal, operationCost);
                if (energy == null || energy.extractEnergy(required, false) != required) {
                    energyFailed = true;
                    StorageFill.LOGGER.error("Wireless terminal energy changed during storage transfer");
                }
            }
            openedTerminal = terminal;
            player.getInventory().setChanged();
        } catch (RuntimeException | LinkageError error) {
            energyFailed = true;
            StorageFill.LOGGER.error("Unable to charge wireless terminal after storage transfer", error);
        }
    }

    @Override
    public Object identity() { return network; }

    @Override
    public Object queryKey(ItemStack prototype) {
        return new com.refinedmods.refinedstorage.util.ItemStackKey(prototype.copyWithCount(1));
    }

    @Override
    public Scan scanQuery(Set<Item> wanted, Set<Object> seen, int position, int scanLimit) {
        if (wanted.isEmpty() || !available(Permission.EXTRACT) || selectTerminal(extractCost()).isEmpty())
            return new Scan(ItemStack.EMPTY, 0, 0, true);
        int scanned = 0;
        var list = network.getItemStorageCache().getList();
        for (Item item : wanted) {
            if (scanned >= scanLimit) return new Scan(ItemStack.EMPTY, 0, scanned, false);
            scanned++;
            for (StackListEntry<ItemStack> entry : list.getStacks(new ItemStack(item))) {
                if (scanned >= scanLimit) return new Scan(ItemStack.EMPTY, 0, scanned, false);
                scanned++;
                ItemStack stack = entry.getStack();
                if (!stack.isEmpty() && !seen.contains(new com.refinedmods.refinedstorage.util.ItemStackKey(stack)))
                    return new Scan(stack.copyWithCount(1), 0, scanned, false);
            }
        }
        return new Scan(ItemStack.EMPTY, 0, scanned, true);
    }

    @Override
    public long countMatching(Item item, java.util.function.Predicate<ItemStack> matches, long limit) {
        if (item == null || limit <= 0 || !available(Permission.EXTRACT)
            || selectTerminal(extractCost()).isEmpty()) return 0;
        return RefinedStorageCounting.count(network, item, matches, limit);
    }

    @Override
    public AutoCloseable watch(Consumer<Item> changed) {
        var cache = network.getItemStorageCache();
        IStorageCacheListener<ItemStack> listener = new IStorageCacheListener<>() {
            public void onAttached() { changed.accept(null); }
            public void onInvalidated() { changed.accept(null); }
            public void onChanged(StackListResult<ItemStack> result) {
                if (result != null && !result.getStack().isEmpty()) changed.accept(result.getStack().getItem());
            }
            public void onChangedBulk(List<StackListResult<ItemStack>> results) {
                for (StackListResult<ItemStack> result : results) onChanged(result);
            }
        };
        cache.addListener(listener);
        return () -> cache.removeListener(listener);
    }

    @Override
    public List<ItemStack> snapshot(Set<Item> wanted) {
        List<ItemStack> result = new ArrayList<>();
        if (!available(Permission.EXTRACT) || selectTerminal(extractCost()).isEmpty()) {
            return result;
        }
        var index = network.getItemStorageCache().getList();
        for (Item item : wanted) {
            for (StackListEntry<ItemStack> entry : index.getStacks(new ItemStack(item))) {
                ItemStack stack = entry.getStack();
                if (!stack.isEmpty()) {
                    result.add(stack.copyWithCount(Math.min(StorageSources.MAX_COUNT, stack.getCount())));
                }
            }
        }
        return result;
    }

    @Override
    public boolean visitPreview(java.util.function.Predicate<ItemStack> visitor) {
        if (!available(Permission.EXTRACT) || selectTerminal(extractCost()).isEmpty()) return true;
        for (StackListEntry<ItemStack> entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (!stack.isEmpty() && !visitor.test(stack.copyWithCount(Math.min(StorageSources.MAX_COUNT, stack.getCount())))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean visitPreview(Set<Item> wanted, java.util.function.Predicate<ItemStack> visitor) {
        if (wanted.isEmpty() || !available(Permission.EXTRACT) || selectTerminal(extractCost()).isEmpty()) return true;
        var list = network.getItemStorageCache().getList();
        for (Item item : wanted) {
            for (StackListEntry<ItemStack> entry : list.getStacks(new ItemStack(item))) {
                ItemStack stack = entry.getStack();
                if (!stack.isEmpty() && !visitor.test(stack.copyWithCount(Math.min(StorageSources.MAX_COUNT, stack.getCount())))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
        if (prototype.isEmpty() || amount <= 0 || !available(Permission.EXTRACT)) {
            return ItemStack.EMPTY;
        }
        ItemStack terminal = selectTerminal(extractCost());
        if (terminal.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!simulate) {
            network.getItemStorageTracker().changed(player, prototype.copy());
        }
        ItemStack result = network.extractItem(prototype, Math.min(StorageSources.MAX_COUNT, amount),
            IComparer.COMPARE_NBT, simulate ? Action.SIMULATE : Action.PERFORM);
        if (!simulate && !result.isEmpty()) {
            charge(terminal, extractCost());
            borrowed.add(result.copy());
        }
        return result;
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        if (stack.isEmpty() || !available(Permission.INSERT)) {
            return stack;
        }
        int refundable = 0;
        for (ItemStack debt : borrowed) {
            if (ItemStack.isSameItemSameTags(stack, debt)) {
                refundable += debt.getCount();
            }
        }
        boolean returning = refundable >= stack.getCount();
        int usage = insertCost();
        ItemStack terminal = selectTerminal(usage);
        if (!returning && terminal.isEmpty()) {
            return stack;
        }
        network.getItemStorageTracker().changed(player, stack.copy());
        ItemStack remainder = network.insertItem(stack, stack.getCount(), Action.PERFORM);
        int inserted = stack.getCount() - remainder.getCount();
        if (inserted > 0) {
            if (!returning) {
                charge(terminal, usage);
            }
            for (ItemStack debt : borrowed) {
                if (ItemStack.isSameItemSameTags(stack, debt)) {
                    int consumed = Math.min(debt.getCount(), inserted);
                    debt.shrink(consumed);
                    inserted -= consumed;
                }
            }
        }
        return remainder;
    }

    @Override
    public void finish(int extracted) {
        borrowed.clear();
    }
}

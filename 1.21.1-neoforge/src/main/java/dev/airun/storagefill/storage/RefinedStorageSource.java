package dev.airun.storagefill.storage;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageListener;
import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.security.SecurityHelper;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.api.storage.root.FuzzyRootStorage;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemPlayerValidator;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemTargetBlockEntity;
import com.refinedmods.refinedstorage.common.content.DataComponents;
import com.refinedmods.refinedstorage.common.grid.WirelessGridItem;
import com.refinedmods.refinedstorage.common.security.BuiltinPermission;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import dev.airun.storagefill.StorageFill;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class RefinedStorageSource implements StorageSource, NativeRecipeQuerySource {
    private final ServerPlayer player;
    private final Network network;
    private final StorageNetworkComponent storage;
    private final FuzzyRootStorage indexedStorage;
    private final Map<ItemResource, Long> borrowed = new LinkedHashMap<>();
    private ItemStack openedTerminal = ItemStack.EMPTY;
    private boolean energyFailed;

    private RefinedStorageSource(ServerPlayer player, Network network) {
        this.player = player;
        this.network = network;
        this.storage = network.getComponent(StorageNetworkComponent.class);
        if (!(storage instanceof FuzzyRootStorage indexed)) throw new IllegalStateException("RS storage component lacks indexed item variants");
        this.indexedStorage = indexed;
    }

    static void find(ServerPlayer player, List<StorageSource> sources) {
        find(player, sources, BuiltinPermission.EXTRACT, false);
    }

    static void findForInsert(ServerPlayer player, List<StorageSource> sources) {
        find(player, sources, BuiltinPermission.INSERT, true);
    }

    private static void find(ServerPlayer player, List<StorageSource> sources, BuiltinPermission permission, boolean inserting) {
        Map<Network, RefinedStorageSource> networks = new LinkedHashMap<>();
        for (ItemStack terminal : carriedStacks(player)) {
            if (!isTerminal(terminal)) continue;
            Network network = linkedNetwork(player, terminal);
            if (network != null) networks.computeIfAbsent(network, key -> new RefinedStorageSource(player, key));
        }
        for (RefinedStorageSource source : networks.values()) {
            if (source.available(permission) && !source.selectTerminal(inserting ? source.insertCost() : source.extractCost()).isEmpty())
                sources.add(source);
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
                for (var entry : inventory.getCurios().entrySet()) {
                    var equipped = entry.getValue().getStacks();
                    for (int slot = 0; slot < equipped.getSlots(); slot++) {
                        if (!inventory.isSlotActive(entry.getKey(), slot)) continue;
                        ItemStack stack = equipped.getStackInSlot(slot);
                        if (!stack.isEmpty()) stacks.add(stack);
                    }
                }
            });
        }
    }

    private static boolean isTerminal(ItemStack stack) {
        return stack.getItem() instanceof WirelessGridItem
            || ModList.get().isLoaded("refinedstorage_quartz_arsenal") && QuartzArsenalAccess.isTerminal(stack);
    }

    private static final class QuartzArsenalAccess {
        private static boolean isTerminal(ItemStack stack) {
            return stack.getItem() instanceof com.refinedmods.refinedstorage.quartzarsenal.common.wirelesscraftinggrid.WirelessCraftingGridItem;
        }

        private static boolean isCreative(ItemStack stack) {
            return stack.is(com.refinedmods.refinedstorage.quartzarsenal.common.Items.INSTANCE.getCreativeWirelessCraftingGrid());
        }

        private static long openCost() {
            return Math.max(0, com.refinedmods.refinedstorage.quartzarsenal.common.Platform.getConfig()
                .getWirelessCraftingGrid().getOpenEnergyUsage());
        }
    }

    private static Network linkedNetwork(ServerPlayer player, ItemStack terminal) {
        var location = terminal.get(DataComponents.INSTANCE.getNetworkLocation());
        if (location == null) return null;
        var level = player.server.getLevel(location.dimension());
        if (level == null || !level.isLoaded(location.pos())) return null;
        if (!(level.getBlockEntity(location.pos()) instanceof NetworkItemTargetBlockEntity target)) return null;
        Network network = target.getNetworkForItem();
        if (network == null) return null;
        var coordinates = new NetworkItemPlayerValidator.PlayerCoordinates(player.level().dimension(), player.position());
        return network.getComponent(GraphNetworkComponent.class).getContainers(NetworkItemPlayerValidator.class)
            .stream().anyMatch(validator -> validator.isValid(coordinates)) ? network : null;
    }

    private boolean available(BuiltinPermission permission) {
        return (!RefinedStorageApi.INSTANCE.isEnergyRequired() || network.getComponent(EnergyNetworkComponent.class).getStored() > 0)
            && SecurityHelper.isAllowed(player, BuiltinPermission.OPEN, network)
            && SecurityHelper.isAllowed(player, permission, network)
            && carriedStacks(player).stream().anyMatch(terminal -> isTerminal(terminal) && linkedNetwork(player, terminal) == network);
    }

    private boolean usesEnergy(ItemStack terminal) {
        if (!RefinedStorageApi.INSTANCE.isEnergyRequired()) return false;
        if (terminal.getItem() instanceof WirelessGridItem)
            return !terminal.is(com.refinedmods.refinedstorage.common.content.Items.INSTANCE.getCreativeWirelessGrid());
        return !QuartzArsenalAccess.isCreative(terminal);
    }

    private long openCost(ItemStack terminal) {
        return terminal.getItem() instanceof WirelessGridItem
            ? Math.max(0, Platform.INSTANCE.getConfig().getWirelessGrid().getOpenEnergyUsage()) : QuartzArsenalAccess.openCost();
    }

    private long extractCost() { return Math.max(0, Platform.INSTANCE.getConfig().getWirelessGrid().getExtractEnergyUsage()); }

    private long insertCost() { return Math.max(0, Platform.INSTANCE.getConfig().getWirelessGrid().getInsertEnergyUsage()); }

    private long cost(ItemStack terminal, long operationCost) {
        long opening = terminal == openedTerminal ? 0 : openCost(terminal);
        return Long.MAX_VALUE - operationCost < opening ? Long.MAX_VALUE : operationCost + opening;
    }

    private ItemStack selectTerminal(long operationCost) {
        if (energyFailed) return ItemStack.EMPTY;
        for (ItemStack terminal : carriedStacks(player)) {
            if (!isTerminal(terminal) || linkedNetwork(player, terminal) != network) continue;
            if (!usesEnergy(terminal)) return terminal;
            var energy = RefinedStorageApi.INSTANCE.getEnergyStorage(terminal).orElse(null);
            long required = cost(terminal, operationCost);
            if (energy != null && energy.getStored() > 0 && energy.extract(required, Action.SIMULATE) == required) return terminal;
        }
        return ItemStack.EMPTY;
    }

    private void charge(ItemStack terminal, long operationCost) {
        try {
            if (usesEnergy(terminal)) {
                var energy = RefinedStorageApi.INSTANCE.getEnergyStorage(terminal).orElse(null);
                long required = cost(terminal, operationCost);
                if (energy == null || energy.extract(required, Action.EXECUTE) != required) {
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

    private Collection<ResourceKey> variants(Item item) {
        return indexedStorage.getFuzzy(new ItemResource(item));
    }

    private long availableAmount(ItemResource key, long limit) {
        return Math.min(limit, Math.max(0, storage.extract(key, limit, Action.SIMULATE, new PlayerActor(player))));
    }

    @Override
    public int sourceId() { return REFINED_STORAGE; }

    @Override
    public Object identity() { return storage; }

    @Override
    public Object queryKey(ItemStack prototype) { return ItemResource.ofItemStack(prototype); }

    @Override
    public Scan scanQuery(Set<Item> wanted, Set<Object> seen, int position, int scanLimit) {
        if (wanted.isEmpty() || !available(BuiltinPermission.EXTRACT) || selectTerminal(extractCost()).isEmpty())
            return new Scan(ItemStack.EMPTY, 0, 0, true);
        int scanned = 0;
        for (Item item : wanted) {
            if (scanned >= scanLimit) return new Scan(ItemStack.EMPTY, 0, scanned, false);
            scanned++;
            for (ResourceKey entry : variants(item)) {
                if (scanned >= scanLimit) return new Scan(ItemStack.EMPTY, 0, scanned, false);
                scanned++;
                if (entry instanceof ItemResource key && key.item() == item && !seen.contains(key) && storage.get(key) > 0)
                    return new Scan(key.toItemStack(), 0, scanned, false);
            }
        }
        return new Scan(ItemStack.EMPTY, 0, scanned, true);
    }

    @Override
    public long countMatching(Item item, java.util.function.Predicate<ItemStack> matches, long limit) {
        if (item == null || limit <= 0 || !available(BuiltinPermission.EXTRACT) || selectTerminal(extractCost()).isEmpty()) return 0;
        return RefinedStorageCounting.count(indexedStorage, new PlayerActor(player), item, matches, limit);
    }

    @Override
    public AutoCloseable watch(Consumer<Item> changed) {
        RootStorageListener listener = change -> {
            if (change.resource() instanceof ItemResource key) changed.accept(key.item());
        };
        storage.addListener(listener);
        return () -> storage.removeListener(listener);
    }

    @Override
    public List<ItemStack> snapshot(Set<Item> wanted) {
        List<ItemStack> result = new ArrayList<>();
        visitPreview(wanted, stack -> { result.add(stack); return true; });
        return result;
    }

    @Override
    public boolean visitPreview(java.util.function.Predicate<ItemStack> visitor) {
        if (!available(BuiltinPermission.EXTRACT) || selectTerminal(extractCost()).isEmpty()) return true;
        for (var entry : storage.getAll()) {
            if (!(entry.resource() instanceof ItemResource key) || entry.amount() <= 0) continue;
            long count = availableAmount(key, Math.min(StorageSources.MAX_COUNT, entry.amount()));
            if (count > 0 && !visitor.test(key.toItemStack(count))) return false;
        }
        return true;
    }

    @Override
    public boolean visitPreview(Set<Item> wanted, java.util.function.Predicate<ItemStack> visitor) {
        if (wanted.isEmpty() || !available(BuiltinPermission.EXTRACT) || selectTerminal(extractCost()).isEmpty()) return true;
        for (Item item : wanted) {
            for (ResourceKey entry : variants(item)) {
                if (!(entry instanceof ItemResource key) || key.item() != item) continue;
                long count = availableAmount(key, StorageSources.MAX_COUNT);
                if (count > 0 && !visitor.test(key.toItemStack(count))) return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
        if (prototype.isEmpty() || amount <= 0 || !available(BuiltinPermission.EXTRACT)) return ItemStack.EMPTY;
        ItemStack terminal = selectTerminal(extractCost());
        if (terminal.isEmpty()) return ItemStack.EMPTY;
        ItemResource key = ItemResource.ofItemStack(prototype);
        long extracted = storage.extract(key, Math.min(StorageSources.MAX_COUNT, amount),
            simulate ? Action.SIMULATE : Action.EXECUTE, new PlayerActor(player));
        if (extracted <= 0) return ItemStack.EMPTY;
        if (!simulate) {
            charge(terminal, extractCost());
            borrowed.merge(key, extracted, Long::sum);
        }
        return key.toItemStack(extracted);
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        if (stack.isEmpty() || !available(BuiltinPermission.INSERT)) return stack;
        ItemResource key = ItemResource.ofItemStack(stack);
        long debt = borrowed.getOrDefault(key, 0L);
        boolean returning = debt >= stack.getCount();
        ItemStack terminal = selectTerminal(insertCost());
        if (!returning && terminal.isEmpty()) return stack;
        long inserted = storage.insert(key, stack.getCount(), Action.EXECUTE, new PlayerActor(player));
        if (inserted <= 0) return stack;
        if (!returning) charge(terminal, insertCost());
        if (debt > 0) {
            long remaining = Math.max(0, debt - inserted);
            if (remaining == 0) borrowed.remove(key);
            else borrowed.put(key, remaining);
        }
        return key.toItemStack(Math.max(0, stack.getCount() - inserted));
    }

    @Override
    public void finish(int extracted) { borrowed.clear(); }
}

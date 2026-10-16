package dev.airun.storagefill.storage;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageHelper;
import appeng.helpers.WirelessTerminalMenuHost;
import appeng.items.tools.powered.WirelessTerminalItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AppliedEnergisticsSource implements StorageSource, NativeRecipeQuerySource {
    private final ServerPlayer player;
    private final IGrid grid;
    private final List<Terminal> terminals = new ArrayList<>();
    private final Map<AEItemKey, Long> borrowed = new LinkedHashMap<>();

    private AppliedEnergisticsSource(ServerPlayer player, IGrid grid) {
        this.player = player;
        this.grid = grid;
    }

    static void find(ServerPlayer player, List<StorageSource> sources) {
        Map<IGrid, AppliedEnergisticsSource> grids = new IdentityHashMap<>();
        List<AppliedEnergisticsSource> ordered = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof WirelessTerminalItem item)) continue;
            IGrid grid = linkedGrid(player, item, stack);
            if (grid == null) continue;
            AppliedEnergisticsSource source = grids.get(grid);
            if (source == null) {
                source = new AppliedEnergisticsSource(player, grid);
                grids.put(grid, source);
                ordered.add(source);
            }
            source.terminals.add(new Terminal(slot, stack, item,
                new WirelessTerminalMenuHost(player, slot, stack, (ignoredPlayer, ignoredMenu) -> {})));
        }
        for (AppliedEnergisticsSource source : ordered) {
            if (source.select(true) != null) sources.add(source);
        }
    }

    private static IGrid linkedGrid(ServerPlayer player, WirelessTerminalItem item, ItemStack stack) {
        var linked = item.getLinkedPosition(stack);
        if (linked == null) return null;
        var level = player.server.getLevel(linked.dimension());
        if (level == null || !level.hasChunkAt(linked.pos())) return null;
        return item.getLinkedGrid(stack, player.level(), null);
    }

    private Terminal select(boolean requirePower) {
        return select(requirePower, Set.of());
    }

    private Terminal select(boolean requirePower, Set<ItemStack> excluded) {
        if (!grid.getEnergyService().isNetworkPowered()) return null;
        refreshTerminals();
        Terminal best = null;
        double bestPower = -1;
        for (Terminal terminal : terminals) {
            if (excluded.contains(terminal.stack()) || player.getInventory().getItem(terminal.slot()) != terminal.stack()
                || linkedGrid(player, terminal.item(), terminal.stack()) != grid) continue;
            var node = terminal.host().getActionableNode();
            if (node == null || !node.isActive()) continue;
            double power = terminal.item().getAECurrentPower(terminal.stack());
            if ((!requirePower || terminal.item().hasPower(player, 0.5, terminal.stack())) && power > bestPower) {
                best = terminal;
                bestPower = power;
            }
        }
        return best;
    }

    private double extractPower(double requested, Actionable mode, PowerMultiplier multiplier) {
        Set<ItemStack> used = Collections.newSetFromMap(new IdentityHashMap<>());
        double extracted = 0;
        while (extracted < requested) {
            Terminal terminal = select(true, used);
            if (terminal == null) break;
            used.add(terminal.stack());
            extracted += terminal.host().extractAEPower(requested - extracted, mode, multiplier);
        }
        return extracted;
    }

    private void refreshTerminals() {
        List<Terminal> current = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof WirelessTerminalItem item) || linkedGrid(player, item, stack) != grid) continue;
            Terminal existing = null;
            for (Terminal terminal : terminals) {
                if (terminal.slot() == slot && terminal.stack() == stack) {
                    existing = terminal;
                    break;
                }
            }
            current.add(existing != null ? existing : new Terminal(slot, stack, item,
                new WirelessTerminalMenuHost(player, slot, stack, (ignoredPlayer, ignoredMenu) -> {})));
        }
        terminals.clear();
        terminals.addAll(current);
    }

    @Override
    public Object identity() { return grid; }

    @Override
    public Object queryKey(ItemStack prototype) { return AEItemKey.of(prototype); }

    @Override
    public Scan scanQuery(Set<Item> wanted, Set<Object> seen, int position, int scanLimit) {
        if (wanted.isEmpty() || select(true) == null) return new Scan(ItemStack.EMPTY, 0, 0, true);
        var inventory = grid.getStorageService().getCachedInventory();
        int scanned = 0;
        for (Item item : wanted) {
            if (scanned >= scanLimit) return new Scan(ItemStack.EMPTY, 0, scanned, false);
            scanned++;
            AEItemKey prototype = AEItemKey.of(item);
            if (prototype == null) continue;
            for (var entry : inventory.findFuzzy(prototype, FuzzyMode.IGNORE_ALL)) {
                if (scanned >= scanLimit) return new Scan(ItemStack.EMPTY, 0, scanned, false);
                scanned++;
                if (entry.getKey() instanceof AEItemKey key && key.getItem() == item && entry.getLongValue() > 0 && !seen.contains(key))
                    return new Scan(key.toStack(1), 0, scanned, false);
            }
        }
        return new Scan(ItemStack.EMPTY, 0, scanned, true);
    }

    @Override
    public Object changeIdentity() { return grid.getStorageService().getInventory(); }

    @Override
    public int sourceId() {
        return APPLIED_ENERGISTICS;
    }

    @Override
    public long countMatching(Item item, java.util.function.Predicate<ItemStack> matches, long limit) {
        if (item == null || limit <= 0) return 0;
        AEItemKey preferred = AEItemKey.of(item);
        return preferred == null ? 0 : countMatching(preferred, matches, limit);
    }

    @Override
    public CountQuery prepareCount(ItemStack prototype, java.util.function.Predicate<ItemStack> matches) {
        AEItemKey preferred = AEItemKey.of(prototype);
        return limit -> preferred == null || limit <= 0 ? 0 : countMatching(preferred, matches, limit);
    }

    private long countMatching(AEItemKey preferred, java.util.function.Predicate<ItemStack> matches, long limit) {
        Terminal terminal = select(true);
        if (terminal == null) return 0;
        Item item = preferred.getItem();
        AEItemKey prototype = AEItemKey.of(item);
        if (prototype == null) return 0;
        var service = grid.getStorageService();
        var actionSource = IActionSource.ofPlayer(player, terminal.host());
        long count = 0;
        long preferredAmount = service.getInventory().extract(preferred, limit, Actionable.SIMULATE, actionSource);
        if (preferredAmount > 0 && matches.test(preferred.toStack(1))) {
            count = Math.min(limit, preferredAmount);
            if (count >= limit) return count;
        }
        for (var entry : service.getCachedInventory().findFuzzy(prototype, FuzzyMode.IGNORE_ALL)) {
            if (!(entry.getKey() instanceof AEItemKey key) || preferred.equals(key) || key.getItem() != item || entry.getLongValue() <= 0
                || !matches.test(key.toStack(1))) continue;
            long requested = limit - count;
            long available = service.getInventory().extract(key, requested, Actionable.SIMULATE, actionSource);
            count += Math.min(requested, Math.max(0, available));
            if (count >= limit) break;
        }
        return count;
    }

    @Override
    public List<ItemStack> preview(Set<Item> wanted) {
        List<ItemStack> result = new ArrayList<>();
        Terminal terminal = select(true);
        if (terminal == null || wanted.isEmpty()) return result;
        var service = grid.getStorageService();
        var inventory = service.getCachedInventory();
        var actionSource = IActionSource.ofPlayer(player, terminal.host());
        for (Item item : wanted) {
            AEItemKey prototype = AEItemKey.of(item);
            if (prototype == null) continue;
            for (var entry : inventory.findFuzzy(prototype, FuzzyMode.IGNORE_ALL)) {
                if (entry.getKey() instanceof AEItemKey key && entry.getLongValue() > 0) {
                    long amount = service.getInventory().extract(key, Math.min(StorageSources.MAX_COUNT, entry.getLongValue()),
                        Actionable.SIMULATE, actionSource);
                    if (amount > 0) result.add(key.toStack((int) Math.min(StorageSources.MAX_COUNT, amount)));
                }
            }
        }
        return result;
    }

    @Override
    public boolean visitPreview(java.util.function.Predicate<ItemStack> visitor) {
        Terminal terminal = select(true);
        if (terminal == null) return true;
        var service = grid.getStorageService();
        var actionSource = IActionSource.ofPlayer(player, terminal.host());
        for (var entry : service.getCachedInventory()) {
            if (entry.getKey() instanceof AEItemKey key && entry.getLongValue() > 0) {
                long amount = service.getInventory().extract(key, Math.min(StorageSources.MAX_COUNT, entry.getLongValue()),
                    Actionable.SIMULATE, actionSource);
                if (amount > 0 && !visitor.test(key.toStack((int) Math.min(StorageSources.MAX_COUNT, amount)))) return false;
            }
        }
        return true;
    }

    @Override
    public boolean visitPreview(Set<Item> wanted, java.util.function.Predicate<ItemStack> visitor) {
        if (wanted.isEmpty()) return true;
        Terminal terminal = select(true);
        if (terminal == null) return true;
        var service = grid.getStorageService();
        var inventory = service.getCachedInventory();
        var actionSource = IActionSource.ofPlayer(player, terminal.host());
        for (Item item : wanted) {
            AEItemKey prototype = AEItemKey.of(item);
            if (prototype == null) continue;
            for (var entry : inventory.findFuzzy(prototype, FuzzyMode.IGNORE_ALL)) {
                if (!(entry.getKey() instanceof AEItemKey key) || key.getItem() != item || entry.getLongValue() <= 0) continue;
                long amount = service.getInventory().extract(key, Math.min(StorageSources.MAX_COUNT, entry.getLongValue()),
                    Actionable.SIMULATE, actionSource);
                if (amount > 0 && !visitor.test(key.toStack((int) Math.min(StorageSources.MAX_COUNT, amount)))) return false;
            }
        }
        return true;
    }

    @Override
    public List<ItemStack> snapshot(Set<Item> wanted) {
        List<ItemStack> result = new ArrayList<>();
        Terminal terminal = select(true);
        if (terminal == null) return result;
        var storage = grid.getStorageService().getInventory();
        var actionSource = IActionSource.ofPlayer(player, terminal.host());
        for (var entry : storage.getAvailableStacks()) {
            if (entry.getKey() instanceof AEItemKey key && wanted.contains(key.getItem()) && entry.getLongValue() > 0) {
                long amount = storage.extract(key, Math.min(StorageSources.MAX_COUNT, entry.getLongValue()), Actionable.SIMULATE, actionSource);
                if (amount > 0) result.add(key.toStack((int) Math.min(StorageSources.MAX_COUNT, amount)));
            }
        }
        return result;
    }

    @Override
    public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
        if (prototype.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        Terminal terminal = select(true);
        if (terminal == null) return ItemStack.EMPTY;
        AEItemKey key = AEItemKey.of(prototype);
        long extracted = StorageHelper.poweredExtraction(this::extractPower, grid.getStorageService().getInventory(),
            key, Math.min(StorageSources.MAX_COUNT, amount), IActionSource.ofPlayer(player, terminal.host()),
            simulate ? Actionable.SIMULATE : Actionable.MODULATE);
        if (extracted <= 0) return ItemStack.EMPTY;
        if (!simulate) borrowed.merge(key, extracted, Long::sum);
        return key.toStack((int) extracted);
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        if (stack.isEmpty()) return stack;
        AEItemKey key = AEItemKey.of(stack);
        long debt = borrowed.getOrDefault(key, 0L);
        boolean returning = debt >= stack.getCount();
        Terminal terminal = select(!returning);
        if (terminal == null) return stack;
        var storage = grid.getStorageService().getInventory();
        var actionSource = IActionSource.ofPlayer(player, terminal.host());
        long inserted = returning
            ? storage.insert(key, stack.getCount(), Actionable.MODULATE, actionSource)
            : StorageHelper.poweredInsert(terminal.host(), storage, key, stack.getCount(), actionSource);
        if (inserted <= 0) return stack;
        if (debt > 0) {
            long remainingDebt = Math.max(0, debt - inserted);
            if (remainingDebt == 0) borrowed.remove(key);
            else borrowed.put(key, remainingDebt);
        }
        return key.toStack((int) Math.max(0, stack.getCount() - inserted));
    }

    @Override
    public void finish(int extracted) {
        borrowed.clear();
    }

    private record Terminal(int slot, ItemStack stack, WirelessTerminalItem item, WirelessTerminalMenuHost host) { }
}

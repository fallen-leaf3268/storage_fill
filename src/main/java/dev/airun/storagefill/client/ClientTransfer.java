package dev.airun.storagefill.client;

import dev.airun.storagefill.AllocationPlanner;
import dev.airun.storagefill.FillNetwork;
import dev.airun.storagefill.RequestLimits;
import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.storage.StorageSource;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = "storage_fill", value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class ClientTransfer {
    private static final int MAX_STACK_BYTES = 16384;
    private static final int MAX_REQUEST_BYTES = 262144;
    private static final int MAX_CACHE_ENTRIES = 1;
    private static AbstractContainerMenu cacheMenu;
    private static final Map<SimulationKey, Simulation> SIMULATIONS =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<IRecipeSlotsView, PreparedInputs> INPUTS = new java.util.IdentityHashMap<>();
    private static Pending pending;
    private static int operationSequence;

    private ClientTransfer() { }

    static void cancelForQuery() {
        Pending previous = pending;
        pending = null;
        clearSimulationCache();
        CustomTransfer.clear();
        if (previous != null) previous.context().completeRecipeTransfer(RecipeTransferResult.REJECTED);
    }

    public static <C extends AbstractContainerMenu, R> boolean captureQuery(C menu, R recipe, IRecipeSlotsView views,
            Player player, IRecipeTransferInfo<C, R> info, IStackHelper helper) {
        if (!RemoteInventory.collecting(menu, recipe, views)) return false;
        PreparedInputs prepared = prepare(views);
        if (prepared == null || !info.canHandle(menu, recipe)) return true;
        List<Slot> targets = List.copyOf(info.getRecipeSlots(menu, recipe));
        List<Slot> inventory = List.copyOf(info.getInventorySlots(menu, recipe));
        if (prepared.options().size() > targets.size() || !RequestLimits.validSlots(menu.slots.size(),
                targets.stream().mapToInt(slot -> slot.index).toArray(), inventory.stream().mapToInt(slot -> slot.index).toArray())) return true;
        AllocationPlanner.SearchBudget queryBudget = RemoteInventory.searchBudget(menu, recipe, views);
        AllocationPlanner.StockCheck<Item> itemCheck = null;
        if (helper.getClass() == mezz.jei.common.util.StackHelper.class) {
            List<AllocationPlanner.Need<Item>> requirements = new ArrayList<>();
            for (List<ItemStack> options : prepared.options()) {
                if (!options.isEmpty()) requirements.add(new AllocationPlanner.Need<>(
                        options.stream().map(ItemStack::getItem).distinct().toList(), options.get(0).getCount(), Integer.MAX_VALUE));
            }
            itemCheck = AllocationPlanner.stockCheck(requirements);
        }
        AllocationPlanner.StockCheck<Item> stockCheck = itemCheck;
        RemoteInventory.bind(menu, recipe, views, new RemoteInventory.Evaluator() {
            public List<ItemStack> preferred() {
                List<ItemStack> result = new ArrayList<>();
                List<Slot> local = new ArrayList<>(inventory);
                local.addAll(targets);
                for (Slot slot : local) {
                    ItemStack stack = slot.getItem();
                    if (!stack.isEmpty() && slot.mayPickup(player) && accepts(StackKey.of(stack))) result.add(stack.copyWithCount(1));
                }
                result.addAll(preferredOptions(prepared.options()));
                return result;
            }
            public boolean accepts(StackKey key) {
                ItemStack prototype = key.stack(1);
                for (int i = 0; i < prepared.options().size(); i++) {
                    if (targets.get(i).mayPlace(prototype.copy()) && prepared.options().get(i).stream()
                            .anyMatch(option -> equivalent(helper, option, prototype))) return true;
                }
                return false;
            }
            public RemoteInventory.Selection select(RemoteInventory.View remote) {
                if (!info.canHandle(menu, recipe)) return null;
                List<Slot> local = new ArrayList<>(inventory);
                local.addAll(targets);
                if (stockCheck != null) {
                    Map<Item, Long> amounts = new java.util.HashMap<>();
                    for (Slot slot : local) {
                        ItemStack stack = slot.getItem();
                        if (!stack.isEmpty()) amounts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                    }
                    for (var row : remote.entries()) amounts.merge(row.key().item(), (long) row.count(), Long::sum);
                    if (!stockCheck.hasEnough(amounts)) return null;
                }
                Map<StackKey, Integer> stock = new LinkedHashMap<>();
                for (Slot slot : local) {
                    if (!slot.getItem().isEmpty() && !slot.mayPickup(player)) return null;
                    add(stock, slot.getItem());
                }
                Map<StackKey, Integer> localStock = new LinkedHashMap<>(stock);
                remote.entries().forEach(row -> add(stock, row));
                Map<StackKey, Integer> network = networkStock(localStock, remote);
                List<AllocationPlanner.Need<StackKey>> needs = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < prepared.options().size(); i++) {
                    List<ItemStack> options = prepared.options().get(i);
                    if (options.isEmpty()) continue;
                    Slot target = targets.get(i);
                    List<StackKey> keys = new ArrayList<>();
                    Map<StackKey, Integer> capacities = new LinkedHashMap<>();
                    int limit = 0;
                    for (StackKey key : stock.keySet()) {
                        ItemStack prototype = key.stack(1);
                        if (!target.mayPlace(prototype.copy()) || options.stream().noneMatch(option -> equivalent(helper, option, prototype))) continue;
                        int capacity = Math.min(prototype.getMaxStackSize(), target.getMaxStackSize(prototype.copy()));
                        keys.add(key);
                        capacities.put(key, capacity);
                        limit = Math.max(limit, capacity);
                    }
                    if (keys.isEmpty()) return null;
                    needs.add(new AllocationPlanner.Need<>(keys, options.get(0).getCount(), limit, capacities));
                    slots.add(target.index);
                }
                if (needs.isEmpty()) return null;
                Set<StackKey> matching = new java.util.HashSet<>();
                needs.forEach(need -> matching.addAll(need.alternatives()));
                stock.keySet().retainAll(matching);
                localStock.keySet().retainAll(matching);
                network.keySet().retainAll(matching);
                var plan = AllocationPlanner.planByPriority(needs, stock, localStock, network, false, queryBudget, queryBudget.remaining());
                if (plan.isEmpty()) return null;
                Map<Integer, StackKey> selected = new LinkedHashMap<>();
                List<ItemStack> required = new ArrayList<>();
                for (int i = 0; i < plan.get().size(); i++) {
                    var choice = plan.get().get(i);
                    selected.put(slots.get(i), choice.key());
                    required.add(choice.key().stack(choice.count()));
                }
                return new RemoteInventory.Selection(required, selected);
            }
        });
        return true;
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void logout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        CustomTransfer.clear();
        RemoteInventory.invalidate();
        clearSimulationCache();
        Pending active = pending;
        pending = null;
        if (active != null) active.context().completeRecipeTransfer(RecipeTransferResult.REJECTED);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void screenClosed(net.minecraftforge.client.event.ScreenEvent.Closing event) {
        Pending active = pending;
        if (active != null && active.screen() == event.getScreen()) {
            pending = null;
            active.context().completeRecipeTransfer(RecipeTransferResult.REJECTED);
        }
        CustomTransfer.cancel(event.getScreen());
        if (event.getScreen() instanceof mezz.jei.gui.recipes.RecipesGui) {
            clearSimulationCache();
            CustomTransfer.clearCache();
        }
    }

    public record Result(boolean handled, IRecipeTransferError error) {
        static Result pass() { return new Result(false, null); }
    }

    public static <C extends AbstractContainerMenu, R> Result transfer(C menu, R recipe, IRecipeSlotsView views,
            Player player, boolean maximum, boolean perform, IRecipeTransferInfo<C, R> info,
            IStackHelper helper, IRecipeTransferHandlerHelper errors, IRecipeTransferContext<R, C> context) {
        if (RemoteInventory.collecting(menu, recipe, views)) return Result.pass();
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !FillNetwork.CHANNEL.isRemotePresent(connection.getConnection()) || !info.canHandle(menu, recipe)) {
            return Result.pass();
        }
        if (pending != null || CustomTransfer.isPending()) return new Result(true, pendingError());
        if (RemoteInventory.state(menu, recipe, views).status() != RemoteInventory.Status.READY) {
            if (perform) RemoteInventory.reportUnavailable(menu, recipe, views);
            return Result.pass();
        }
        resetSimulationCache(menu);
        List<Slot> targets = info.getRecipeSlots(menu, recipe);
        List<Slot> inventory = info.getInventorySlots(menu, recipe);
        int[] targetIds = targets.stream().mapToInt(slot -> slot.index).toArray();
        int[] inventoryIds = inventory.stream().mapToInt(slot -> slot.index).toArray();
        if (!RequestLimits.validSlots(menu.slots.size(), targetIds, inventoryIds)) return Result.pass();
        List<IRecipeSlotView> inputs = views.getSlotViews(RecipeIngredientRole.INPUT);
        if (inputs.size() > targets.size()) return Result.pass();

        PreparedInputs prepared = prepare(views);
        if (prepared == null) return Result.pass();
        List<List<ItemStack>> optionsByInput = prepared.options();
        List<ItemStack> candidates = prepared.candidates();
        if (candidates.isEmpty()) return Result.pass();

        RemoteInventory.View remoteView = RemoteInventory.get(menu, recipe, views, candidates);
        if (remoteView.request() < 0) {
            if (perform) RemoteInventory.reportUnavailable(menu, recipe, views);
            return Result.pass();
        }
        RemoteInventory.Selection selection = RemoteInventory.selection(menu, recipe, views);
        if (selection == null) return Result.pass();
        boolean completeSets = info.requireCompleteSets(menu, recipe);
        SimulationKey simulationKey = new SimulationKey(menu.getStateId(), new RecipeIdentity(recipe),
                prepared.signatures(), remoteView.request(), remoteView.revision(), maximum, completeSets);
        if (!perform) {
            resetSimulationCache(menu);
            Simulation cached = SIMULATIONS.get(simulationKey);
            if (cached != null) return result(cached, inputs, errors);
        }

        Map<StackKey, Integer> stock = new LinkedHashMap<>();
        List<Slot> local = new ArrayList<>(inventory);
        local.addAll(targets);
        for (Slot slot : local) {
            if (!slot.getItem().isEmpty() && !slot.mayPickup(player)) return Result.pass();
            add(stock, slot.getItem());
        }
        Map<StackKey, Integer> localStock = new LinkedHashMap<>(stock);
        remoteView.entries().forEach(entry -> add(stock, entry));
        Map<StackKey, Integer> networkStock = networkStock(localStock, remoteView);
        List<FillNetwork.Input> requests = new ArrayList<>();
        List<AllocationPlanner.Need<StackKey>> needs = new ArrayList<>();
        int candidateCount = 0;
        long requestBytes = 0;
        for (int i = 0; i < targets.size(); i++) {
            Slot slot = targets.get(i);
            List<ItemStack> options = i < optionsByInput.size() ? optionsByInput.get(i) : List.of();
            if (options.isEmpty()) {
                if (i < inputs.size() && !inputs.get(i).isEmpty()) return Result.pass();
                requests.add(new FillNetwork.Input(slot.index, List.of()));
                continue;
            }
            List<StackKey> matches = new ArrayList<>();
            Map<StackKey, Integer> capacities = new LinkedHashMap<>();
            Map<Item, List<ItemStack>> byItem = byItem(options);
            int limit = 0;
            for (StackKey key : stock.keySet()) {
                if (!key.equals(selection.inputs().get(slot.index))) continue;
                List<ItemStack> alternatives = byItem.get(key.item());
                if (alternatives == null) continue;
                ItemStack prototype = key.stack(1);
                if (!slot.mayPlace(prototype.copy()) || alternatives.stream().noneMatch(option -> equivalent(helper, option, prototype))) continue;
                candidateCount++;
                requestBytes += key.data().sizeInBytes();
                if (candidateCount > RequestLimits.MAX_CANDIDATES || requestBytes > MAX_REQUEST_BYTES) {
                    if (!perform) cache(simulationKey, new Simulation(Status.PASS, -1));
                    return Result.pass();
                }
                int capacity = Math.min(prototype.getMaxStackSize(), slot.getMaxStackSize(prototype.copy()));
                matches.add(key);
                capacities.put(key, capacity);
                limit = Math.max(limit, capacity);
            }
            if (matches.isEmpty()) {
                Simulation simulation = new Simulation(Status.MISSING_SLOT, i);
                if (!perform) cache(simulationKey, simulation);
                return result(simulation, inputs, errors);
            }
            int count = options.get(0).getCount();
            needs.add(new AllocationPlanner.Need<>(matches, count, limit, capacities));
            requests.add(new FillNetwork.Input(slot.index, matches.stream().map(key -> key.stack(count)).toList()));
        }
        var matched = new java.util.HashSet<StackKey>();
        needs.forEach(need -> matched.addAll(need.alternatives()));
        stock.keySet().retainAll(matched);
        localStock.keySet().retainAll(matched);
        networkStock.keySet().retainAll(matched);
        var plan = AllocationPlanner.planByPriority(needs, stock, localStock, networkStock, maximum, completeSets);
        if (plan.isEmpty()) {
            Simulation simulation = new Simulation(Status.MISSING_GENERAL, -1);
            if (!perform) cache(simulationKey, simulation);
            return result(simulation, inputs, errors);
        }
        if (perform) {
            if (operationSequence == Integer.MAX_VALUE) return Result.pass();
            int operation = ++operationSequence;
            pending = new Pending(operation, menu, Minecraft.getInstance().screen, System.nanoTime(), context);
            try {
                FillNetwork.CHANNEL.sendToServer(new FillNetwork.Fill(menu.containerId, menu.getStateId(),
                    remoteView.request(), requests, inventoryIds, maximum, completeSets, operation));
            } catch (RuntimeException error) {
                pending = null;
                context.completeRecipeTransfer(RecipeTransferResult.REJECTED);
                dev.airun.storagefill.StorageFill.LOGGER.error("Unable to request recipe fill", error);
                player.displayClientMessage(Component.translatable("storage_fill.failed"), false);
            }
            clearSimulationCache();
            return new Result(true, pendingError());
        } else {
            IRecipeTransferError notice = sourceNotice(plan.get(), localStock, remoteView);
            if (notice != null && !maximum) {
                var maximumPlan = AllocationPlanner.planByPriority(needs, stock, localStock, networkStock, true, completeSets);
                if (maximumPlan.isPresent()) notice = withMaximumNotice(notice, sourceNotice(maximumPlan.get(), localStock, remoteView));
            }
            Simulation simulation = new Simulation(Status.SUCCESS, -1, notice);
            cache(simulationKey, simulation);
            return result(simulation, inputs, errors);
        }
    }

    private static Result result(Simulation simulation, List<IRecipeSlotView> inputs,
            IRecipeTransferHandlerHelper errors) {
        return switch (simulation.status()) {
            case PASS -> Result.pass();
            case SUCCESS -> new Result(true, simulation.notice());
            case MISSING_GENERAL -> new Result(true, errors.createUserErrorWithTooltip(
                    Component.translatable("jei.tooltip.error.recipe.transfer.missing")));
            case MISSING_SLOT -> new Result(true, errors.createUserErrorForMissingSlots(
                    Component.translatable("jei.tooltip.error.recipe.transfer.missing"),
                    List.of(inputs.get(simulation.missingSlot()))));
        };
    }

    private static void resetSimulationCache(AbstractContainerMenu menu) {
        if (cacheMenu != menu) {
            cacheMenu = menu;
            SIMULATIONS.clear();
            INPUTS.clear();
        }
    }

    private static void cache(SimulationKey key, Simulation simulation) {
        SIMULATIONS.put(key, simulation);
        while (SIMULATIONS.size() > MAX_CACHE_ENTRIES) {
            SIMULATIONS.remove(SIMULATIONS.keySet().iterator().next());
        }
    }

    private static void clearSimulationCache() {
        cacheMenu = null;
        SIMULATIONS.clear();
        INPUTS.clear();
    }

    public static Map<StackKey, Integer> networkStock(Map<StackKey, Integer> local,
            RemoteInventory.View remote) {
        Map<StackKey, Integer> result = new LinkedHashMap<>(local);
        for (var entry : remote.entries()) {
            if (entry.sourceId() != StorageSource.SOPHISTICATED_BACKPACKS) {
                add(result, entry);
            }
        }
        return result;
    }

    private static void add(Map<StackKey, Integer> stock, ItemStack stack) {
        if (stack.isEmpty()) return;
        StackKey key = StackKey.of(stack);
        if (key.data().sizeInBytes() > MAX_STACK_BYTES) return;
        stock.merge(key, Math.min(RequestLimits.MAX_COUNT, stack.getCount()),
                (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
    }

    private static void add(Map<StackKey, Integer> stock, RemoteInventory.Stock entry) {
        if (entry.key().data().sizeInBytes() > MAX_STACK_BYTES) return;
        stock.merge(entry.key(), Math.min(RequestLimits.MAX_COUNT, entry.count()),
                (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
    }

    static Map<Item, List<ItemStack>> byItem(List<ItemStack> options) {
        Map<Item, List<ItemStack>> result = new LinkedHashMap<>();
        for (ItemStack option : options) result.computeIfAbsent(option.getItem(), ignored -> new ArrayList<>()).add(option);
        return result;
    }

    static List<ItemStack> preferredOptions(List<List<ItemStack>> options) {
        List<ItemStack> result = new ArrayList<>();
        int rounds = options.stream().mapToInt(List::size).max().orElse(0);
        for (int round = 0; round < rounds; round++) {
            for (var input : options) if (round < input.size()) result.add(input.get(round));
        }
        return result;
    }

    static boolean equivalent(IStackHelper helper, ItemStack option, ItemStack prototype) {
        boolean nativeHelper = helper.getClass() == mezz.jei.common.util.StackHelper.class;
        return helper.isEquivalent(nativeHelper ? option : option.copy(), nativeHelper ? prototype : prototype.copy(), UidContext.Recipe);
    }

    private enum Status {
        PASS,
        SUCCESS,
        MISSING_GENERAL,
        MISSING_SLOT
    }

    public static IRecipeTransferError sourceNotice(List<AllocationPlanner.Choice<StackKey>> choices,
            Map<StackKey, Integer> localStock, RemoteInventory.View remote) {
        return sourceNotice(choices, localStock, remote, true);
    }

    public static IRecipeTransferError sourceNotice(List<AllocationPlanner.Choice<StackKey>> choices,
            Map<StackKey, Integer> localStock, RemoteInventory.View remote, boolean supportsMaximum) {
        Component message = sourceMessage(choices, localStock, remote);
        return message == null ? null : new SourceNotice(message, supportsMaximum, message);
    }

    public static IRecipeTransferError withMaximumNotice(IRecipeTransferError ordinary, IRecipeTransferError maximum) {
        if (ordinary instanceof SourceNotice first && maximum instanceof SourceNotice second) {
            return new SourceNotice(first.message(), first.supportsMaximum(), second.message());
        }
        return ordinary;
    }

    private static Component sourceMessage(List<AllocationPlanner.Choice<StackKey>> choices,
            Map<StackKey, Integer> localStock, RemoteInventory.View remote) {
        Map<StackKey, Integer> remaining = new LinkedHashMap<>();
        choices.forEach(choice -> remaining.merge(choice.key(), choice.count(), Integer::sum));
        remaining.replaceAll((key, count) -> Math.max(0, count - localStock.getOrDefault(key, 0)));
        int left = remaining.values().stream().mapToInt(Integer::intValue).sum();
        var used = new java.util.LinkedHashSet<Integer>();
        for (var entry : remote.entries()) {
            if (left == 0) break;
            StackKey key = entry.key();
            int needed = remaining.getOrDefault(key, 0);
            if (needed <= 0) continue;
            int moved = Math.min(needed, entry.count());
            remaining.put(key, needed - moved);
            left -= moved;
            used.add(entry.sourceId());
        }
        if (used.isEmpty() || left > 0) return null;
        var names = Component.empty();
        for (int source : used) {
            String name = switch (source) {
                case StorageSource.BEYOND_DIMENSIONS -> "beyonddimensions";
                case StorageSource.REFINED_STORAGE -> "refinedstorage";
                case StorageSource.APPLIED_ENERGISTICS -> "ae2";
                case StorageSource.SOPHISTICATED_BACKPACKS -> "sophisticatedbackpacks";
                default -> throw new IllegalArgumentException("Unknown storage source");
            };
            if (!names.getSiblings().isEmpty()) names.append(Component.translatable("storage_fill.source_separator"));
            names.append(Component.translatable("storage_fill.source." + name));
        }
        return Component.translatable("storage_fill.remote_fill", names).withStyle(ChatFormatting.YELLOW);
    }

    private record SourceNotice(Component message, boolean supportsMaximum, Component maximumMessage) implements IRecipeTransferError {
        public Type getType() { return Type.COSMETIC; }
        public int getButtonHighlightColor() { return 0; }
        public int getMissingCountHint() { return 0; }
        public void getTooltip(ITooltipBuilder tooltip) {
            getTooltip(tooltip, net.minecraft.client.gui.screens.Screen.hasShiftDown());
        }
        private void getTooltip(ITooltipBuilder tooltip, boolean shift) {
            tooltip.add(Component.translatable(supportsMaximum && shift
                    ? "storage_fill.transfer_max" : "jei.tooltip.transfer"));
            tooltip.add(supportsMaximum && shift ? maximumMessage : message);
        }
    }

    private record Simulation(Status status, int missingSlot, IRecipeTransferError notice) {
        private Simulation(Status status, int missingSlot) { this(status, missingSlot, null); }
    }

    private record CandidateSignature(StackKey key, int count) {
    }

    static List<List<ItemStack>> options(IRecipeSlotsView views) {
        PreparedInputs prepared = prepare(views);
        return prepared == null ? List.of() : prepared.options();
    }

    private static PreparedInputs prepare(IRecipeSlotsView views) {
        var inputs = views.getSlotViews(RecipeIngredientRole.INPUT);
        if (inputs.isEmpty() || inputs.size() > RequestLimits.MAX_SLOTS) return null;
        List<List<ItemStack>> raw = new ArrayList<>();
        int count = 0;
        for (var input : inputs) {
            var alternatives = input.getIngredients(VanillaTypes.ITEM_STACK).filter(stack -> !stack.isEmpty())
                .limit(RequestLimits.MAX_CANDIDATES + 1L).toList();
            count += alternatives.size();
            if (count > RequestLimits.MAX_CANDIDATES) return null;
            raw.add(alternatives);
        }
        PreparedInputs existing = INPUTS.get(views);
        if (existing != null && sameInputs(raw, existing.options())) return existing;
        List<List<ItemStack>> options = new ArrayList<>();
        List<List<CandidateSignature>> signatures = new ArrayList<>();
        List<ItemStack> candidates = new ArrayList<>();
        long bytes = 0;
        for (var alternatives : raw) {
            List<ItemStack> copies = new ArrayList<>();
            List<CandidateSignature> signature = new ArrayList<>();
            int required = alternatives.isEmpty() ? 0 : alternatives.get(0).getCount();
            for (ItemStack stack : alternatives) {
                if (required < 1 || required > 64 || stack.getCount() != required) return null;
                StackKey key = StackKey.of(stack);
                int size = key.data().sizeInBytes();
                bytes += size;
                if (size > MAX_STACK_BYTES || bytes > MAX_REQUEST_BYTES) return null;
                copies.add(stack.copy());
                signature.add(new CandidateSignature(key, required));
            }
            options.add(List.copyOf(copies));
            candidates.addAll(copies);
            signatures.add(List.copyOf(signature));
        }
        PreparedInputs result = new PreparedInputs(List.copyOf(options), List.copyOf(signatures), List.copyOf(candidates));
        if (INPUTS.size() >= 128) INPUTS.remove(INPUTS.keySet().iterator().next());
        INPUTS.put(views, result);
        return result;
    }

    private static boolean sameInputs(List<List<ItemStack>> current, List<List<ItemStack>> previous) {
        if (current.size() != previous.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).size() != previous.get(i).size()) return false;
            for (int j = 0; j < current.get(i).size(); j++) {
                if (!ItemStack.matches(current.get(i).get(j), previous.get(i).get(j))) return false;
            }
        }
        return true;
    }

    private record PreparedInputs(List<List<ItemStack>> options, List<List<CandidateSignature>> signatures,
                                  List<ItemStack> candidates) { }

    public static boolean isPending() { return pending != null; }

    public static <R, C extends AbstractContainerMenu> boolean deferMaximum(C menu, IRecipeSlotsView views,
                                       Function<IRecipeTransferContext<R, C>, IRecipeTransferError> resume,
                                       IRecipeTransferContext<R, C> context) {
        var client = Minecraft.getInstance();
        if (!(client.screen instanceof mezz.jei.gui.recipes.RecipesGui)) return false;
        var connection = client.getConnection();
        if (connection == null || !FillNetwork.CHANNEL.isRemotePresent(connection.getConnection())) return false;
        if (!RemoteInventory.shouldWait(menu, context.getRecipe(), views)) return false;
        PreparedInputs prepared = prepare(views);
        if (prepared == null || prepared.candidates().isEmpty() || operationSequence == Integer.MAX_VALUE) return false;
        if (RemoteInventory.get(menu, context.getRecipe(), views, prepared.candidates()).request() >= 0) return false;
        if (!RemoteInventory.shouldWait(menu, context.getRecipe(), views)) {
            RemoteInventory.reportUnavailable(menu, context.getRecipe(), views);
            return false;
        }
        int operation = ++operationSequence;
        DeferredContext<R, C> completion = new DeferredContext<>(context, operation, menu, client.screen);
        pending = new Pending(operation, menu, client.screen, System.nanoTime(), completion,
                prepared.candidates(), () -> resume.apply(completion));
        return true;
    }

    public static IRecipeTransferError pendingError() {
        return new IRecipeTransferError() {
            public Type getType() { return Type.USER_FACING; }
            public void getTooltip(ITooltipBuilder tooltip) { }
        };
    }

    public static void accept(FillNetwork.FillResult result) {
        Pending active = pending;
        if (active == null || active.operation() != result.operation() || active.menu().containerId != result.menu()) return;
        pending = null;
        clearSimulationCache();
        var client = Minecraft.getInstance();
        if (client.player == null || client.player.containerMenu != active.menu() || client.screen != active.screen()) {
            active.context().completeRecipeTransfer(RecipeTransferResult.REJECTED);
            return;
        }
        if (result.success()) {
            active.context().completeRecipeTransfer(RecipeTransferResult.SUCCESS);
            if (client.screen == active.screen() && client.screen != null) client.screen.onClose();
        } else {
            active.context().completeRecipeTransfer(RecipeTransferResult.REJECTED);
            client.player.displayClientMessage(result.message(), false);
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || pending == null) return;
        var client = Minecraft.getInstance();
        Pending active = pending;
        if (client.player == null || client.player.containerMenu != active.menu() || client.screen != active.screen()) {
            pending = null;
            active.context().completeRecipeTransfer(RecipeTransferResult.REJECTED);
        } else if (active.resume() != null) {
            boolean unavailable = RemoteInventory.get(active.menu(), active.context().getRecipe(), active.context().getRecipeSlots(), active.candidates()).request() < 0;
            if (unavailable && RemoteInventory.shouldWait(active.menu(), active.context().getRecipe(), active.context().getRecipeSlots())) {
                if (System.nanoTime() - active.started() <= 30_000_000_000L) return;
                RemoteInventory.stopWaiting(active.menu(), active.context().getRecipe(), active.context().getRecipeSlots());
            }
            if (unavailable) RemoteInventory.reportUnavailable(active.menu(), active.context().getRecipe(), active.context().getRecipeSlots());
            pending = null;
            try {
                IRecipeTransferError result = active.resume().get();
                if (pending != null || CustomTransfer.isPending()) return;
                if (result == null || result.getType().allowsTransfer) {
                    if (!completed(active.context())) pending = active.awaitingCompletion();
                } else {
                    active.context().completeRecipeTransfer(RecipeTransferResult.REJECTED);
                    if (!unavailable) client.player.displayClientMessage(Component.translatable("storage_fill.failed"), false);
                }
            } catch (RuntimeException error) {
                active.context().completeRecipeTransfer(RecipeTransferResult.REJECTED);
                dev.airun.storagefill.StorageFill.LOGGER.error("Deferred maximum fill failed", error);
                client.player.displayClientMessage(Component.translatable("storage_fill.failed"), false);
            }
        } else if (System.nanoTime() - active.started() > 10_000_000_000L) {
            boolean deferred = active.context() instanceof DeferredContext<?, ?>;
            pending = null;
            active.context().completeRecipeTransfer(RecipeTransferResult.REJECTED);
            if (!deferred) client.player.displayClientMessage(Component.translatable("storage_fill.failed"), false);
        }
    }

    private static boolean completed(IRecipeTransferContext<?, ?> context) {
        return context instanceof DeferredContext<?, ?> completion && completion.completed();
    }

    private static void completeDeferred(DeferredContext<?, ?> context, RecipeTransferResult result) {
        Pending active = pending;
        boolean waiting = active != null && active.context() == context;
        if (waiting) pending = null;
        if (!waiting && result != RecipeTransferResult.SUCCESS) return;
        var client = Minecraft.getInstance();
        if (client.player != null && client.player.containerMenu == context.menu
                && client.screen == context.screen && client.screen != null) {
            if (result == RecipeTransferResult.SUCCESS) client.screen.onClose();
            else if (waiting) client.player.displayClientMessage(Component.translatable("storage_fill.failed"), false);
        }
    }

    private static final class DeferredContext<R, C extends AbstractContainerMenu>
            implements IRecipeTransferContext<R, C> {
        private final IRecipeTransferContext<R, C> delegate;
        private final int operation;
        private final C menu;
        private final net.minecraft.client.gui.screens.Screen screen;
        private final AtomicBoolean completed = new AtomicBoolean();

        private DeferredContext(IRecipeTransferContext<R, C> delegate, int operation, C menu,
                                net.minecraft.client.gui.screens.Screen screen) {
            this.delegate = delegate;
            this.operation = operation;
            this.menu = menu;
            this.screen = screen;
        }

        public int getTransferId() { return delegate.getTransferId(); }
        public void completeRecipeTransfer(RecipeTransferResult result) {
            if (!completed.compareAndSet(false, true)) return;
            delegate.completeRecipeTransfer(result);
            ClientTransfer.completeDeferred(this, result);
        }
        public R getRecipe() { return delegate.getRecipe(); }
        public mezz.jei.api.recipe.RecipeType<R> getRecipeType() { return delegate.getRecipeType(); }
        public C getContainer() { return delegate.getContainer(); }
        public net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<C> getScreen() { return delegate.getScreen(); }
        public IRecipeSlotsView getRecipeSlots() { return delegate.getRecipeSlots(); }
        public Player getPlayer() { return delegate.getPlayer(); }
        public boolean isMaxTransfer() { return delegate.isMaxTransfer(); }
        private boolean completed() { return completed.get(); }
    }

    private record Pending(int operation, AbstractContainerMenu menu, net.minecraft.client.gui.screens.Screen screen,
                           long started, IRecipeTransferContext<?, ?> context, List<ItemStack> candidates,
                           java.util.function.Supplier<IRecipeTransferError> resume) {
        private Pending(int operation, AbstractContainerMenu menu, net.minecraft.client.gui.screens.Screen screen,
                        long started, IRecipeTransferContext<?, ?> context) {
            this(operation, menu, screen, started, context, List.of(), null);
        }
        private Pending awaitingCompletion() {
            return new Pending(operation, menu, screen, System.nanoTime(), context, List.of(), null);
        }
    }

    private record SimulationKey(int state, RecipeIdentity recipe,
                                 List<List<CandidateSignature>> inputs, int request, long revision, boolean maximum,
                                 boolean completeSets) {
    }

    private static final class RecipeIdentity {
        private final Object recipe;

        private RecipeIdentity(Object recipe) {
            this.recipe = recipe;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RecipeIdentity identity && recipe == identity.recipe;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(recipe);
        }
    }
}

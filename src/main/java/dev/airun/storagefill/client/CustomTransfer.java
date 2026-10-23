package dev.airun.storagefill.client;

import dev.airun.storagefill.AllocationPlanner;
import dev.airun.storagefill.FillNetwork;
import dev.airun.storagefill.InventoryTopUp;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.RequestLimits;
import dev.airun.storagefill.StackKey;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.common.Internal;
import mezz.jei.library.transfer.BasicRecipeTransferHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = StorageFill.ID, value = Dist.CLIENT)
public final class CustomTransfer {
    private static final Map<CacheKey, Cached> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static Pending pending;
    private static int sequence;

    private CustomTransfer() { }

    public static <C extends AbstractContainerMenu, R> IRecipeTransferError transfer(
            IRecipeTransferHandler<C, R> handler, TransferContext<R, C> context, boolean perform) {
        if (handler instanceof BasicRecipeTransferHandler<?, ?>) {
            return JeiTransfers.invoke(handler, context, perform);
        }
        if (PlayerCraftingTransfer.supports(handler)) return PlayerCraftingTransfer.transfer(handler, context, perform);
        C menu = context.getContainer();
        R recipe = context.getRecipe();
        IRecipeSlotsView views = context.getRecipeSlots();
        Player player = context.getPlayer();
        boolean maximum = context.isMaxTransfer();
        if (RemoteInventory.collecting(menu, recipe, views)) {
            IRecipeTransferError original = JeiTransfers.invoke(handler, context, false);
            List<List<ItemStack>> capturedOptions = options(views);
            if (capturedOptions.isEmpty() || (!allows(original) && original.getType() != IRecipeTransferError.Type.USER_FACING)) return original;
            IStackHelper capturedHelper = Internal.getJeiRuntime().getJeiHelpers().getStackHelper();
            AllocationPlanner.SearchBudget queryBudget = RemoteInventory.searchBudget(menu, recipe, views);
            RemoteInventory.bind(menu, recipe, views, new RemoteInventory.Evaluator() {
                private boolean rejected;
                public List<ItemStack> preferred() {
                    List<ItemStack> result = new ArrayList<>();
                    for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                        ItemStack stack = player.getInventory().getItem(slot);
                        if (!stack.isEmpty() && accepts(StackKey.of(stack))) result.add(stack.copyWithCount(1));
                    }
                    result.addAll(ClientTransfer.preferredOptions(capturedOptions));
                    return result;
                }
                public boolean accepts(StackKey key) {
                    ItemStack prototype = key.stack(1);
                    return capturedOptions.stream().flatMap(List::stream)
                            .anyMatch(option -> ClientTransfer.equivalent(capturedHelper, option, prototype));
                }
                public RemoteInventory.Selection select(RemoteInventory.View candidates) {
                    IRecipeTransferError current = JeiTransfers.invoke(handler, context, false);
                    if (allows(current)) {
                        StackKey mechanical = MechanicalRavenTransfer.selectedKey(handler, menu, recipe, player.getInventory());
                        return new RemoteInventory.Selection(mechanical == null ? List.of() : List.of(mechanical.stack(1)), Map.of());
                    }
                    Proposal proposal = preview(player.getInventory(), capturedOptions, candidates, false, capturedHelper,
                            () -> JeiTransfers.invoke(handler, context, false), false, value -> rejected |= value, queryBudget);
                    if (proposal == null) {
                        return null;
                    }
                    return new RemoteInventory.Selection(proposal.required(), Map.of());
                }
                public boolean ruleRejected() { return rejected; }
            });
            return original;
        }
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        if (connection == null || !FillNetwork.CHANNEL.isRemotePresent(connection.getConnection())) {
            return JeiTransfers.invoke(handler, context, perform);
        }
        if (pending != null || ClientTransfer.isPending()) return ClientTransfer.pendingError();
        IRecipeTransferError original = JeiTransfers.invoke(handler, context, false);
        RemoteInventory.Status queryStatus = RemoteInventory.state(menu, recipe, views).status();
        if (queryStatus != RemoteInventory.Status.READY && queryStatus != RemoteInventory.Status.QUERYING) {
            if (perform) RemoteInventory.reportUnavailable(menu, recipe, views);
            return nativeTransfer(handler, context, perform, original);
        }
        boolean mechanical = MechanicalRavenTransfer.supports(handler, menu, recipe);
        boolean remoteMaximum = maximum && mechanical && client.screen instanceof mezz.jei.gui.recipes.RecipesGui;
        if (allows(original) && !remoteMaximum) {
            return perform ? JeiTransfers.invoke(handler, context, true) : original;
        }
        if (!allows(original) && original.getType() != IRecipeTransferError.Type.USER_FACING) return original;
        List<List<ItemStack>> options = options(views);
        if (options.isEmpty()) return nativeTransfer(handler, context, perform, original);
        if (remoteMaximum && perform && ClientTransfer.deferMaximum(menu, views, resumed -> {
            IRecipeTransferError result = transfer(handler, resumed, true);
            if (!isPending() && !ClientTransfer.isPending() && allows(result) && JeiTransfers.usesLegacyTransfer(handler)) {
                resumed.completeRecipeTransfer(TransferContext.Result.SUCCESS);
            }
            return result;
        }, context)) return ClientTransfer.pendingError();
        List<ItemStack> candidates = options.stream().flatMap(List::stream).toList();
        RemoteInventory.View remote = RemoteInventory.get(menu, recipe, views, candidates);
        if (remote.request() < 0) {
            if (perform) RemoteInventory.reportUnavailable(menu, recipe, views);
            return nativeTransfer(handler, context, perform, original);
        }
        RemoteInventory.Selection selected = RemoteInventory.selection(menu, recipe, views);
        if (selected == null || selected.required().isEmpty()) return nativeTransfer(handler, context, perform, original);
        CacheKey key = new CacheKey(new Identity(menu), new Identity(handler), new Identity(recipe),
                menu.getStateId(), remote.request(), remote.revision(), maximum, new Identity(options));
        Proposal proposal;
        Cached cached = perform ? null : CACHE.get(key);
        if (cached != null && sameInventory(player.getInventory(), cached.inventory())) {
            proposal = cached.proposal();
            if (proposal != null) {
                var plan = InventoryTopUp.plan(copyInventory(player.getInventory()), proposal.required());
                if (plan.isEmpty()) return original;
                IRecipeTransferError current = simulate(player.getInventory(), plan.get().contents(),
                        () -> JeiTransfers.invoke(handler, context, false));
                if (!allows(current)) return current;
            }
        } else {
            IStackHelper helper = Internal.getJeiRuntime().getJeiHelpers().getStackHelper();
            Supplier<IRecipeTransferError> simulate = () -> JeiTransfers.invoke(handler, context, false);
            if (remoteMaximum) {
                proposal = MechanicalRavenTransfer.preview(handler, menu, recipe, player.getInventory(),
                        options, remote, helper, simulate, StackKey.of(selected.required().get(0)));
                if (!MechanicalRavenTransfer.supports(handler, menu, recipe) && !allows(original)) {
                    proposal = fixedPreview(player.getInventory(), selected.required(), remote, simulate, false);
                }
            } else {
                proposal = fixedPreview(player.getInventory(), selected.required(), remote, simulate, mechanical);
            }
            if (!perform && mechanical && !maximum && proposal != null) {
                Proposal shifted = MechanicalRavenTransfer.preview(handler, menu, recipe, player.getInventory(), options, remote, helper, simulate,
                        StackKey.of(selected.required().get(0)));
                if (shifted != null) proposal = new Proposal(proposal.required(),
                        ClientTransfer.withMaximumNotice(proposal.notice(), shifted.notice()));
            }
            if (!perform) {
                CACHE.clear();
                CACHE.put(key, new Cached(copyAllInventory(player.getInventory()), proposal));
            }
        }
        if (proposal == null) {
            return nativeTransfer(handler, context, perform, original);
        }
        if (!perform) return proposal.notice();
        if (sequence == Integer.MAX_VALUE) return nativeTransfer(handler, context, true, original);
        int operation = ++sequence;
        Screen screen = client.screen;
        CompletionContext<R, C> completion = new CompletionContext<>(context, operation);
        pending = new Pending(operation, menu, screen, System.nanoTime(), completion, () -> {
            IRecipeTransferError check = JeiTransfers.invoke(handler, completion, false);
            if (!allows(check)) return false;
            return allows(JeiTransfers.invoke(handler, completion, true));
        }, JeiTransfers.usesLegacyTransfer(handler));
        try {
            FillNetwork.CHANNEL.sendToServer(new FillNetwork.TopUp(menu.containerId, menu.getStateId(),
                    remote.request(), operation, proposal.required()));
        } catch (RuntimeException error) {
            pending = null;
            context.completeRecipeTransfer(TransferContext.Result.REJECTED);
            StorageFill.LOGGER.error("Unable to request inventory top-up", error);
            player.displayClientMessage(Component.translatable("storage_fill.failed"), false);
            return original;
        }
        return ClientTransfer.pendingError();
    }

    public record Proposal(List<ItemStack> required, IRecipeTransferError notice) { }

    private static Proposal fixedPreview(Inventory inventory, List<ItemStack> required, RemoteInventory.View remote,
            Supplier<IRecipeTransferError> simulate, boolean supportsMaximum) {
        List<ItemStack> before = copyInventory(inventory);
        var plan = InventoryTopUp.plan(before, required);
        if (plan.isEmpty() || plan.get().missing().isEmpty()) return null;
        Map<StackKey, Integer> available = counts(remote);
        for (ItemStack stack : plan.get().missing()) {
            StackKey key = StackKey.of(stack);
            if (available.getOrDefault(key, 0) < stack.getCount()) return null;
            available.put(key, available.get(key) - stack.getCount());
        }
        if (!allows(simulate(inventory, plan.get().contents(), simulate))) return null;
        List<AllocationPlanner.Choice<StackKey>> choices = required.stream()
                .map(stack -> new AllocationPlanner.Choice<>(StackKey.of(stack), stack.getCount())).toList();
        IRecipeTransferError notice = ClientTransfer.sourceNotice(choices, counts(before), remote, supportsMaximum);
        return notice == null ? null : new Proposal(required.stream().map(ItemStack::copy).toList(), notice);
    }

    public static Proposal preview(Inventory inventory, List<List<ItemStack>> options,
            RemoteInventory.View remote, boolean maximum, IStackHelper helper,
            Supplier<IRecipeTransferError> simulate) {
        return preview(inventory, options, remote, maximum, helper, simulate, false);
    }

    private static Proposal preview(Inventory inventory, List<List<ItemStack>> options,
            RemoteInventory.View remote, boolean maximum, IStackHelper helper,
            Supplier<IRecipeTransferError> simulate, boolean supportsMaximum) {
        return preview(inventory, options, remote, maximum, helper, simulate, supportsMaximum, null,
                new AllocationPlanner.SearchBudget());
    }

    private static Proposal preview(Inventory inventory, List<List<ItemStack>> options,
            RemoteInventory.View remote, boolean maximum, IStackHelper helper,
            Supplier<IRecipeTransferError> simulate, boolean supportsMaximum, java.util.function.Consumer<Boolean> declined,
            AllocationPlanner.SearchBudget budget) {
        List<ItemStack> before = copyInventory(inventory);
        Map<StackKey, Integer> local = counts(before);
        Map<StackKey, Integer> total = new LinkedHashMap<>(local);
        counts(remote).forEach((key, count) -> total.merge(key, count,
                (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b)));
        List<AllocationPlanner.Need<StackKey>> needs = new ArrayList<>();
        int candidates = 0;
        boolean completeMaterials = true;
        for (List<ItemStack> alternatives : options) {
            if (alternatives.isEmpty()) continue;
            int count = alternatives.get(0).getCount();
            if (count < 1 || count > 64 || alternatives.stream().anyMatch(stack -> stack.getCount() != count)) return null;
            List<StackKey> matching = new ArrayList<>();
            Map<StackKey, Integer> capacities = new LinkedHashMap<>();
            var byItem = ClientTransfer.byItem(alternatives);
            int limit = 0;
            for (StackKey key : total.keySet()) {
                List<ItemStack> itemOptions = byItem.get(key.item());
                if (itemOptions == null) continue;
                ItemStack prototype = key.stack(1);
                if (itemOptions.stream().noneMatch(option -> ClientTransfer.equivalent(helper, option, prototype))) continue;
                if (++candidates > AllocationPlanner.MAX_ALTERNATIVES) return null;
                matching.add(key);
                int capacity = Math.min(64, prototype.getMaxStackSize());
                capacities.put(key, capacity);
                limit = Math.max(limit, capacity);
            }
            if (matching.isEmpty()) {
                completeMaterials = false;
                continue;
            }
            needs.add(new AllocationPlanner.Need<>(matching, count, limit, capacities));
        }
        var matched = new java.util.HashSet<StackKey>();
        needs.forEach(need -> matched.addAll(need.alternatives()));
        total.keySet().retainAll(matched);
        local.keySet().retainAll(matched);
        List<Map<StackKey, Integer>> tiers = ClientTransfer.priorityTiers(local, remote, remote.priority());
        tiers.forEach(tier -> tier.keySet().retainAll(matched));
        var allocation = AllocationPlanner.planByPriority(needs, total, tiers,
                false, true, budget, budget.remaining());
        if (allocation.isEmpty()) return null;
        Map<StackKey, Integer> amounts = new LinkedHashMap<>();
        allocation.get().forEach(choice -> amounts.merge(choice.key(), choice.count(), Integer::sum));
        List<ItemStack> required = amounts.entrySet().stream().map(entry -> entry.getKey().stack(entry.getValue())).toList();
        var plan = InventoryTopUp.plan(before, required);
        if (plan.isEmpty() || plan.get().missing().isEmpty()) {
            if (completeMaterials && declined != null) declined.accept(true);
            return null;
        }
        if (!allows(simulate(inventory, plan.get().contents(), simulate))) {
            if (completeMaterials && declined != null) declined.accept(true);
            return null;
        }
        {
            for (StackKey key : new ArrayList<>(amounts.keySet())) {
                int previous = amounts.remove(key);
                List<ItemStack> reduced = amounts.entrySet().stream().map(entry -> entry.getKey().stack(entry.getValue())).toList();
                var trial = reduced.isEmpty() ? java.util.Optional.<InventoryTopUp.Plan>empty() : InventoryTopUp.plan(before, reduced);
                if (trial.isEmpty() || !allows(simulate(inventory, trial.get().contents(), simulate))) amounts.put(key, previous);
            }
            required = amounts.entrySet().stream().map(entry -> entry.getKey().stack(entry.getValue())).toList();
        }
        List<AllocationPlanner.Choice<StackKey>> choices = amounts.entrySet().stream()
                .map(entry -> new AllocationPlanner.Choice<>(entry.getKey(), entry.getValue())).toList();
        long requestBytes = 0;
        for (ItemStack stack : required) {
            int size = StackKey.of(stack).data().sizeInBytes();
            requestBytes += size;
            if (size > 16384 || requestBytes > 262144) return null;
        }
        IRecipeTransferError notice = ClientTransfer.sourceNotice(choices, local, remote, supportsMaximum);
        return notice == null ? null : new Proposal(required, notice);
    }

    public static <T> T simulate(Inventory inventory, List<ItemStack> contents, Supplier<T> action) {
        List<ItemStack> saved = new ArrayList<>(inventory.items);
        try {
            for (int i = 0; i < saved.size(); i++) inventory.items.set(i, contents.get(i).copy());
            return action.get();
        } finally {
            for (int i = 0; i < saved.size(); i++) inventory.items.set(i, saved.get(i));
        }
    }

    private static List<List<ItemStack>> options(IRecipeSlotsView views) {
        return ClientTransfer.options(views);
    }

    private static Map<StackKey, Integer> counts(List<ItemStack> stacks) {
        Map<StackKey, Integer> result = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            StackKey key = StackKey.of(stack);
            if (key.data().sizeInBytes() <= 16384) {
                result.merge(key, stack.getCount(), (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
            }
        }
        return result;
    }

    private static Map<StackKey, Integer> counts(RemoteInventory.View remote) {
        Map<StackKey, Integer> result = new LinkedHashMap<>();
        for (var entry : remote.entries()) {
            if (entry.key().data().sizeInBytes() <= 16384) result.merge(entry.key(), entry.count(),
                    (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
        }
        return result;
    }

    private static List<ItemStack> copyInventory(Inventory inventory) {
        return inventory.items.stream().map(ItemStack::copy).toList();
    }

    private static List<ItemStack> copyAllInventory(Inventory inventory) {
        List<ItemStack> result = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) result.add(inventory.getItem(slot).copy());
        return result;
    }

    private static boolean sameInventory(Inventory inventory, List<ItemStack> expected) {
        if (inventory.getContainerSize() != expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!ItemStack.matches(inventory.getItem(i), expected.get(i))) return false;
        }
        return true;
    }

    private static <C extends AbstractContainerMenu, R> IRecipeTransferError nativeTransfer(
            IRecipeTransferHandler<C, R> handler, TransferContext<R, C> context, boolean perform,
            IRecipeTransferError original) {
        return perform && allows(original) ? JeiTransfers.invoke(handler, context, true) : original;
    }

    private static boolean allows(IRecipeTransferError error) {
        return error == null || error.getType().allowsTransfer;
    }

    public static void accept(FillNetwork.TopUpResult result) {
        Pending active = pending;
        if (active == null || active.operation() != result.operation() || active.menu().containerId != result.menu()) return;
        if (active.resume() == null) return;
        CACHE.clear();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.containerMenu != active.menu() || client.screen != active.screen()
                || System.nanoTime() - active.started() > 10_000_000_000L) {
            pending = null;
            active.context().completeRecipeTransfer(TransferContext.Result.REJECTED);
            return;
        }
        if (!result.success()) {
            pending = null;
            active.context().completeRecipeTransfer(TransferContext.Result.REJECTED);
            client.player.displayClientMessage(result.message(), false);
            return;
        }
        pending = active.awaitingCompletion();
        boolean transferred = false;
        try { transferred = active.resume().get(); }
        catch (RuntimeException error) { StorageFill.LOGGER.error("Custom recipe transfer failed after top-up", error); }
        if (transferred && active.legacy()) {
            active.context().completeRecipeTransfer(TransferContext.Result.SUCCESS);
        } else if (!transferred && !completed(active.context())) {
            if (pending != null && pending.operation() == active.operation()) pending = null;
            active.context().completeRecipeTransfer(TransferContext.Result.REJECTED);
            client.player.displayClientMessage(Component.translatable("storage_fill.topped_up_retry"), true);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending == null) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.containerMenu != pending.menu() || client.screen != pending.screen()
                ) {
            pending.context().completeRecipeTransfer(TransferContext.Result.REJECTED);
            pending = null;
        }
        else if (System.nanoTime() - pending.started() > 10_000_000_000L) {
            pending.context().completeRecipeTransfer(TransferContext.Result.REJECTED);
            pending = null;
            client.player.displayClientMessage(Component.translatable("storage_fill.failed"), false);
        }
    }

    public static void clear() {
        Pending active = pending;
        pending = null;
        if (active != null) active.context().completeRecipeTransfer(TransferContext.Result.REJECTED);
        CACHE.clear();
    }

    static void cancel(Screen screen) {
        Pending active = pending;
        if (active == null || active.screen() != screen) return;
        pending = null;
        active.context().completeRecipeTransfer(TransferContext.Result.REJECTED);
    }

    static void clearCache() { CACHE.clear(); }

    public static boolean isPending() { return pending != null; }

    private static boolean completed(TransferContext<?, ?> context) {
        return context instanceof CompletionContext<?, ?> completion && completion.completed();
    }

    private static void complete(int operation, TransferContext.Result result) {
        Pending active = pending;
        if (active == null || active.operation() != operation) return;
        pending = null;
        if (result == TransferContext.Result.SUCCESS) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.player.containerMenu == active.menu()
                    && client.screen == active.screen() && client.screen != null) client.screen.onClose();
        }
    }

    private static final class CompletionContext<R, C extends AbstractContainerMenu>
            implements TransferContext<R, C> {
        private final TransferContext<R, C> delegate;
        private final int operation;
        private final AtomicBoolean completed = new AtomicBoolean();

        private CompletionContext(TransferContext<R, C> delegate, int operation) {
            this.delegate = delegate;
            this.operation = operation;
        }

        public int getTransferId() { return delegate.getTransferId(); }
        public void completeRecipeTransfer(TransferContext.Result result) {
            if (!completed.compareAndSet(false, true)) return;
            delegate.completeRecipeTransfer(result);
            CustomTransfer.complete(operation, result);
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

    private record Pending(int operation, AbstractContainerMenu menu, Screen screen, long started,
                           TransferContext<?, ?> context, Supplier<Boolean> resume, boolean legacy) {
        private Pending awaitingCompletion() {
            return new Pending(operation, menu, screen, System.nanoTime(), context, null, legacy);
        }
    }
    private record Cached(List<ItemStack> inventory, Proposal proposal) { }
    private record CacheKey(Identity menu, Identity handler, Identity recipe, int state, int request,
                            long revision, boolean maximum, Identity ingredients) { }
    private record Identity(Object value) {
        public boolean equals(Object other) { return other instanceof Identity identity && value == identity.value; }
        public int hashCode() { return System.identityHashCode(value); }
    }
    private record Message(String key) implements IRecipeTransferError {
        public Type getType() { return Type.USER_FACING; }
        public void getTooltip(ITooltipBuilder tooltip) { tooltip.add(Component.translatable(key)); }
    }
}

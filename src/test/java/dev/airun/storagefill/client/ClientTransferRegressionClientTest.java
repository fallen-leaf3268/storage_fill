package dev.airun.storagefill.client;

import dev.airun.storagefill.FillNetwork;
import dev.airun.storagefill.RequestLimits;
import dev.airun.storagefill.StackKey;
import dev.airun.storagefill.StorageFill;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.common.Internal;
import mezz.jei.common.gui.JeiTooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public final class ClientTransferRegressionClientTest {
    public static void run(Minecraft client) {
        try {
            firstMatchRetainsOnlySelectedVariant(client);
            shiftNoticeUsesMaximumPlan(client);
            incompleteSetsShiftNoticeUsesIndependentSlots(client);
            customCacheRechecksMachineRules(client);
            queryReplacementRejectsOldReplies(client);
            unknownAndMissingRemainDistinct(client);
            repeatedCandidateCountsDoNotAccumulate(client);
            customMissingMaterialIsNotRuleRejection(client);
            querySearchBudgetIsShared(client);
            StorageFill.LOGGER.info("STORAGE_FILL_CLIENT_PREVIEW_REGRESSIONS_PASSED");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        } finally {
            if (client.screen instanceof mezz.jei.gui.recipes.RecipesGui) client.screen.onClose();
        }
    }

    private static void firstMatchRetainsOnlySelectedVariant(Minecraft client) throws ReflectiveOperationException {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            ItemStack stack = new ItemStack(Items.CARROT, i == 0 ? 1 : 64);
            stack.getOrCreateTag().putInt("variant", i);
            stacks.add(stack);
        }
        Fixture fixture = fixture(client, stacks.subList(0, 16), Collections.nCopies(16, 1));
        var view = view(fixture);
        check(view.request() == fixture.request() && view.entries().size() == 1
                        && view.entries().get(0).key().equals(StackKey.of(stacks.get(0)))
                        && view.entries().get(0).count() == 1,
                "First feasible recipe compared or retained later larger NBT variants");
        check(field(active(), "evaluator") == null && ((java.util.Map<?, ?>) field(active(), "pool")).isEmpty(),
                "Completed recipe query retained its collector or candidate pool");
        long[] calls = {0};
        IStackHelper nativeHelper = Internal.getJeiRuntime().getJeiHelpers().getStackHelper();
        IStackHelper counting = proxy(IStackHelper.class, (self, method, args) -> {
            if (!method.getName().equals("isEquivalent")) throw new UnsupportedOperationException(method.getName());
            calls[0]++;
            return nativeHelper.isEquivalent((ItemStack) args[0], (ItemStack) args[1], (UidContext) args[2]);
        });
        var firstResult = fixture.transfer(client, counting);
        check(firstResult.handled() && calls[0] > 0, "Selected recipe did not produce a Basic preview");
        long firstCalls = calls[0];
        var repeatedResult = fixture.transfer(client, counting);
        check(repeatedResult.handled() && calls[0] == firstCalls,
                "Repeated selected recipe preview reran material matching");
        RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(fixture.menu().containerId, fixture.request(), 1,
                stacks.subList(16, 32), Collections.nCopies(16, 1), false, false));
        check(view(fixture).entries().size() == 1 && view(fixture).entries().get(0).count() == 1,
                "Late continuation replaced a completed first-match result");
    }

    private static void shiftNoticeUsesMaximumPlan(Minecraft client) throws ReflectiveOperationException {
        Fixture fixture = fixture(client, List.of(new ItemStack(Items.CARROT), new ItemStack(Items.CARROT, 63)), List.of(1, 2));
        var result = fixture.transfer(client, Internal.getJeiRuntime().getJeiHelpers().getStackHelper());
        check(result.handled() && result.error() != null, "Actual Basic preview did not expose its source notice");
        String rs = Component.translatable("storage_fill.source.refinedstorage").getString();
        check(!tooltip(result.error(), false).get(1).getString().contains(rs)
                        && tooltip(result.error(), true).get(1).getString().contains(rs),
                "Actual Basic Shift preview retained the one-recipe source list");
    }

    private static void incompleteSetsShiftNoticeUsesIndependentSlots(Minecraft client) throws ReflectiveOperationException {
        Fixture fixture = fixture(client, List.of(new ItemStack(Items.IRON_INGOT),
                new ItemStack(Items.IRON_INGOT, 63), new ItemStack(Items.GOLD_INGOT)), List.of(1, 2, 1),
                List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)));
        List<Slot> targets = Stream.of(fixture.menu().getSlot(1), fixture.menu().getSlot(2),
                        fixture.menu().getSlot(3), fixture.menu().getSlot(4))
                .filter(slot -> slot.getItem().isEmpty()).limit(2).toList();
        check(targets.size() == 2, "Independent Shift fixture needs two empty crafting slots");
        List<IRecipeSlotView> inputs = new ArrayList<>();
        for (var item : List.of(Items.IRON_INGOT, Items.GOLD_INGOT)) {
            inputs.add(proxy(IRecipeSlotView.class, (self, method, args) -> switch (method.getName()) {
                case "getIngredients" -> args[0] == VanillaTypes.ITEM_STACK ? Stream.of(new ItemStack(item)) : Stream.empty();
                case "getRole" -> RecipeIngredientRole.INPUT;
                case "isEmpty" -> false;
                default -> objectMethod(self, method.getName(), args);
            }));
        }
        IRecipeSlotsView views = proxy(IRecipeSlotsView.class, (self, method, args) -> switch (method.getName()) {
            case "getSlotViews" -> inputs;
            default -> objectMethod(self, method.getName(), args);
        });
        boolean[] completeSets = {true};
        IRecipeTransferInfo<AbstractContainerMenu, Object> info = proxy(IRecipeTransferInfo.class,
                (self, method, args) -> switch (method.getName()) {
                    case "canHandle" -> true;
                    case "requireCompleteSets" -> completeSets[0];
                    case "getRecipeSlots" -> targets;
                    case "getInventorySlots" -> List.of();
                    default -> objectMethod(self, method.getName(), args);
                });
        IStackHelper helper = Internal.getJeiRuntime().getJeiHelpers().getStackHelper();
        check(RemoteInventory.query(fixture.menu(), fixture.recipe(), views,
                () -> ClientTransfer.captureQuery(fixture.menu(), fixture.recipe(), views, client.player, info, helper)),
                "Independent slot recipe query did not start");
        RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(fixture.menu().containerId, request(), 0,
                List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_INGOT, 63), new ItemStack(Items.GOLD_INGOT)),
                List.of(1, 2, 1), true, false));
        String rs = Component.translatable("storage_fill.source.refinedstorage").getString();
        for (boolean requireComplete : List.of(true, false, true)) {
            completeSets[0] = requireComplete;
            var result = ClientTransfer.transfer(fixture.menu(), fixture.recipe(), views, client.player,
                    false, false, info, helper, fixture.errors(), null);
            check(result.handled() && result.error() != null, "Independent Shift preview did not expose its source notice");
            check(!tooltip(result.error(), false).get(1).getString().contains(rs),
                    "Ordinary preview included stock beyond one complete recipe");
            check(tooltip(result.error(), true).get(1).getString().contains(rs) != requireComplete,
                    "Shift preview ignored the complete-set rule or reused the wrong cached rule");
        }
    }

    private static Fixture fixture(Minecraft client, List<ItemStack> stacks, List<Integer> sources) throws ReflectiveOperationException {
        return fixture(client, stacks, sources,
                List.of(new ItemStack(Items.STONE), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.CARROT)));
    }

    private static Fixture fixture(Minecraft client, List<ItemStack> stacks, List<Integer> sources,
            List<ItemStack> alternatives) throws ReflectiveOperationException {
        if (client.screen instanceof mezz.jei.gui.recipes.RecipesGui) client.screen.onClose();
        client.setScreen(new InventoryScreen(client.player));
        Internal.getJeiRuntime().getRecipesGui().showTypes(List.of(RecipeTypes.CRAFTING));
        check(client.screen instanceof mezz.jei.gui.recipes.RecipesGui, "Real JEI fixture did not open");
        AbstractContainerMenu menu = client.player.containerMenu;
        Slot target = Stream.of(menu.getSlot(1), menu.getSlot(2), menu.getSlot(3), menu.getSlot(4))
                .filter(slot -> slot.getItem().isEmpty()).findFirst().orElseThrow();
        IRecipeSlotView slot = proxy(IRecipeSlotView.class, (self, method, args) -> switch (method.getName()) {
            case "getIngredients" -> args[0] == VanillaTypes.ITEM_STACK ? alternatives.stream() : Stream.empty();
            case "getRole" -> RecipeIngredientRole.INPUT;
            case "isEmpty" -> false;
            default -> objectMethod(self, method.getName(), args);
        });
        IRecipeSlotsView views = proxy(IRecipeSlotsView.class, (self, method, args) -> switch (method.getName()) {
            case "getSlotViews" -> List.of(slot);
            default -> objectMethod(self, method.getName(), args);
        });
        IRecipeTransferInfo<AbstractContainerMenu, Object> info = proxy(IRecipeTransferInfo.class,
                (self, method, args) -> switch (method.getName()) {
                    case "canHandle", "requireCompleteSets" -> true;
                    case "getRecipeSlots" -> List.of(target);
                    case "getInventorySlots" -> List.of();
                    default -> objectMethod(self, method.getName(), args);
                });
        IRecipeTransferError missing = new IRecipeTransferError() {
            public Type getType() { return Type.USER_FACING; }
        };
        IRecipeTransferHandlerHelper errors = proxy(IRecipeTransferHandlerHelper.class,
                (self, method, args) -> method.getName().startsWith("createUserError") ? missing : objectMethod(self, method.getName(), args));
        Object recipe = new Object();
        check(active() == null && RemoteInventory.state(menu, recipe, views).status() == RemoteInventory.Status.IDLE,
                "Opening JEI queried remote storage automatically");
        check(!ClientTransfer.transfer(menu, recipe, views, client.player, false, false, info,
                Internal.getJeiRuntime().getJeiHelpers().getStackHelper(), errors, null).handled() && active() == null,
                "Automatic recipe preview queried remote storage");
        check(RemoteInventory.query(menu, recipe, views, () -> ClientTransfer.captureQuery(menu, recipe, views,
                client.player, info, Internal.getJeiRuntime().getJeiHelpers().getStackHelper())),
                "Explicit Basic recipe query did not start");
        int request = request();
        seed(menu.containerId, request, stacks, sources);
        return new Fixture(menu, request, recipe, views, info, errors);
    }

    private static void seed(int menuId, int request,
            List<ItemStack> stacks, List<Integer> sources) {
        check(stacks.size() == sources.size(), "Fixture source count does not match its stacks");
        int start = 0;
        int part = 0;
        do {
            int end = start;
            long bytes = 0;
            while (end < stacks.size() && end - start < 256) {
                long size = StackKey.of(stacks.get(end)).data().sizeInBytes() + 64L;
                check(size <= RequestLimits.MAX_SNAPSHOT_BYTES, "Fixture row exceeds its page budget: row=" + end + ", bytes=" + size);
                if (bytes + size > RequestLimits.MAX_SNAPSHOT_BYTES) break;
                bytes += size;
                end++;
            }
            boolean last = end == stacks.size();
            RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(menuId, request, part,
                    stacks.subList(start, end), sources.subList(start, end), last, false));
            start = end;
            part++;
        } while (start < stacks.size() && field(active(), "status") == RemoteInventory.Status.QUERYING);
    }

    private static void customCacheRechecksMachineRules(Minecraft client) throws ReflectiveOperationException {
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValues().stream()
                .filter(value -> value != Items.AIR && new ItemStack(value).getMaxStackSize() == 64
                        && client.player.getInventory().items.stream().noneMatch(stack -> stack.is(value)))
                .findFirst().orElseThrow();
        Fixture fixture = fixture(client, List.of(new ItemStack(item, 64)), List.of(1), List.of(new ItemStack(item)));
        IRecipeTransferError missing = new IRecipeTransferError() { public Type getType() { return Type.USER_FACING; } };
        IRecipeTransferError noEnergy = new IRecipeTransferError() { public Type getType() { return Type.USER_FACING; } };
        boolean[] energy = {true};
        var handler = new mezz.jei.api.recipe.transfer.IRecipeTransferHandler<AbstractContainerMenu, Object>() {
            public Class<? extends AbstractContainerMenu> getContainerClass() { return AbstractContainerMenu.class; }
            public java.util.Optional<net.minecraft.world.inventory.MenuType<AbstractContainerMenu>> getMenuType() { return java.util.Optional.empty(); }
            public mezz.jei.api.recipe.RecipeType<Object> getRecipeType() {
                return mezz.jei.api.recipe.RecipeType.create(StorageFill.ID, "energy_preview", Object.class);
            }
            public IRecipeTransferError transferRecipe(AbstractContainerMenu menu, Object recipe, IRecipeSlotsView views,
                    net.minecraft.world.entity.player.Player player, boolean maximum, boolean perform) {
                check(!perform, "Machine rule preview attempted a real transfer");
                if (player.getInventory().items.stream().noneMatch(stack -> stack.is(item))) return missing;
                return energy[0] ? null : noEnergy;
            }
        };
        mezz.jei.api.recipe.transfer.IRecipeTransferContext<Object, AbstractContainerMenu> context = proxy(
                mezz.jei.api.recipe.transfer.IRecipeTransferContext.class, (self, method, args) -> switch (method.getName()) {
                    case "getContainer" -> fixture.menu();
                    case "getRecipe" -> fixture.recipe();
                    case "getRecipeSlots" -> fixture.views();
                    case "getPlayer" -> client.player;
                    case "isMaxTransfer" -> false;
                    default -> objectMethod(self, method.getName(), args);
                });
        IRecipeTransferError ready = CustomTransfer.transfer(handler, context, false);
        check(ready != null && ready.getType().allowsTransfer, "Machine rule fixture did not prepare a cached remote proposal");
        int state = fixture.menu().getStateId();
        energy[0] = false;
        check(CustomTransfer.transfer(handler, context, false) == noEnergy && fixture.menu().getStateId() == state,
                "Cached proposal hid a changed machine rule without a menu state update");
        energy[0] = true;
        check(CustomTransfer.transfer(handler, context, false) == ready,
                "Restoring the machine rule did not reuse the cached material proposal");
        check(client.player.getInventory().items.stream().noneMatch(stack -> stack.is(item)),
                "Machine rule validation changed real player inventory");
    }

    private static void queryReplacementRejectsOldReplies(Minecraft client) throws ReflectiveOperationException {
        Fixture first = fixture(client, List.of(new ItemStack(Items.IRON_INGOT, 64)), List.of(1),
                List.of(new ItemStack(Items.IRON_INGOT)));
        check(first.transfer(client, Internal.getJeiRuntime().getJeiHelpers().getStackHelper()).handled(),
                "First recipe did not prepare a preview");
        int[] completed = {0};
        var pendingContext = proxy(mezz.jei.api.recipe.transfer.IRecipeTransferContext.class, (self, method, args) -> {
            if (method.getName().equals("completeRecipeTransfer")) {
                check(args[0] == mezz.jei.api.recipe.transfer.RecipeTransferResult.REJECTED,
                        "Replacing a query completed an old operation successfully");
                completed[0]++;
                return null;
            }
            return objectMethod(self, method.getName(), args);
        });
        setPending(ClientTransfer.class, new Object[]{1_000_001, first.menu(), client.screen, System.nanoTime(), pendingContext});
        setPending(CustomTransfer.class, new Object[]{1_000_002, first.menu(), client.screen, System.nanoTime(),
                pendingContext, (java.util.function.Supplier<Boolean>) () -> { throw new AssertionError("Old custom ACK replayed"); }, false});
        Object secondRecipe = new Object();
        IRecipeSlotsView secondViews = views(List.of(List.of(new ItemStack(Items.GOLD_INGOT))));
        check(RemoteInventory.query(first.menu(), secondRecipe, secondViews,
                () -> ClientTransfer.captureQuery(first.menu(), secondRecipe, secondViews, client.player, first.info(),
                        Internal.getJeiRuntime().getJeiHelpers().getStackHelper())), "Second recipe query did not start");
        int current = request();
        check(current > first.request() && view(first).request() < 0
                        && RemoteInventory.state(first.menu(), first.recipe(), first.views()).status() == RemoteInventory.Status.IDLE,
                "Querying a second recipe retained the first recipe result");
        check(completed[0] == 2 && !ClientTransfer.isPending() && !CustomTransfer.isPending(),
                "Replacing a query did not synchronously cancel both pending transfer types");
        ClientTransfer.accept(new FillNetwork.FillResult(first.menu().containerId, 1_000_001, true, Component.empty()));
        CustomTransfer.accept(new FillNetwork.TopUpResult(first.menu().containerId, 1_000_002, true));
        check(completed[0] == 2 && client.screen instanceof mezz.jei.gui.recipes.RecipesGui,
                "Old transfer ACK affected the new query or screen");
        check(((java.util.Map<?, ?>) staticField(ClientTransfer.class, "SIMULATIONS")).isEmpty()
                        && ((java.util.Map<?, ?>) staticField(CustomTransfer.class, "CACHE")).isEmpty(),
                "Switching recipe queries retained old preview caches");
        RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(first.menu().containerId, first.request(), 0,
                List.of(new ItemStack(Items.IRON_INGOT, 64)), List.of(1), true, false));
        check(RemoteInventory.state(first.menu(), secondRecipe, secondViews).status() == RemoteInventory.Status.QUERYING,
                "Old response changed the new recipe query");
        RemoteInventory.fail(first.menu().containerId, current, Component.literal("expected second recipe failure"));
        check(RemoteInventory.state(first.menu(), secondRecipe, secondViews).status() == RemoteInventory.Status.FAILED
                        && view(first).request() < 0 && field(active(), "evaluator") == null,
                "Failed second recipe restored the first result or retained its collector");
        RemoteInventory.invalidate();
        RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(first.menu().containerId, current, 0,
                List.of(new ItemStack(Items.GOLD_INGOT, 64)), List.of(1), true, false));
        check(active() == null, "Late query response recreated a closed session");
    }

    private static void unknownAndMissingRemainDistinct(Minecraft client) throws ReflectiveOperationException {
        Fixture fixture = fixture(client, List.of(), List.of(), List.of(new ItemStack(Items.CARROT)));
        query(client, fixture);
        int request = request();
        RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(fixture.menu().containerId, request, 0,
                List.of(new ItemStack(Items.STONE)), List.of(1), false, false));
        check(state(fixture) == RemoteInventory.Status.QUERYING, "Unfinished query was reported missing");
        RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(fixture.menu().containerId, request, 1,
                List.of(), List.of(), false, true));
        check(state(fixture) == RemoteInventory.Status.UNKNOWN, "Truncated query was reported missing");
        query(client, fixture);
        RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(fixture.menu().containerId, request(), 0,
                List.of(new ItemStack(Items.STONE)), List.of(1), true, false));
        check(state(fixture) == RemoteInventory.Status.MISSING, "Exhausted insufficient query was not missing");
    }

    private static void repeatedCandidateCountsDoNotAccumulate(Minecraft client) throws ReflectiveOperationException {
        Fixture fixture = fixture(client, List.of(), List.of(), List.of(new ItemStack(Items.CARROT, 64)));
        query(client, fixture);
        int request = request();
        for (int batch = 0; batch < 2; batch++) {
            RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(fixture.menu().containerId, request, batch,
                    List.of(new ItemStack(Items.CARROT, 32)), List.of(1), batch == 1, false));
            check(state(fixture) == (batch == 0 ? RemoteInventory.Status.QUERYING : RemoteInventory.Status.MISSING),
                    "Repeated current quantity accumulated into nonexistent inventory");
        }
    }

    private static void customMissingMaterialIsNotRuleRejection(Minecraft client) throws ReflectiveOperationException {
        List<net.minecraft.world.item.Item> absent = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValues().stream()
                .filter(item -> item != Items.AIR && new ItemStack(item).getMaxStackSize() == 64
                        && client.player.getInventory().items.stream().noneMatch(stack -> stack.is(item))).limit(2).toList();
        check(absent.size() == 2, "Custom query fixture needs two absent items");
        Fixture fixture = fixture(client, List.of(), List.of(), List.of(new ItemStack(absent.get(0))));
        IRecipeSlotsView views = views(absent.stream().map(item -> List.of(new ItemStack(item))).toList());
        boolean[] energy = {true};
        var handler = new mezz.jei.api.recipe.transfer.IRecipeTransferHandler<AbstractContainerMenu, Object>() {
            public Class<? extends AbstractContainerMenu> getContainerClass() { return AbstractContainerMenu.class; }
            public java.util.Optional<net.minecraft.world.inventory.MenuType<AbstractContainerMenu>> getMenuType() { return java.util.Optional.empty(); }
            public mezz.jei.api.recipe.RecipeType<Object> getRecipeType() {
                return mezz.jei.api.recipe.RecipeType.create(StorageFill.ID, "missing_query", Object.class);
            }
            public IRecipeTransferError transferRecipe(AbstractContainerMenu menu, Object recipe, IRecipeSlotsView slots,
                    net.minecraft.world.entity.player.Player player, boolean maximum, boolean perform) {
                check(!perform, "Query invoked a real custom transfer");
                boolean enough = absent.stream().allMatch(item -> player.getInventory().items.stream().anyMatch(stack -> stack.is(item)));
                return enough && energy[0] ? null : new IRecipeTransferError() { public Type getType() { return Type.USER_FACING; } };
            }
        };
        mezz.jei.api.recipe.transfer.IRecipeTransferContext<Object, AbstractContainerMenu> context = proxy(
                mezz.jei.api.recipe.transfer.IRecipeTransferContext.class, (self, method, args) -> switch (method.getName()) {
                    case "getContainer" -> fixture.menu();
                    case "getRecipe" -> fixture.recipe();
                    case "getRecipeSlots" -> views;
                    case "getPlayer" -> client.player;
                    case "isMaxTransfer" -> false;
                    default -> objectMethod(self, method.getName(), args);
                });
        check(RemoteInventory.query(fixture.menu(), fixture.recipe(), views,
                () -> CustomTransfer.transfer(handler, context, false)), "Custom missing query did not start");
        RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(fixture.menu().containerId, request(), 0,
                List.of(new ItemStack(absent.get(0), 64)), List.of(1), true, false));
        check(RemoteInventory.state(fixture.menu(), fixture.recipe(), views).status() == RemoteInventory.Status.MISSING,
                "Missing second custom material was reported as a native rule rejection");
        energy[0] = false;
        check(RemoteInventory.query(fixture.menu(), fixture.recipe(), views,
                () -> CustomTransfer.transfer(handler, context, false)), "Custom native-rule query did not start");
        RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(fixture.menu().containerId, request(), 0,
                absent.stream().map(item -> new ItemStack(item, 64)).toList(), List.of(1, 1), true, false));
        check(RemoteInventory.state(fixture.menu(), fixture.recipe(), views).status() == RemoteInventory.Status.UNKNOWN,
                "Native machine rule rejection was reported as missing inventory");
    }

    private static void querySearchBudgetIsShared(Minecraft client) throws ReflectiveOperationException {
        fixture(client, List.of(), List.of(), List.of(new ItemStack(Items.DIAMOND)));
        AbstractContainerMenu previous = client.player.containerMenu;
        var contents = new net.minecraft.world.SimpleContainer(40);
        AbstractContainerMenu menu = new AbstractContainerMenu(null, previous.containerId) {
            { for (int i = 0; i < 40; i++) addSlot(new Slot(contents, i, 0, 0)); }
            public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
            public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) { return ItemStack.EMPTY; }
        };
        contents.setItem(36, new ItemStack(Items.IRON_INGOT, 9));
        contents.setItem(37, new ItemStack(Items.GOLD_INGOT, 10));
        contents.setItem(38, new ItemStack(Items.COPPER_INGOT, 10));
        contents.setItem(39, new ItemStack(Items.DIAMOND, 20));
        List<List<ItemStack>> options = new ArrayList<>();
        for (int i = 0; i < 10; i++) options.add(List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)));
        for (int i = 0; i < 10; i++) options.add(List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.COPPER_INGOT)));
        for (int i = 0; i < 10; i++) options.add(List.of(new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.COPPER_INGOT)));
        for (int i = 0; i < 6; i++) options.add(List.of(new ItemStack(Items.DIAMOND)));
        IRecipeSlotsView views = views(options);
        Object recipe = new Object();
        IRecipeTransferInfo<AbstractContainerMenu, Object> info = proxy(IRecipeTransferInfo.class, (self, method, args) -> switch (method.getName()) {
            case "canHandle", "requireCompleteSets" -> true;
            case "getRecipeSlots" -> menu.slots.subList(0, 36);
            case "getInventorySlots" -> menu.slots.subList(36, 40);
            default -> objectMethod(self, method.getName(), args);
        });
        try {
            client.player.containerMenu = menu;
            check(RemoteInventory.query(menu, recipe, views, () -> ClientTransfer.captureQuery(menu, recipe, views,
                    client.player, info, Internal.getJeiRuntime().getJeiHelpers().getStackHelper())), "Budget query did not start");
            Object query = active();
            var evaluator = (RemoteInventory.Evaluator) field(query, "evaluator");
            int[] evaluations = {0};
            var evaluatorField = query.getClass().getDeclaredField("evaluator");
            evaluatorField.setAccessible(true);
            evaluatorField.set(query, new RemoteInventory.Evaluator() {
                public List<ItemStack> preferred() { return evaluator.preferred(); }
                public boolean accepts(StackKey key) { return evaluator.accepts(key); }
                public RemoteInventory.Selection select(RemoteInventory.View candidates) {
                    evaluations[0]++;
                    return evaluator.select(candidates);
                }
            });
            List<ItemStack> variants = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                ItemStack variant = new ItemStack(Items.DIAMOND);
                variant.getOrCreateTag().putInt("variant", i);
                variants.add(variant);
            }
            RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(menu.containerId, request(), 0,
                    variants, Collections.nCopies(16, 1), true, false));
            var budget = (dev.airun.storagefill.AllocationPlanner.SearchBudget) field(query, "budget");
            check(RemoteInventory.state(menu, recipe, views).status() == RemoteInventory.Status.UNKNOWN
                            && evaluations[0] == 1 && budget.remaining() == 0,
                    "Each candidate reset the search budget or exhausted search was reported missing: evaluations=" + evaluations[0]);
        } finally {
            RemoteInventory.invalidate();
            client.player.containerMenu = previous;
        }
    }

    private static IRecipeSlotsView views(List<List<ItemStack>> options) {
        List<IRecipeSlotView> inputs = new ArrayList<>();
        for (List<ItemStack> alternatives : options) {
            inputs.add(proxy(IRecipeSlotView.class, (self, method, args) -> switch (method.getName()) {
                case "getIngredients" -> args[0] == VanillaTypes.ITEM_STACK ? alternatives.stream() : Stream.empty();
                case "getRole" -> RecipeIngredientRole.INPUT;
                case "isEmpty" -> alternatives.isEmpty();
                default -> objectMethod(self, method.getName(), args);
            }));
        }
        return proxy(IRecipeSlotsView.class, (self, method, args) -> switch (method.getName()) {
            case "getSlotViews" -> inputs;
            default -> objectMethod(self, method.getName(), args);
        });
    }

    private static void query(Minecraft client, Fixture fixture) {
        check(RemoteInventory.query(fixture.menu(), fixture.recipe(), fixture.views(),
                () -> ClientTransfer.captureQuery(fixture.menu(), fixture.recipe(), fixture.views(), client.player,
                        fixture.info(), Internal.getJeiRuntime().getJeiHelpers().getStackHelper())), "Recipe query did not start");
    }

    private static RemoteInventory.Status state(Fixture fixture) {
        return RemoteInventory.state(fixture.menu(), fixture.recipe(), fixture.views()).status();
    }

    private static RemoteInventory.View view(Fixture fixture) {
        return RemoteInventory.get(fixture.menu(), fixture.recipe(), fixture.views(), List.of());
    }

    private static Object active() { return staticField(RemoteInventory.class, "active"); }
    private static int request() { return (int) field(active(), "request"); }

    private static Object staticField(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Object field(Object instance, String name) {
        try {
            var field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void setPending(Class<?> owner, Object[] values) throws ReflectiveOperationException {
        Class<?> type = Stream.of(owner.getDeclaredClasses()).filter(candidate -> candidate.getSimpleName().equals("Pending"))
                .findFirst().orElseThrow();
        var constructor = Stream.of(type.getDeclaredConstructors()).filter(candidate -> candidate.getParameterCount() == values.length)
                .findFirst().orElseThrow();
        constructor.setAccessible(true);
        var field = owner.getDeclaredField("pending");
        field.setAccessible(true);
        field.set(null, constructor.newInstance(values));
    }

    private static List<Component> tooltip(IRecipeTransferError notice, boolean shift) throws ReflectiveOperationException {
        JeiTooltip tooltip = new JeiTooltip();
        var method = notice.getClass().getDeclaredMethod("getTooltip", ITooltipBuilder.class, boolean.class);
        method.setAccessible(true);
        method.invoke(notice, tooltip, shift);
        return tooltip.toLegacyToComponents();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(ClientTransferRegressionClientTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object objectMethod(Object self, String name, Object[] args) {
        return switch (name) {
            case "toString" -> "ClientRegressionProxy";
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            default -> throw new UnsupportedOperationException(name);
        };
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(AbstractContainerMenu menu, int request, Object recipe, IRecipeSlotsView views,
                           IRecipeTransferInfo<AbstractContainerMenu, Object> info, IRecipeTransferHandlerHelper errors) {
        private ClientTransfer.Result transfer(Minecraft client, IStackHelper helper) {
            return ClientTransfer.transfer(menu, recipe, views, client.player, false, false, info, helper, errors, null);
        }
    }
}

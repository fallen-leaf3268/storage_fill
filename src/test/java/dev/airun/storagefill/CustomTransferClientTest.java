package dev.airun.storagefill;

import dev.airun.storagefill.client.CustomTransfer;
import dev.airun.storagefill.client.RemoteInventory;
import dev.airun.storagefill.storage.StorageSource;
import dev.airun.storagefill.storage.StorageSources;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.common.Internal;
import mezz.jei.common.transfer.RecipeTransferService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Mod.EventBusSubscriber(modid = StorageFill.ID, value = Dist.CLIENT)
public final class CustomTransferClientTest {
    private static final Object RECIPE = new Object();
    private static final RecipeType<Object> TYPE = RecipeType.create(StorageFill.ID, "custom_integration", Object.class);
    private static final IRecipeTransferError MISSING = new IRecipeTransferError() {
        public Type getType() { return Type.USER_FACING; }
    };
    private static long started;
    private static int stage;
    private static int simulated;
    private static int performed;
    private static int operation;
    private static int cancelledOperation;
    private static int menuId;
    private static int finalTicks;
    private static int cachedTicks;
    private static int cacheRequest = -1;
    private static long cacheRevision = -1;
    private static int jeiRequest = -1;
    private static long jeiRevision = -1;
    private static int jeiTicks;
    private static int lifecycleStage;
    private static int reopenedRequest;
    private static boolean reopened;
    private static int staleStage;
    private static int retryTicks;
    private static volatile boolean depleted;
    private static volatile boolean replenished;
    private static UUID playerId;
    private static volatile boolean serverReady;
    private static volatile boolean serverConsumed;
    private static volatile boolean cancellationConfirmed;
    private static boolean cancellationCheckRequested;
    private static volatile Throwable failure;
    private static TestScreen screen;
    private static IRecipeLayoutDrawable<Object> layout;
    private static IRecipeTransferManager manager;
    private static RecipeTransferService transferService;
    private static IRecipeTransferHandler<InventoryMenu, Object> standardHandler;
    private static int standardOperation;
    private static volatile boolean standardReady;
    private static int preparationTicks;
    private static volatile boolean preparationChanged;
    private static volatile boolean lifecycleChanged;
    private static boolean standardQueryStarted;
    private static boolean maximumRequested;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("storage_fill.integration") || stage == 14 || event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (started == 0) started = System.nanoTime();
        if (failure != null) throw new AssertionError("Custom integration server assertion failed at stage " + stage, failure);
        if (System.nanoTime() - started > 60_000_000_000L) {
            throw new AssertionError("Custom integration timed out at stage " + stage + ", simulations=" + simulated + ", executions=" + performed);
        }
        if (client.player == null || client.level == null || !client.hasSingleplayerServer()) return;
        if (stage == 0) {
            check(ModList.get().isLoaded("beyonddimensions"), "BD must be loaded for custom integration");
            playerId = client.player.getUUID();
            stage = 1;
            client.getSingleplayerServer().execute(() -> guarded(() -> ServerFixture.setup(client)));
            return;
        }
        if (!serverReady) return;
        if (stage == 1) {
            try {
                if (Internal.getJeiRuntime() == null) return;
            } catch (IllegalStateException notReady) {
                return;
            }
            if (client.getOverlay() != null || carrots(client.player) != 0) return;
            if (preparationTicks == 0) {
                CustomTransfer.clear();
                RemoteInventory.invalidate();
                client.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
            }
            if (preparationTicks == 20) {
                var ordinary = RemoteInventory.get(client.player.containerMenu, RECIPE, null, List.of(new ItemStack(Items.CARROT)));
                check(!hasSnapshotSession() && ordinary.request() < 0 && ordinary.inventory().isEmpty(),
                    "Opening an ordinary inventory activated the remote snapshot session");
                client.setScreen(null);
                client.getSingleplayerServer().execute(() -> guarded(() -> {
                    var player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                    check(StorageSources.find(player).get(0).insert(new ItemStack(Items.DIAMOND, 3)).isEmpty(),
                        "Could not insert the newly acquired preparation material");
                    preparationChanged = true;
                }));
            }
            if (++preparationTicks <= 24 || !preparationChanged) return;
            client.player.containerMenu = client.player.inventoryMenu;
            menuId = client.player.containerMenu.containerId;
            CustomTransfer.clear();
            screen = new TestScreen();
            client.setScreen(screen);
            openJei();
            fixtures();
            IRecipeTransferManager absent = proxy(IRecipeTransferManager.class, (p, m, a) -> {
                if (m.getName().equals("getRecipeTransferHandler")) return Optional.empty();
                if (m.getName().equals("getRecipeTransferListeners")) return List.of();
                return objectMethod(p, m.getName(), a);
            });
            var unavailable = new RecipeTransferService(absent).getTransferRecipeError(screen, layout, client.player);
            check(unavailable.isPresent() && unavailable.get().getType() == IRecipeTransferError.Type.INTERNAL,
                "Absent custom handler did not retain JEI INTERNAL result");
            check(simulated == 0 && performed == 0, "Absent custom handler invoked callback");
            check(carrots(client.player) == 0 && performed == 0, "Preparing inventory changed real items or invoked a transfer");
            transferService.getTransferRecipeError(screen, layout, client.player);
            check(!hasSnapshotSession() && queryState() == RemoteInventory.Status.IDLE,
                "Browsing JEI started a query without pressing the query button");
            queryCurrent();
            StorageFill.LOGGER.info("STORAGE_FILL_ORDINARY_CONTAINER_IDLE_PASSED");
            stage = 2;
            return;
        }
        if (stage == 2) {
            check(client.screen instanceof mezz.jei.gui.recipes.RecipesGui && screen.closed == 0,
                "Query unexpectedly closed the JEI recipe screen or its parent");
            var notice = transferService.getTransferRecipeError(screen, layout, client.player);
            check(carrots(client.player) == 0 && performed == 0, "Remote preview modified actual player inventory or executed handler");
            var currentView = currentView();
            if (staleStage == 2) {
                if (pendingOperationOrZero() != 0) return;
                check(performed == 0 && carrots(client.player) == 0 && screen.closed == 0,
                    "Failed stale fill changed inventory, replayed handler or closed the screen");
                checkMissingChat(client);
                checkSnapshot(currentView, cacheRequest, cacheRevision, 16,
                    "Failed fill changed the frozen inventory snapshot");
                client.getSingleplayerServer().execute(() -> guarded(() -> ServerFixture.replenish(client)));
                staleStage = 3;
                return;
            }
            if (notice.isEmpty() || !notice.get().getType().allowsTransfer) return;
            var tooltip = new mezz.jei.common.gui.JeiTooltip();
            notice.get().getTooltip(tooltip);
            check(tooltip.toLegacyToComponents().size() >= 2, "Remote preview did not expose source tooltip");
            check(simulated > 0, "Custom handler was not consulted during JEI preview");
            var view = currentView();
            if (cacheRequest < 0) cacheRequest = view.request();
            if (cacheRevision < 0) {
                cacheRevision = view.revision();
                check(count(view, Items.DIAMOND) == 0,
                    "A recipe query retained unrelated storage materials");
                StorageFill.LOGGER.info("STORAGE_FILL_FIRST_PREVIEW_READY_PASSED");
            }
            checkSnapshot(view, cacheRequest, cacheRevision, 16, "Session polling replaced or changed the initial snapshot");
            var absentMaterial = RemoteInventory.get(client.player.containerMenu, new Object(), layout.getRecipeSlotsView(),
                List.of(new ItemStack(Items.NETHER_STAR)));
            check(absentMaterial.request() < 0 && absentMaterial.stacks().isEmpty(),
                "Another recipe reused the current recipe's result");
            if (++cachedTicks < 70) return;
            if (staleStage == 0) {
                client.getSingleplayerServer().execute(() -> guarded(() -> ServerFixture.deplete(client)));
                staleStage = 1;
                return;
            }
            if (staleStage == 1) {
                if (!depleted) return;
                checkSnapshot(view, cacheRequest, cacheRevision, 16,
                    "Remote inventory changes updated an open JEI snapshot automatically");
                check(!transferService.transferRecipe(screen, layout, client.player, false),
                    "Stale preview executed the handler before server validation");
                check(pendingOperation() > 0, "Stale snapshot did not issue a fill request");
                staleStage = 2;
                return;
            }
            if (staleStage == 3) {
                if (!replenished || ++retryTicks < 5) return;
                checkSnapshot(view, cacheRequest, cacheRevision, 16,
                    "Replenishing storage updated the frozen JEI snapshot");
                staleStage = 4;
                StorageFill.LOGGER.info("STORAGE_FILL_STALE_SNAPSHOT_MISSING_REASON_PASSED");
            }
            StorageFill.LOGGER.info("STORAGE_FILL_SESSION_REUSE_INTEGRATION_PASSED");
            boolean immediate = transferService.transferRecipe(screen, layout, client.player, false);
            check(!immediate && performed == 0 && screen.closed == 0, "Pending transfer executed or closed screen before ACK");
            operation = pendingOperation();
            check(operation > 0, "Pending transfer operation was not created");
            var pending = transferService.getTransferRecipeError(screen, layout, client.player);
            check(pending.isPresent() && !pending.get().getType().allowsTransfer, "Pending query did not block duplicate click");
            stage = 3;
            return;
        }
        if (stage == 3) {
            if (!serverConsumed || carrots(client.player) != 0) return;
            check(performed == 1, "ACK did not invoke original custom handler exactly once");
            check(screen.closed == 0 && client.screen == screen,
                "Successful custom transfer closed the container parent or failed to close JEI");
            CustomTransfer.accept(new FillNetwork.TopUpResult(menuId, operation, true));
            CustomTransfer.accept(new FillNetwork.TopUpResult(menuId, operation, true));
            check(performed == 1 && screen.closed == 0 && client.screen == screen,
                "Duplicate ACK replayed original handler or closed the container parent");
            stage = 4;
            return;
        }
        if (stage == 4 && ++finalTicks >= 10) {
            check(performed == 1 && screen.closed == 0 && client.screen == screen && carrots(client.player) == 0,
                "Custom transfer did not remain exactly once");
            StorageFill.LOGGER.info("STORAGE_FILL_CUSTOM_INTEGRATION_PASSED");
            CustomTransfer.clear();
            RemoteInventory.invalidate();
            screen = new TestScreen();
            client.setScreen(screen);
            openJei();
            queryCurrent();
            finalTicks = 0;
            stage = 5;
            return;
        }
        if (stage == 5) {
            check(performed == 1 && carrots(client.player) == 0 && client.screen instanceof mezz.jei.gui.recipes.RecipesGui,
                "Cancellation fixture did not begin with empty inventory and a fresh screen");
            var notice = transferService.getTransferRecipeError(screen, layout, client.player);
            if (notice.isEmpty() || !notice.get().getType().allowsTransfer) return;
            var remote = currentView();
            check(remote.stacks().stream().filter(stack -> stack.is(Items.CARROT)).mapToInt(ItemStack::getCount).sum() == 15,
                "Cancellation fixture did not begin with fifteen remote carrots");
            boolean immediate = transferService.transferRecipe(screen, layout, client.player, false);
            check(!immediate && performed == 1 && screen.closed == 0, "Cancellation request replayed handler before ACK");
            cancelledOperation = pendingOperation();
            check(cancelledOperation > operation, "Cancellation request did not get a new operation id");
            client.screen.onClose();
            check(screen.closed == 0 && client.screen == screen, "Cancelling JEI also closed its container parent");
            stage = 6;
            return;
        }
        if (stage == 6) {
            check(performed == 1 && screen.closed == 0 && client.screen == screen,
                "ACK replayed handler or closed the parent after recipe screen closed");
            if (carrots(client.player) != 1) return;
            if (!cancellationCheckRequested) {
                cancellationCheckRequested = true;
                client.getSingleplayerServer().execute(() -> guarded(() -> ServerFixture.confirmCancellation(client)));
                return;
            }
            if (!cancellationConfirmed || ++finalTicks < 10) return;
            CustomTransfer.accept(new FillNetwork.TopUpResult(menuId, cancelledOperation, true));
            CustomTransfer.accept(new FillNetwork.TopUpResult(menuId, cancelledOperation, true));
            check(performed == 1 && screen.closed == 0 && client.screen == screen && carrots(client.player) == 1,
                "Duplicate cancelled ACK replayed handler or removed retained material");
            finalTicks = 0;
            stage = 7;
            return;
        }
        if (stage == 7 && ++finalTicks >= 10) {
            check(performed == 1 && screen.closed == 0 && client.screen == screen && carrots(client.player) == 1,
                "Cancelled transfer did not retain material without replay");
            stage = 8;
            StorageFill.LOGGER.info("STORAGE_FILL_CUSTOM_CANCELLATION_INTEGRATION_PASSED");
            openJei();
            lifecycleFixtures();
            queryCurrent();
            return;
        }
        if (stage == 8) {
            check(client.screen instanceof mezz.jei.gui.recipes.RecipesGui, "Actual JEI recipe screen did not open");
            var view = currentView();
            if (view.request() < 0) return;
            if (jeiRequest < 0) {
                jeiRequest = view.request();
                jeiRevision = view.revision();
                openJei();
                client.getSingleplayerServer().execute(() -> guarded(() -> ServerFixture.addDiamonds(client)));
            }
            check(view.request() == jeiRequest && view.revision() == jeiRevision,
                "Actual JEI screen refreshed during one open session");
            var diamonds = currentView();
            check(count(diamonds, Items.DIAMOND) == 3, "Open JEI snapshot received a later storage mutation");
            if (++jeiTicks < 70 || !lifecycleChanged) return;
            var cameraKey = new net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre(client.screen,
                org.lwjgl.glfw.GLFW.GLFW_KEY_F5, 0, 0);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(cameraKey);
            check(!cameraKey.isCanceled(), "Remote inventory intercepted the normal F5 key");
            var unchanged = currentView();
            check(unchanged.request() == jeiRequest && unchanged.revision() == jeiRevision
                && count(unchanged, Items.DIAMOND) == 3, "F5 changed the frozen JEI inventory snapshot");
            client.screen.onClose();
            check(client.screen == screen && screen.closed == 0, "Closing JEI closed its container parent");
            check(currentView().request() < 0,
                "Closing JEI retained or reactivated a remote inventory session");
            check(!hasSnapshotSession(), "Closing JEI did not release its inventory snapshot");
            openJei();
            check(queryState() == RemoteInventory.Status.IDLE, "Reopening JEI queried storage automatically");
            queryCurrent();
            stage = 9;
            return;
        }
        if (stage == 9) {
            if (lifecycleStage == 0) {
                var reopenedView = currentView();
                if (reopenedView.request() < 0) return;
                check(reopenedView.request() > jeiRequest && count(reopenedView, Items.DIAMOND) == 5,
                    "Reopening JEI did not replace the request and obtain current stock");
                reopenedRequest = reopenedView.request();
                RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(menuId, jeiRequest, 0,
                    List.of(new ItemStack(Items.DIAMOND, 64)), List.of(1), true, false));
                check(currentView().request() == reopenedRequest && count(currentView(), Items.DIAMOND) == 5,
                    "A delayed snapshot from before reopening JEI replaced the new snapshot");
                lifecycleChanged = false;
                client.getSingleplayerServer().execute(() -> guarded(() -> ServerFixture.addDiamonds(client)));
                lifecycleStage = 1;
                return;
            }
            if (lifecycleStage == 1) {
                if (!lifecycleChanged) return;
                client.screen.onClose();
                check(client.screen == screen && screen.closed == 0, "Closing JEI closed its container parent");
                RemoteInventory.acceptRecipe(new FillNetwork.RecipeQueryBatch(menuId, reopenedRequest, 0,
                    List.of(new ItemStack(Items.DIAMOND, 64)), List.of(1), true, false));
                check(currentView().request() < 0,
                    "Closing JEI retained or reactivated a remote inventory session");
                lifecycleStage = 2;
                return;
            }
            if (!reopened) {
                reopened = true;
                openJei();
                check(queryState() == RemoteInventory.Status.IDLE, "Reopening JEI queried storage automatically");
                queryCurrent();
                return;
            }
            var view = currentView();
            if (view.request() < 0) return;
            check(view.request() > reopenedRequest, "Closing and reopening JEI reused the obsolete inventory request");
            check(count(view, Items.DIAMOND) == 7,
                "Reopened JEI did not load storage changes from the previous session");
            RemoteInventory.fail(menuId, view.request(), Component.literal("BACKGROUND_FAILURE_MUST_BE_SILENT"));
            try {
                var field = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");
                field.setAccessible(true);
                for (Object message : (List<?>) field.get(client.gui.getChat())) {
                    check(!message.toString().contains("BACKGROUND_FAILURE_MUST_BE_SILENT"), "Background inventory failure showed a chat message");
                }
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            client.screen.onClose();
            stage = 10;
            StorageFill.LOGGER.info("STORAGE_FILL_REAL_JEI_SESSION_LIFECYCLE_PASSED");
            screen = new TestScreen();
            client.setScreen(screen);
            fixtures();
            check(!dev.airun.storagefill.client.ClientTransfer.<Object, InventoryMenu>deferMaximum(
                client.player.inventoryMenu, layout.getRecipeSlotsView(), context -> {
                    throw new AssertionError("An ordinary container resumed a deferred maximum fill");
                }, null) && !dev.airun.storagefill.client.ClientTransfer.isPending(),
                "An ordinary container deferred maximum fill while no JEI snapshot session was active");
            var stackHelper = Internal.getJeiRuntime().getJeiHelpers().getStackHelper();
            var craftingCategory = (mezz.jei.library.plugins.vanilla.crafting.CraftingRecipeCategory)
                    Internal.getJeiRuntime().getRecipeManager().getRecipeCategory(mezz.jei.api.constants.RecipeTypes.CRAFTING);
            var helper = new mezz.jei.library.transfer.RecipeTransferHandlerHelper(stackHelper, craftingCategory);
            standardHandler = helper.createUnregisteredRecipeTransferHandler(helper.createBasicRecipeTransferInfo(
                InventoryMenu.class, null, TYPE, 1, 4, 9, 36));
            transferService = serviceFor(standardHandler);
            openJei();
            client.getSingleplayerServer().execute(() -> guarded(() -> ServerFixture.prepareStandard(client)));
            return;
        }
        if (stage == 10) {
            if (!standardReady || carrots(client.player) != 0) return;
            if (!standardQueryStarted) {
                standardQueryStarted = true;
                check(queryState() == RemoteInventory.Status.IDLE, "Standard fixture queried automatically");
                queryCurrent();
                return;
            }
            var result = transferService.getTransferRecipeError(screen, layout, client.player);
            if (result.isEmpty() || !result.get().getType().allowsTransfer) return;
            check(!transferService.transferRecipe(screen, layout, client.player, false) && screen.closed == 0,
                "Standard fill closed before server acknowledgement");
            standardOperation = standardPendingOperation();
            stage = 11;
            return;
        }
        if (stage == 11) {
            if (dev.airun.storagefill.client.ClientTransfer.isPending()) return;
            check(screen.closed == 0 && client.screen == screen && client.player.inventoryMenu.getSlot(1).getItem().getCount() == 1,
                "Standard acknowledged fill did not place one item or closed the container parent");
            dev.airun.storagefill.client.ClientTransfer.accept(new FillNetwork.FillResult(menuId, standardOperation, true, Component.empty()));
            check(screen.closed == 0 && client.screen == screen, "Duplicate standard ACK closed another screen");
            RemoteInventory.invalidate();
            screen = new TestScreen();
            client.setScreen(screen);
            openJei();
            var local = transferService.getTransferRecipeError(screen, layout, client.player);
            check(local.isEmpty() || local.get().getType().allowsTransfer, "Local ingredients stopped working without remote cache");
            check(queryState() == RemoteInventory.Status.IDLE && !dev.airun.storagefill.client.ClientTransfer.isPending(),
                "Unqueried local preview started a remote fill");
            queryCurrent();
            stage = 12;
            return;
        }
        if (stage == 12) {
            if (!maximumRequested) {
                if (queryState() != RemoteInventory.Status.READY) return;
                maximumRequested = true;
                check(!transferService.transferRecipe(screen, layout, client.player, true) && screen.closed == 0,
                    "Queried maximum fill did not wait for server acknowledgement");
                return;
            }
            if (dev.airun.storagefill.client.ClientTransfer.isPending()) return;
            check(screen.closed == 0 && client.screen == screen && client.player.inventoryMenu.getSlot(1).getItem().getCount() == 14,
                "Maximum fill failed to combine the existing input with thirteen remote items or closed the container parent");
            stage = 13;
            client.getSingleplayerServer().execute(() -> guarded(() -> {
                var player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                check(ServerFixture.remoteCarrots(StorageSources.find(player).get(0)) == 0
                    && player.inventoryMenu.getSlot(1).getItem().getCount() == 14, "Maximum fill broke conservation");
                standardReady = false;
            }));
            return;
        }
        if (stage == 13 && !standardReady) {
            stage = 14;
            StorageFill.LOGGER.info("STORAGE_FILL_STANDARD_ACK_AND_MAXIMUM_PASSED");
            dev.airun.storagefill.client.ClientTransferRegressionClientTest.run(client);
            client.stop();
        }
    }

    private static int standardPendingOperation() {
        try {
            var field = dev.airun.storagefill.client.ClientTransfer.class.getDeclaredField("pending");
            field.setAccessible(true);
            Object pending = field.get(null);
            check(pending != null, "Standard operation was not pending");
            var method = pending.getClass().getDeclaredMethod("operation");
            method.setAccessible(true);
            return (int) method.invoke(pending);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void openJei() {
        Internal.getJeiRuntime().getRecipesGui().showTypes(List.of(mezz.jei.api.constants.RecipeTypes.CRAFTING));
        check(Minecraft.getInstance().screen instanceof mezz.jei.gui.recipes.RecipesGui,
            "Opening the real JEI recipe interface did not replace the container screen");
    }

    private static RemoteInventory.View currentView() {
        return RemoteInventory.get(Minecraft.getInstance().player.containerMenu, RECIPE,
            layout.getRecipeSlotsView(), List.of());
    }

    private static RemoteInventory.Status queryState() {
        return RemoteInventory.state(Minecraft.getInstance().player.containerMenu, RECIPE,
            layout.getRecipeSlotsView()).status();
    }

    private static void queryCurrent() {
        Minecraft client = Minecraft.getInstance();
        check(RemoteInventory.query(client.player.containerMenu, RECIPE, layout.getRecipeSlotsView(),
            () -> transferService.getTransferRecipeError(screen, layout, client.player)),
            "Explicit recipe query failed to start: " + queryState());
    }

    private static void lifecycleFixtures() {
        var ingredient = Internal.getJeiRuntime().getIngredientManager()
            .createTypedIngredient(VanillaTypes.ITEM_STACK, new ItemStack(Items.DIAMOND)).orElseThrow();
        IRecipeSlotView slot = proxy(IRecipeSlotView.class, (p, m, a) -> switch (m.getName()) {
            case "getAllIngredientsList" -> List.of(ingredient);
            case "getAllIngredients" -> Stream.of(ingredient);
            case "getIngredients" -> a[0] == VanillaTypes.ITEM_STACK ? Stream.of(new ItemStack(Items.DIAMOND)) : Stream.empty();
            case "getItemStacks" -> Stream.of(new ItemStack(Items.DIAMOND));
            case "getRole" -> RecipeIngredientRole.INPUT;
            case "isEmpty" -> false;
            default -> objectMethod(p, m.getName(), a);
        });
        IRecipeSlotsView views = proxy(IRecipeSlotsView.class, (p, m, a) -> switch (m.getName()) {
            case "getSlotViews" -> a == null || a.length == 0 || a[0] == RecipeIngredientRole.INPUT ? List.of(slot) : List.of();
            default -> objectMethod(p, m.getName(), a);
        });
        IRecipeCategory<Object> category = proxy(IRecipeCategory.class, (p, m, a) -> switch (m.getName()) {
            case "getRecipeType" -> TYPE;
            case "getTitle" -> Component.literal("Recipe query lifecycle fixture");
            default -> objectMethod(p, m.getName(), a);
        });
        layout = proxy(IRecipeLayoutDrawable.class, (p, m, a) -> switch (m.getName()) {
            case "getRecipeCategory" -> category;
            case "getRecipeSlotsView" -> views;
            case "getRecipe" -> RECIPE;
            default -> objectMethod(p, m.getName(), a);
        });
        transferService = serviceFor(new IRecipeTransferHandler<InventoryMenu, Object>() {
            public Class<? extends InventoryMenu> getContainerClass() { return InventoryMenu.class; }
            public Optional<MenuType<InventoryMenu>> getMenuType() { return Optional.empty(); }
            public RecipeType<Object> getRecipeType() { return TYPE; }
            public IRecipeTransferError transferRecipe(InventoryMenu menu, Object recipe, IRecipeSlotsView slots,
                    Player player, boolean maximum, boolean perform) {
                check(!perform, "Lifecycle query attempted a real transfer");
                return player.getInventory().items.stream().anyMatch(stack -> stack.is(Items.DIAMOND)) ? null : MISSING;
            }
        });
    }

    private static boolean hasSnapshotSession() {
        try {
            var field = RemoteInventory.class.getDeclaredField("active");
            field.setAccessible(true);
            return field.get(null) != null;
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect the remote snapshot session", error);
        }
    }

    private static int count(RemoteInventory.View view, Item item) {
        return view.inventory().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static void checkSnapshot(RemoteInventory.View view, int request, long revision, int carrots, String message) {
        check(view.request() == request && view.revision() == revision && count(view, Items.CARROT) == carrots, message);
    }

    private static void fixtures() {
        var ingredient = Internal.getJeiRuntime().getIngredientManager()
            .createTypedIngredient(VanillaTypes.ITEM_STACK, new ItemStack(Items.CARROT)).orElseThrow();
        IRecipeSlotView slot = proxy(IRecipeSlotView.class, (p, m, a) -> switch (m.getName()) {
            case "getAllIngredientsList" -> List.of(ingredient);
            case "getAllIngredients" -> Stream.of(ingredient);
            case "getIngredients" -> a[0] == VanillaTypes.ITEM_STACK ? Stream.of(new ItemStack(Items.CARROT)) : Stream.empty();
            case "getItemStacks" -> Stream.of(new ItemStack(Items.CARROT));
            case "getRole" -> RecipeIngredientRole.INPUT;
            case "isEmpty" -> false;
            case "getSlotName" -> Optional.of("ingredient");
            default -> objectMethod(p, m.getName(), a);
        });
        IRecipeSlotsView views = proxy(IRecipeSlotsView.class, (p, m, a) -> switch (m.getName()) {
            case "getSlotViews" -> a == null || a.length == 0 || a[0] == RecipeIngredientRole.INPUT ? List.of(slot) : List.of();
            case "findSlotByName" -> Optional.of(slot);
            default -> objectMethod(p, m.getName(), a);
        });
        IRecipeCategory<Object> category = proxy(IRecipeCategory.class, (p, m, a) -> switch (m.getName()) {
            case "getRecipeType" -> TYPE;
            case "getTitle" -> Component.literal("Custom integration fixture");
            default -> objectMethod(p, m.getName(), a);
        });
        layout = proxy(IRecipeLayoutDrawable.class, (p, m, a) -> switch (m.getName()) {
            case "getRecipeCategory" -> category;
            case "getRecipeSlotsView" -> views;
            case "getRecipe" -> RECIPE;
            default -> objectMethod(p, m.getName(), a);
        });
        IRecipeTransferHandler<InventoryMenu, Object> handler = new IRecipeTransferHandler<>() {
            public Class<? extends InventoryMenu> getContainerClass() { return InventoryMenu.class; }
            public Optional<MenuType<InventoryMenu>> getMenuType() { return Optional.empty(); }
            public RecipeType<Object> getRecipeType() { return TYPE; }
            public IRecipeTransferError transferRecipe(InventoryMenu menu, Object recipe, IRecipeSlotsView slots,
                    Player player, boolean maximum, boolean perform) {
                check(recipe == RECIPE && slots == views, "JEI altered custom handler recipe context");
                if (!perform) {
                    simulated++;
                    return carrots(player) >= 1 ? null : MISSING;
                }
                check(carrots(player) == 1, "ACK replay did not see one synchronized carrot");
                performed++;
                check(performed == 1, "Original custom handler executed more than once");
                Minecraft client = Minecraft.getInstance();
                client.getSingleplayerServer().execute(() -> guarded(() -> ServerFixture.consume(client)));
                return null;
            }
        };
        manager = proxy(IRecipeTransferManager.class, (p, m, a) -> {
            if (m.getName().equals("getRecipeTransferHandler")) {
                check(a[0] instanceof InventoryMenu && a[1] == category, "JEI resolved handler against wrong menu/category");
                return Optional.of(handler);
            }
            if (m.getName().equals("getRecipeTransferListeners")) return List.of();
            return objectMethod(p, m.getName(), a);
        });
        transferService = new RecipeTransferService(manager);
    }

    private static RecipeTransferService serviceFor(IRecipeTransferHandler<InventoryMenu, Object> handler) {
        IRecipeTransferManager transferManager = proxy(IRecipeTransferManager.class, (p, m, a) -> {
            if (m.getName().equals("getRecipeTransferHandler")) return Optional.of(handler);
            if (m.getName().equals("getRecipeTransferListeners")) return List.of();
            return objectMethod(p, m.getName(), a);
        });
        return new RecipeTransferService(transferManager);
    }

    private static int pendingOperation() {
        int operation = pendingOperationOrZero();
        check(operation > 0, "Missing pending custom transfer");
        return operation;
    }

    private static int pendingOperationOrZero() {
        try {
            var field = CustomTransfer.class.getDeclaredField("pending");
            field.setAccessible(true);
            Object pending = field.get(null);
            if (pending == null) return 0;
            var operation = pending.getClass().getDeclaredMethod("operation");
            operation.setAccessible(true);
            return (int) operation.invoke(pending);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect pending custom transfer", error);
        }
    }

    private static void checkMissingChat(Minecraft client) {
        try {
            var field = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");
            field.setAccessible(true);
            var messages = (List<net.minecraft.client.GuiMessage>) field.get(client.gui.getChat());
            boolean found = messages.stream().anyMatch(message -> {
                if (!(message.content().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text)
                        || !text.getKey().equals("storage_fill.failed.missing")) return false;
                if (text.getArgs().length != 1 || !(text.getArgs()[0] instanceof Component items)) return false;
                return items.getString().contains(new ItemStack(Items.CARROT).getHoverName().getString() + " × 1");
            });
            check(found, "Server's exact missing material and count did not reach the chat display");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect rendered failure message", error);
        }
    }

    private static int carrots(Player player) {
        return player.getInventory().items.stream().filter(stack -> stack.is(Items.CARROT)).mapToInt(ItemStack::getCount).sum();
    }

    private static void guarded(Runnable action) {
        try { action.run(); }
        catch (Throwable error) { failure = error; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(CustomTransferClientTest.class.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object objectMethod(Object proxy, String name, Object[] arguments) {
        return switch (name) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "custom-integration-proxy";
            default -> throw new UnsupportedOperationException("Unexpected fixture method " + name);
        };
    }

    private static final class TestScreen extends AbstractContainerScreen<InventoryMenu> {
        private int closed;
        private TestScreen() {
            super(Minecraft.getInstance().player.inventoryMenu, Minecraft.getInstance().player.getInventory(),
                    Component.literal("Custom integration"));
        }
        protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) { }
        public boolean isPauseScreen() { return false; }
        public void onClose() {
            closed++;
            Minecraft.getInstance().setScreen(null);
        }
    }

    private static final class ServerFixture {
        private static void addDiamonds(Minecraft client) {
            ServerPlayer player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            check(StorageSources.find(player).get(0).insert(new ItemStack(Items.DIAMOND, 2)).isEmpty(),
                "Could not mutate storage for snapshot reopening validation");
            lifecycleChanged = true;
        }

        private static void prepareStandard(Minecraft client) {
            ServerPlayer player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            player.getInventory().clearContent();
            for (int i = 1; i <= 4; i++) player.inventoryMenu.getSlot(i).set(ItemStack.EMPTY);
            player.inventoryMenu.broadcastChanges();
            player.inventoryMenu.sendAllDataToRemote();
            standardReady = true;
        }
        private static void deplete(Minecraft client) {
            ServerPlayer player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            StorageSource source = StorageSources.find(player).get(0);
            check(source.extract(new ItemStack(Items.CARROT), 16, false).getCount() == 16 && remoteCarrots(source) == 0,
                "Could not change real storage after taking the client snapshot");
            depleted = true;
        }

        private static void replenish(Minecraft client) {
            ServerPlayer player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            StorageSource source = StorageSources.find(player).get(0);
            check(source.insert(new ItemStack(Items.CARROT, 16)).isEmpty() && remoteCarrots(source) == 16,
                "Could not restore real storage for retry with the same snapshot");
            replenished = true;
        }

        private static void setup(Minecraft client) {
            ServerPlayer player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            check(player != null, "Integrated server player is missing");
            player.getInventory().clearContent();
            player.containerMenu = player.inventoryMenu;
            com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.clearPrimaryNetForPlayer(player);
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.createNewNetForPlayer(player, 10000L, 64);
            check(network != null, "Cannot create integration BD network");
            StorageSource source = StorageSources.find(player).get(0);
            check(source.insert(new ItemStack(Items.CARROT, 16)).isEmpty(), "Cannot seed integration BD network");
            check(remoteCarrots(source) == 16, "Integration BD fixture count is not sixteen");
            player.inventoryMenu.broadcastChanges();
            player.inventoryMenu.sendAllDataToRemote();
            serverReady = true;
        }

        private static void consume(Minecraft client) {
            ServerPlayer player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            check(player != null && carrots(player) == 1, "Server did not receive exactly one top-up carrot");
            StorageSource source = StorageSources.find(player).get(0);
            check(remoteCarrots(source) == 15, "BD did not retain exactly fifteen carrots after top-up");
            for (ItemStack stack : player.getInventory().items) {
                if (stack.is(Items.CARROT)) { stack.shrink(1); break; }
            }
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            check(carrots(player) == 0 && remoteCarrots(source) == 15, "Simulated original handler consumption broke conservation");
            serverConsumed = true;
        }

        private static void confirmCancellation(Minecraft client) {
            ServerPlayer player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            check(player != null && carrots(player) == 1, "Cancelled transfer did not retain exactly one carrot on server");
            StorageSource source = StorageSources.find(player).get(0);
            check(remoteCarrots(source) == 14, "Cancelled transfer did not leave exactly fourteen carrots in BD");
            cancellationConfirmed = true;
        }

        private static int remoteCarrots(StorageSource source) {
            return source.snapshot(Set.of(Items.CARROT)).stream().filter(stack -> stack.is(Items.CARROT)).mapToInt(ItemStack::getCount).sum();
        }
    }
}

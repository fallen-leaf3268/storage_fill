package dev.airun.storagefill.client;

import dev.airun.storagefill.mixin.RecipeTransferButtonAccessor;
import dev.airun.storagefill.storage.StorageSource;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.gui.recipes.RecipeTransferButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.List;

public final class RecipeQueryButton implements IIconButtonController {
    private static final ResourceLocation QUERY_ICON = new ResourceLocation("storage_fill", "textures/gui/recipe_query.png");
    private final IRecipeLayoutDrawable<?> layout;
    private final RecipeTransferButton transfer;
    private final IDrawable icon = new IDrawable() {
        public int getWidth() { return 8; }
        public int getHeight() { return 8; }
        public void draw(GuiGraphics graphics, int x, int y) {
            graphics.blit(QUERY_ICON, x, y, 8, 8, 0.0F, 0.0F, 16, 16, 16, 16);
        }
    };

    public RecipeQueryButton(IRecipeLayoutDrawable<?> layout, RecipeTransferButton transfer) {
        this.layout = layout;
        this.transfer = transfer;
    }

    private AbstractContainerScreen<?> parent() {
        return ((RecipeTransferButtonAccessor) transfer).storageFillParentScreenSupplier().get();
    }

    private RemoteInventory.State state() {
        AbstractContainerScreen<?> parent = parent();
        return parent == null ? new RemoteInventory.State(RemoteInventory.Status.IDLE, null)
            : RemoteInventory.state(parent.getMenu(), layout.getRecipe(), layout.getRecipeSlotsView());
    }

    @Override
    public boolean onPress(IJeiUserInput input) {
        if (input.isSimulate()) return true;
        AbstractContainerScreen<?> parent = parent();
        var player = Minecraft.getInstance().player;
        if (parent == null || player == null) return false;
        var service = ((RecipeTransferButtonAccessor) transfer).storageFillRecipeTransferService();
        RemoteInventory.query(parent.getMenu(), layout.getRecipe(), layout.getRecipeSlotsView(),
            () -> service.getTransferRecipeError(parent, layout, player));
        return true;
    }

    @Override
    public void initState(IButtonState button) {
        button.setIcon(icon);
        updateState(button);
    }

    @Override
    public void updateState(IButtonState button) {
        boolean available = parent() != null && Minecraft.getInstance().player != null;
        button.setVisible(available);
        button.setActive(available && state().status() != RemoteInventory.Status.QUERYING);
    }

    @Override
    public void getTooltips(ITooltipBuilder tooltip) {
        RemoteInventory.State state = state();
        if (state.status() == RemoteInventory.Status.IDLE) {
            tooltip.add(Component.translatable("storage_fill.query.action"));
            tooltip.add(Component.translatable("storage_fill.query.idle").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (state.status() == RemoteInventory.Status.READY) {
            tooltip.add(Component.translatable("storage_fill.query.sources", materialSources()));
            return;
        }
        if (state.status() == RemoteInventory.Status.MISSING) {
            tooltip.add(Component.translatable("storage_fill.query.missing").withStyle(ChatFormatting.RED));
            return;
        }
        String key = switch (state.status()) {
            case IDLE -> "idle";
            case QUERYING -> "querying";
            case READY -> "ready";
            case MISSING -> "missing";
            case UNKNOWN -> "unknown";
            case FAILED -> "failed";
        };
        tooltip.add(Component.translatable("storage_fill.query." + key).withStyle(switch (state.status()) {
            case READY -> ChatFormatting.GREEN;
            case MISSING, FAILED -> ChatFormatting.RED;
            case QUERYING, UNKNOWN -> ChatFormatting.YELLOW;
            case IDLE -> ChatFormatting.GRAY;
        }));
        if (state.reason() != null && !state.reason().getString().isEmpty()) tooltip.add(state.reason());
    }

    private Component materialSources() {
        var parent = parent();
        var names = Component.empty();
        if (parent == null) return names;
        var sources = new LinkedHashSet<Integer>();
        sources.addAll(RemoteInventory.get(parent.getMenu(), layout.getRecipe(),
            layout.getRecipeSlotsView(), List.of()).sourceIds());
        for (int source : sources) {
            String name = switch (source) {
                case StorageSource.BEYOND_DIMENSIONS -> "beyonddimensions";
                case StorageSource.REFINED_STORAGE -> "refinedstorage";
                case StorageSource.APPLIED_ENERGISTICS -> "ae2";
                case StorageSource.SOPHISTICATED_BACKPACKS -> "sophisticatedbackpacks";
                default -> null;
            };
            if (name == null) continue;
            if (!names.getSiblings().isEmpty()) names.append(Component.translatable("storage_fill.source_separator"));
            names.append(Component.translatable("storage_fill.source." + name));
        }
        return names.getSiblings().isEmpty() ? Component.translatable("storage_fill.source.inventory") : names;
    }

    @Override
    public void drawExtras(GuiGraphics graphics, Rect2i area, int mouseX, int mouseY, float partialTicks) {
        int color = switch (state().status()) {
            case IDLE -> 0;
            case QUERYING -> 0x80E9C65B;
            case READY -> 0x8068CE78;
            case MISSING, FAILED -> 0x80E66B66;
            case UNKNOWN -> 0x80E7AC57;
        };
        if (color != 0) graphics.fill(area.getX(), area.getY(),
            area.getX() + area.getWidth(), area.getY() + area.getHeight(), color);
    }
}

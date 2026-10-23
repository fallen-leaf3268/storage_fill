package dev.airun.storagefill.client.ftb;

import dev.airun.storagefill.client.RewardDestinationState;
import dev.airun.storagefill.compat.ftb.RewardDestination;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftblibrary.ui.ContextMenu;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.Theme;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.ftb.mods.ftbquests.client.gui.quests.TabButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

public final class RewardDestinationButton extends TabButton {
    private ContextMenu destinationMenu;
    private RewardDestination displayedDestination;

    public RewardDestinationButton(Panel panel) {
        this(panel, RewardDestinationState.current());
    }

    private RewardDestinationButton(Panel panel, RewardDestination destination) {
        super(panel, Component.translatable("storage_fill.ftb.reward_destination.action"), destinationIcon(destination));
        displayedDestination = destination;
    }

    @Override
    public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
        RewardDestination current = RewardDestinationState.current();
        if (current != displayedDestination) {
            setIcon(destinationIcon(current));
            displayedDestination = current;
        }
        super.draw(graphics, theme, x, y, w, h);
    }

    @Override
    public void onClicked(MouseButton button) {
        if (button != MouseButton.LEFT) return;
        playClickSound();
        if (questScreen.getContextMenu().orElse(null) == destinationMenu && destinationMenu != null) {
            questScreen.closeContextMenu();
            destinationMenu = null;
            return;
        }
        RewardDestination current = RewardDestinationState.current();
        List<ContextMenuItem> items = new ArrayList<>();
        for (RewardDestination destination : RewardDestination.values()) {
            if (!destination.available()) continue;
            Component label = Component.literal(destination == current ? "✓ " : "  ")
                .append(Component.translatable(destination.translationKey()))
                .withStyle(destination == current ? ChatFormatting.GREEN : ChatFormatting.WHITE);
            items.add(new ContextMenuItem(label, destinationIcon(destination),
                ignored -> RewardDestinationState.select(destination)).setCloseMenu(true));
        }
        destinationMenu = questScreen.openContextMenu(items);
        destinationMenu.setPos(Math.max(0, getX() - questScreen.getX() - destinationMenu.width - 2),
            Math.max(0, Math.min(getY() - questScreen.getY(), questScreen.height - destinationMenu.height)));
    }

    @Override
    public void addMouseOverText(TooltipList list) {
        list.add(getTitle());
        list.add(Component.translatable("storage_fill.ftb.reward_destination.current",
            Component.translatable(RewardDestinationState.current().translationKey())));
        list.add(Component.translatable("storage_fill.ftb.reward_destination.hint").withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable("storage_fill.ftb.reward_destination.fallback").withStyle(ChatFormatting.GRAY));
    }

    private static Icon destinationIcon(RewardDestination destination) {
        Item item = BuiltInRegistries.ITEM.get(destination.iconId());
        return ItemIcon.getItemIcon(item == null || item == Items.AIR ? Items.CRAFTING_TABLE : item);
    }
}

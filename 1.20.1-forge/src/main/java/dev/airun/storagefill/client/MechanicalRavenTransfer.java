package dev.airun.storagefill.client;

import dev.airun.storagefill.AllocationPlanner;
import dev.airun.storagefill.InventoryTopUp;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.RequestLimits;
import dev.airun.storagefill.StackKey;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemHandlerHelper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class MechanicalRavenTransfer {
    private static final String HANDLER = "com.apotheosis_artifice.enchant.ApotheosisArtificeJEIPlugin$MechanicalRavenTransferHandler";
    private static final String MENU = "com.apotheosis_artifice.enchant.MechanicalRavenEnchantMenu";
    private static final String RECIPE = "dev.shadowsoffire.apotheosis.ench.table.EnchantingRecipe";
    private static final ClassValue<Optional<Access>> ACCESS = new ClassValue<>() {
        protected Optional<Access> computeValue(Class<?> type) {
            try {
                Method find = type.getEnclosingClass().getDeclaredMethod("findMechanicalTransferInput",
                        Inventory.class, Ingredient.class, boolean.class);
                Class<?> recipe = Class.forName(RECIPE, false, type.getClassLoader());
                Method input = recipe.getMethod("getInput");
                if (!Modifier.isStatic(find.getModifiers()) || find.getReturnType() != ItemStack.class
                        || input.getReturnType() != Ingredient.class || !find.trySetAccessible()) {
                    throw new IllegalStateException("Unsupported mechanical raven transfer contract");
                }
                return Optional.of(new Access(recipe, input, find, new AtomicBoolean()));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                StorageFill.LOGGER.warn("Unable to bind mechanical raven recipe transfer", error);
                return Optional.empty();
            }
        }
    };

    private MechanicalRavenTransfer() { }

    static boolean supports(Object handler, AbstractContainerMenu menu, Object recipe) {
        return access(handler, menu, recipe) != null;
    }

    static StackKey selectedKey(Object handler, AbstractContainerMenu menu, Object recipe, Inventory inventory) {
        Access access = access(handler, menu, recipe);
        if (access == null) return null;
        try {
            Ingredient ingredient = (Ingredient) access.input().invoke(recipe);
            ItemStack selected = access.requested(inventory, ingredient);
            return selected.isEmpty() ? null : StackKey.of(selected);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (access.failed().compareAndSet(false, true)) StorageFill.LOGGER.warn("Unable to select mechanical raven input", error);
            return null;
        }
    }

    private static Access access(Object handler, AbstractContainerMenu menu, Object recipe) {
        if (!handler.getClass().getName().equals(HANDLER) || !menu.getClass().getName().equals(MENU)) return null;
        Access access = ACCESS.get(handler.getClass()).orElse(null);
        return access != null && !access.failed().get() && access.recipe().isInstance(recipe) ? access : null;
    }

    static CustomTransfer.Proposal preview(Object handler, AbstractContainerMenu menu, Object recipe,
            Inventory inventory, List<List<ItemStack>> options, RemoteInventory.View remote,
            IStackHelper helper, Supplier<IRecipeTransferError> simulate) {
        return preview(handler, menu, recipe, inventory, options, remote, helper, simulate, null);
    }

    static CustomTransfer.Proposal preview(Object handler, AbstractContainerMenu menu, Object recipe,
            Inventory inventory, List<List<ItemStack>> options, RemoteInventory.View remote,
            IStackHelper helper, Supplier<IRecipeTransferError> simulate, StackKey fixed) {
        Access access = access(handler, menu, recipe);
        if (access == null) return null;
        try {
            Ingredient ingredient = (Ingredient) access.input().invoke(recipe);
            ItemStack requested = fixed == null ? access.requested(inventory, ingredient)
                    : fixed.stack(Math.min(64, fixed.stack(1).getMaxStackSize()));
            List<ItemStack> before = inventory.items.stream().map(ItemStack::copy).toList();
            if (requested.isEmpty()) {
                CustomTransfer.Proposal seed = CustomTransfer.preview(inventory, options, remote, false, helper, simulate);
                if (seed == null) return null;
                var seedPlan = InventoryTopUp.plan(before, seed.required());
                if (seedPlan.isEmpty()) return null;
                requested = CustomTransfer.simulate(inventory, seedPlan.get().contents(),
                        () -> access.requested(inventory, ingredient));
            }
            if (requested.isEmpty() || requested.getCount() < 1 || requested.getCount() > 64) return null;
            StackKey key = StackKey.of(requested);
            if (key.data().sizeInBytes() > 16384) return null;
            int main = count(before, key, requested);
            int outside = 0;
            for (int slot = inventory.items.size(); slot < inventory.getContainerSize(); slot++) {
                outside += count(List.of(inventory.getItem(slot)), key, requested);
            }
            int remoteCount = 0;
            for (var entry : remote.entries()) {
                if (key.equals(entry.key())) {
                    ItemStack prototype = entry.key().stack(1);
                    if (ItemHandlerHelper.canItemStacksStack(prototype, requested)) remoteCount = Math.min(RequestLimits.MAX_COUNT, remoteCount + entry.count());
                }
            }
            int available = Math.min(RequestLimits.MAX_COUNT, main + outside + remoteCount);
            int target = Math.min(requested.getCount(), available);
            int needed = Math.max(0, target - outside);
            if (needed <= main) return null;
            var plan = InventoryTopUp.plan(before, List.of(key.stack(needed)));
            if (plan.isEmpty()) {
                int low = main;
                int high = needed;
                while (low < high) {
                    int middle = low + (high - low + 1) / 2;
                    if (InventoryTopUp.plan(before, List.of(key.stack(middle))).isPresent()) low = middle;
                    else high = middle - 1;
                }
                needed = low;
                if (needed <= main) return null;
                plan = InventoryTopUp.plan(before, List.of(key.stack(needed)));
            }
            if (plan.isEmpty() || plan.get().missing().isEmpty()) return null;
            if (fixed != null) {
                ItemStack nativeSelected = CustomTransfer.simulate(inventory, plan.get().contents(),
                        () -> access.requested(inventory, ingredient));
                if (nativeSelected.isEmpty() || !fixed.equals(StackKey.of(nativeSelected))) return null;
            }
            if (!allows(CustomTransfer.simulate(inventory, plan.get().contents(), simulate))) return null;
            List<ItemStack> required = List.of(key.stack(needed));
            var choices = List.of(new AllocationPlanner.Choice<>(key, needed));
            IRecipeTransferError notice = ClientTransfer.sourceNotice(choices, Map.of(key, main), remote, true);
            return notice == null ? null : new CustomTransfer.Proposal(required, notice);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (access.failed().compareAndSet(false, true)) {
                StorageFill.LOGGER.warn("Unable to prepare mechanical raven recipe transfer", error);
            }
            return null;
        }
    }

    private static int count(List<ItemStack> inventory, StackKey key, ItemStack prototype) {
        int count = 0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && key.equals(StackKey.of(stack)) && ItemHandlerHelper.canItemStacksStack(stack, prototype)) {
                count = Math.min(RequestLimits.MAX_COUNT, count + stack.getCount());
            }
        }
        return count;
    }

    private static boolean allows(IRecipeTransferError error) {
        return error == null || error.getType().allowsTransfer;
    }

    private record Access(Class<?> recipe, Method input, Method find, AtomicBoolean failed) {
        private ItemStack requested(Inventory inventory, Ingredient ingredient) {
            try {
                return (ItemStack) find.invoke(null, inventory, ingredient, true);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Unable to select mechanical raven input", error);
            }
        }
    }
}

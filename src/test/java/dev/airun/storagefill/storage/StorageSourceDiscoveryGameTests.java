package dev.airun.storagefill.storage;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class StorageSourceDiscoveryGameTests {
    @GameTest(template = "empty", batch = "storage_source_failure_isolation")
    public static void failedAdaptersDoNotDiscardOtherSourcesOrLeakPartialResults(GameTestHelper helper) {
        StorageSource first = new EmptySource("first");
        StorageSource partial = new EmptySource("partial");
        StorageSource last = new EmptySource("last");
        List<StorageSource> sources = new ArrayList<>();
        StorageSources.collect("discovery_test_before", sources, found -> found.add(first));
        int[] attempts = {0};
        for (int attempt = 0; attempt < 2; attempt++) {
            StorageSources.collect("discovery_test_runtime_failure", sources, found -> {
                attempts[0]++;
                found.add(partial);
                throw new IllegalStateException("Expected isolated storage discovery failure");
            });
        }
        StorageSources.collect("discovery_test_linkage_failure", sources, found -> {
            found.add(partial);
            throw new NoClassDefFoundError("Expected isolated optional-adapter linkage failure");
        });
        StorageSources.collect("discovery_test_after", sources, found -> found.add(last));
        helper.assertTrue(attempts[0] == 2, "A discovery error permanently disabled its adapter instead of allowing recovery");
        helper.assertTrue(sources.equals(List.of(first, last)),
            "Failed adapter discovery discarded healthy sources, changed their order, or exposed partial results");
        StorageSources.collect("discovery_test_runtime_failure", sources, found -> found.add(partial));
        helper.assertTrue(sources.equals(List.of(first, last, partial)), "A recovered adapter could not expose its complete successful sources");
        helper.succeed();
    }

    private record EmptySource(String name) implements StorageSource {
        public List<ItemStack> snapshot(Set<Item> wanted) { return List.of(); }
        public ItemStack extract(ItemStack prototype, int amount, boolean simulate) { return ItemStack.EMPTY; }
        public ItemStack insert(ItemStack stack) { return stack; }
        public void finish(int extracted) { }
    }
}

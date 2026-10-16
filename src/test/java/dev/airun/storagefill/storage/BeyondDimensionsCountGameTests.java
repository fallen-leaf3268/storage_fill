package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import dev.airun.storagefill.StorageFill;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class BeyondDimensionsCountGameTests {
    @GameTest(template = "empty", batch = "bd_count_query")
    public static void bdPreparedExactNbtPrefersPrototypeWithoutScanningOtherVariants(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.exactPrototype(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "bd_count_query")
    public static void bdPreparedQueryReusesMatchingCandidatesAndReadsFreshAmounts(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.reuse(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "bd_count_query")
    public static void bdPreparedQueryPreservesWeakAndEmptyNbtMatching(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.matching(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "bd_count_query")
    public static void bdPreparedQueryIncludesDifferentNativeCapabilityKeys(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.capabilities(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "bd_count_query")
    public static void bdPreparedQueryRebuildsAfterInsertionRemovalAndReplacement(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.changes(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "bd_count_query")
    public static void bdPreparedQueryRejectsRevokedMembershipAndChangedPrimaryNetwork(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.access(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "bd_count_query")
    public static void bdPreparedWeakQueryStopsEarlyAndContinuesForHigherLimits(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.lazyCandidates(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "bd_count_query")
    public static void bdStrictQuerySkipsUnrelatedTagsWhenMissingOrInsufficient(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.strictMissingAndInsufficient(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "bd_count_query")
    public static void bdStrictQueryPreservesAuthoritativeCapabilityMatching(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.strictCapabilities(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "bd_count_query")
    public static void bdStrictHintKeepsNullEmptyWeakAndIgnoredNbtSemantics(GameTestHelper helper) {
        if (!installed(helper)) return;
        Cases.strictFallbacks(helper);
        helper.succeed();
    }

    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("beyonddimensions") && ModList.get().isLoaded("ftbquests")) return true;
        StorageFill.LOGGER.warn("SKIPPED BD prepared count GameTest: beyonddimensions or ftbquests is absent");
        helper.succeed();
        return false;
    }

    private static final class Cases {
        private static ItemStack tagged(int variant) {
            ItemStack stack = new ItemStack(Items.IRON_INGOT);
            stack.getOrCreateTag().putInt("group", 9);
            stack.getOrCreateTag().putInt("variant", variant);
            return stack;
        }

        private static ItemStack group() {
            ItemStack stack = new ItemStack(Items.IRON_INGOT);
            stack.getOrCreateTag().putInt("group", 9);
            return stack;
        }

        private static Predicate<ItemStack> matcher(ItemStack prototype, boolean weak) {
            return stack -> ItemMatchingSystem.INSTANCE.doesItemMatch(prototype, stack, true, weak);
        }

        private static void strictMissingAndInsufficient(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                for (int variant = 1; variant <= 64; variant++) fixture.put(tagged(variant), 1);
                ItemStack target = tagged(999);
                int[] matched = {0};
                StorageSource.CountQuery query = fixture.source.prepareStrictCount(target, stack -> {
                    matched[0]++;
                    return ItemMatchingSystem.INSTANCE.doesItemMatch(target, stack, true, false);
                });
                helper.assertTrue(query.count(1000) == 0 && matched[0] == 0,
                    "Strict missing BD query evaluated unrelated NBT predicates");
                fixture.put(target, 7);
                helper.assertTrue(query.count(1000) == 7 && matched[0] == 1,
                    "Strict insufficient BD query omitted current stock or evaluated unrelated variants");
                helper.assertTrue(fixture.network.getUnifiedStorage().getStackByKey(new ItemStackKey(target)).amount() == 7,
                    "Strict read-only BD query consumed its matching prototype");
                fixture.network.getUnifiedStorage().setAmountByKey(new ItemStackKey(target), 11);
                helper.assertTrue(query.count(1000) == 11,
                    "Strict BD query failed to invalidate after a native amount change");
            }
        }

        private static void strictCapabilities(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemStack target = tagged(1);
                ItemStackKey first = capabilityKey(target, 1);
                ItemStackKey second = capabilityKey(target, 2);
                ItemStackKey otherTag = capabilityKey(tagged(2), 3);
                ItemStackKey nullTag = capabilityKey(new ItemStack(Items.IRON_INGOT), 4);
                helper.assertTrue(fixture.network.getUnifiedStorage().insert(first, 7, false).isEmpty()
                    && fixture.network.getUnifiedStorage().insert(second, 11, false).isEmpty()
                    && fixture.network.getUnifiedStorage().insert(otherTag, 13, false).isEmpty()
                    && fixture.network.getUnifiedStorage().insert(nullTag, 17, false).isEmpty(),
                    "Strict capability count fixture failed to store native keys");
                int[] matched = {0};
                StorageSource.CountQuery query = fixture.source.prepareStrictCount(target, stack -> {
                    matched[0]++;
                    return ItemMatchingSystem.INSTANCE.doesItemMatch(target, stack, true, false);
                });
                helper.assertTrue(query.count(1000) == 18 && matched[0] == 4,
                    "Strict BD query bypassed authoritative reconstruction for a nonempty capability key");
                helper.assertTrue(fixture.network.getUnifiedStorage().getStackByKey(first).amount() == 7
                    && fixture.network.getUnifiedStorage().getStackByKey(second).amount() == 11,
                    "Strict capability-aware BD query consumed native keys");
            }
        }

        private static void strictFallbacks(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                fixture.put(new ItemStack(Items.IRON_INGOT), 3);
                fixture.put(tagged(1), 5);
                fixture.put(tagged(2), 7);
                ItemStack plain = new ItemStack(Items.IRON_INGOT);
                helper.assertTrue(fixture.source.prepareStrictCount(plain, matcher(plain, false)).count(1000) == 15,
                    "Strict BD hint narrowed a null FTB NBT pattern");
                ItemStack empty = new ItemStack(Items.IRON_INGOT);
                empty.setTag(new CompoundTag());
                helper.assertTrue(fixture.source.prepareStrictCount(empty, matcher(empty, false)).count(1000) == 3,
                    "Strict BD hint omitted tagless stock matching an empty NBT pattern");
                ItemStack weak = group();
                helper.assertTrue(fixture.source.prepareCount(weak, matcher(weak, true)).count(1000) == 12,
                    "BD weak NBT counting stopped aggregating matching variants");
                ItemStack ignored = tagged(999);
                helper.assertTrue(fixture.source.prepareCount(ignored,
                    stack -> ItemMatchingSystem.INSTANCE.doesItemMatch(ignored, stack, false, false)).count(1000) == 15,
                    "BD ignored NBT counting stopped aggregating all variants");
            }
        }

        private static void exactPrototype(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                for (int variant = 1; variant <= 64; variant++) fixture.put(tagged(variant), 64);
                ItemStack target = tagged(64);
                int[] matches = {0};
                StorageSource.CountQuery query = fixture.source.prepareCount(target, stack -> {
                    matches[0]++;
                    return ItemMatchingSystem.INSTANCE.doesItemMatch(target, stack, true, false);
                });
                helper.assertTrue(query.count(64) == 64, "Prepared BD exact NBT query omitted its native prototype stock");
                helper.assertTrue(matches[0] <= 1,
                    "Prepared BD exact NBT query matched unrelated variants before its sufficient prototype key");
                helper.assertTrue(fixture.network.getUnifiedStorage().getStackByKey(new ItemStackKey(target)).amount() == 64,
                    "Prepared BD exact NBT query consumed its native stock");
            }
        }

        private static void reuse(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                for (int variant = 1; variant <= 10; variant++) fixture.put(tagged(variant), 8);
                ItemStack target = group();
                int[] matches = {0};
                StorageSource.CountQuery query = fixture.source.prepareCount(target, stack -> {
                    matches[0]++;
                    return ItemMatchingSystem.INSTANCE.doesItemMatch(target, stack, true, true);
                });
                helper.assertTrue(query.count(1000) == 80, "Prepared BD query omitted weak NBT variants");
                int firstMatches = matches[0];
                helper.assertTrue(query.count(1000) == 80 && matches[0] == firstMatches,
                    "Unchanged prepared BD query rebuilt matching candidates instead of reusing them");
                fixture.network.getUnifiedStorage().insert(new ItemStackKey(tagged(11)), 9, true);
                helper.assertTrue(query.count(1000) == 80 && matches[0] == firstMatches,
                    "Simulated native BD insertion invalidated matching candidates or changed stock");
                helper.assertTrue(fixture.network.getUnifiedStorage().setAmountByKey(new ItemStackKey(tagged(3)), 12) == 12,
                    "Native BD amount-change fixture failed");
                helper.assertTrue(query.count(1000) == 84,
                    "Prepared BD query reused a stale quantity after a native amount change");
            }
        }

        private static void lazyCandidates(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                for (int variant = 1; variant <= 64; variant++) fixture.put(tagged(variant), 64);
                ItemStack target = group();
                helper.assertTrue(fixture.network.getUnifiedStorage().getStackByKey(new ItemStackKey(target)).amount() == 0,
                    "Weak BD early-stop fixture unexpectedly contains an exact prototype key");
                int[] matches = {0};
                StorageSource.CountQuery query = fixture.source.prepareCount(target, stack -> {
                    matches[0]++;
                    return ItemMatchingSystem.INSTANCE.doesItemMatch(target, stack, true, true);
                });
                helper.assertTrue(query.count(1) == 1 && matches[0] <= 1,
                    "Prepared weak BD query matched every variant before returning its sufficient first stock");
                helper.assertTrue(query.count(65) == 65 && matches[0] <= 2,
                    "Prepared weak BD query did not continue lazily from its previous matching candidate");
                helper.assertTrue(query.count(4097) == 4096 && matches[0] == 64,
                    "Prepared weak BD query omitted or duplicated candidates when completing the remaining scan");
                helper.assertTrue(query.count(1000) == 1000 && matches[0] == 64,
                    "Completed prepared weak BD query did not reuse its matching candidates");
                for (int variant = 1; variant <= 64; variant++) {
                    helper.assertTrue(fixture.network.getUnifiedStorage().getStackByKey(new ItemStackKey(tagged(variant))).amount() == 64,
                        "Prepared weak BD early-stop query consumed native stock");
                }
            }
        }

        private static void matching(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                fixture.put(new ItemStack(Items.IRON_INGOT), 3);
                fixture.put(tagged(1), 12);
                fixture.put(tagged(2), 20);
                ItemStack weak = group();
                helper.assertTrue(fixture.source.prepareCount(weak, matcher(weak, true)).count(1000) == 32,
                    "Prepared BD query replaced weak NBT matching with exact native key equality");
                ItemStack empty = new ItemStack(Items.IRON_INGOT);
                helper.assertTrue(fixture.source.prepareCount(empty, matcher(empty, false)).count(1000) == 35,
                    "Prepared BD query treated an empty FTB NBT pattern as a no-NBT restriction");
                ItemStack target = tagged(2);
                helper.assertTrue(fixture.source.prepareCount(tagged(1), matcher(target, false)).count(1000) == 20,
                    "Prepared BD prototype hint bypassed the authoritative item predicate");
            }
        }

        private static void capabilities(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                ItemStack target = tagged(1);
                ItemStackKey first = capabilityKey(target, 1);
                ItemStackKey second = capabilityKey(target, 2);
                helper.assertFalse(first.equals(second), "Native BD capability fixture did not create distinct keys");
                helper.assertTrue(fixture.network.getUnifiedStorage().insert(first, 7, false).isEmpty()
                    && fixture.network.getUnifiedStorage().insert(second, 11, false).isEmpty(),
                    "Native BD capability fixture insertion failed");
                StorageSource.CountQuery query = fixture.source.prepareCount(target, matcher(target, false));
                helper.assertTrue(query.count(1000) == 18 && query.count(8) == 8,
                    "Prepared BD query discarded FTB-matching stocks with different native capability keys");
                helper.assertTrue(fixture.network.getUnifiedStorage().getStackByKey(first).amount() == 7
                    && fixture.network.getUnifiedStorage().getStackByKey(second).amount() == 11,
                    "Prepared BD capability query consumed native stock");
            }
        }

        private static ItemStackKey capabilityKey(ItemStack prototype, int variant) {
            ItemStackKey key = new ItemStackKey(prototype);
            CompoundTag encoded = key.serializeNBT();
            CompoundTag capability = new CompoundTag();
            capability.putInt("variant", variant);
            CompoundTag caps = new CompoundTag();
            caps.put("storage_fill:count_query", capability);
            encoded.put("caps", caps);
            return (ItemStackKey) key.deserializeNBT(encoded);
        }

        private static void changes(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                fixture.put(tagged(1), 7);
                ItemStack target = group();
                StorageSource.CountQuery query = fixture.source.prepareCount(target, matcher(target, true));
                helper.assertTrue(query.count(1000) == 7, "Prepared BD change fixture had an incorrect initial quantity");
                fixture.put(tagged(2), 11);
                helper.assertTrue(query.count(1000) == 18, "Prepared BD query missed a newly inserted matching key");
                fixture.network.getUnifiedStorage().extract(new ItemStackKey(tagged(1)), 7, false, false);
                helper.assertTrue(query.count(1000) == 11, "Prepared BD query retained a removed native key");
                fixture.network.getUnifiedStorage().clearStorage();
                fixture.put(tagged(3), 13);
                helper.assertTrue(query.count(1000) == 13, "Prepared BD query reused candidates after native replacement");
                fixture.network.getUnifiedStorage().extract(new ItemStackKey(tagged(3)), 5, false, false);
                helper.assertTrue(query.count(1000) == 8, "Prepared BD query reused a stale remaining native quantity");
                CompoundTag saved = fixture.network.getUnifiedStorage().serializeNBT();
                fixture.network.getUnifiedStorage().setStackDirectly(0, new ItemStackKey(tagged(4)), 17);
                helper.assertTrue(query.count(1000) == 17, "Prepared BD query ignored a native slot-key replacement");
                fixture.network.getUnifiedStorage().deserializeNBT(saved);
                helper.assertTrue(query.count(1000) == 8, "Prepared BD query reused candidates after a native inventory load");
            }
        }

        private static void access(GameTestHelper helper) {
            try (Fixture fixture = new Fixture(helper)) {
                fixture.put(tagged(1), 64);
                StorageSource.CountQuery query = fixture.source.prepareCount(tagged(1), matcher(tagged(1), false));
                helper.assertTrue(query.count(64) == 64, "Prepared BD access fixture was not initially available");
                fixture.network.removePlayer(fixture.player.getUUID());
                helper.assertTrue(query.count(64) == 0, "Prepared BD query retained revoked membership access");
                fixture.network.addPlayer(fixture.player.getUUID());
                DimensionsNet.setPrimaryNetForPlayer(fixture.player, fixture.network);
                helper.assertTrue(query.count(64) == 64, "Prepared BD query did not revalidate restored membership");
                DimensionsNet other = DimensionsNet.createNewNetForPlayer(Fixture.player(helper), 1000, 64);
                helper.assertTrue(other != null && other != fixture.network,
                    "Prepared BD primary-switch fixture could not create a distinct second network");
                try {
                    other.addPlayer(fixture.player.getUUID());
                    DimensionsNet.setPrimaryNetForPlayer(fixture.player, fixture.network);
                    helper.assertTrue(DimensionsNet.getNetFromPlayer(fixture.player) == fixture.network,
                        "Prepared BD primary-switch fixture did not restore the original network before switching");
                    helper.assertTrue(DimensionsNet.setPrimaryNetForPlayer(fixture.player, other)
                        && DimensionsNet.getNetFromPlayer(fixture.player) == other,
                        "Prepared BD primary-switch fixture did not select the second network");
                    helper.assertTrue(query.count(64) == 0, "Prepared BD query ignored a changed primary network");
                } finally {
                    other.destroySelf();
                }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerPlayer player;
        private final DimensionsNet network;
        private final StorageSource source;

        private Fixture(GameTestHelper helper) {
            ServerPlayer owner = player(helper);
            player = player(helper);
            network = DimensionsNet.createNewNetForPlayer(owner, Long.MAX_VALUE, 512);
            helper.assertTrue(network != null, "Native BD prepared query fixture could not create a network");
            network.addPlayer(player.getUUID());
            DimensionsNet.setPrimaryNetForPlayer(player, network);
            List<StorageSource> sources = new ArrayList<>();
            BeyondDimensionsSource.find(player, sources);
            helper.assertTrue(sources.size() == 1, "Native BD prepared query fixture did not discover one source");
            source = sources.get(0);
        }

        private void put(ItemStack stack, long amount) {
            if (!network.getUnifiedStorage().insert(new ItemStackKey(stack), amount, false).isEmpty()) {
                throw new AssertionError("Native BD prepared query fixture insertion failed");
            }
        }

        private static ServerPlayer player(GameTestHelper helper) {
            return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "bd-count-query"));
        }

        public void close() { network.destroySelf(); }
    }
}

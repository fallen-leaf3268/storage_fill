package dev.airun.storagefill.storage;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.StorageFill;
import dev.airun.storagefill.TransferService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class StorageIntegrationGameTests {
    @GameTest(template = "empty")
    public static void fallbackPreviewStopsAndPreservesInventory(GameTestHelper helper) {
        StorageSource source = new StorageSource() {
            private int previews;
            public List<ItemStack> snapshot(Set<net.minecraft.world.item.Item> wanted) {
                throw new AssertionError("Streaming fallback bypassed preview");
            }
            public List<ItemStack> preview(Set<net.minecraft.world.item.Item> wanted) {
                if (previews++ == 0) helper.assertTrue(wanted.equals(Set.copyOf(ForgeRegistries.ITEMS.getValues())),
                    "Whole-inventory fallback preview omitted registered item types");
                return List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.EMERALD, 7)).stream()
                    .filter(stack -> wanted.contains(stack.getItem())).toList();
            }
            public ItemStack extract(ItemStack prototype, int amount, boolean simulate) {
                throw new AssertionError("Streaming fallback extracted items");
            }
            public ItemStack insert(ItemStack stack) { throw new AssertionError("Streaming fallback inserted items"); }
            public void finish(int extracted) { throw new AssertionError("Streaming fallback completed a transfer"); }
        };
        assertStreamingPreview(helper, source, 11);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bdIndexedWatchTracksVariantsAndCloses(GameTestHelper helper) {
        if (!installed(helper, "beyonddimensions")) return;
        BeyondDimensionsCases.watch(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bdRealSnapshotExtractionAndRefund(GameTestHelper helper) {
        if (!installed(helper, "beyonddimensions")) return;
        BeyondDimensionsCases.roundTrip(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bdRevokedMemberCannotUseOldSource(GameTestHelper helper) {
        if (!installed(helper, "beyonddimensions")) return;
        BeyondDimensionsCases.membership(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bdPrimarySwitchInvalidatesOldSource(GameTestHelper helper) {
        if (!installed(helper, "beyonddimensions")) return;
        BeyondDimensionsCases.switchPrimary(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rsUnboundAndMissingNetworkRejected(GameTestHelper helper) {
        if (!installed(helper, "refinedstorage")) return;
        RefinedStorageCases.invalidNetworks(helper);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rsRealTerminalSimulationPreservesEnergy(GameTestHelper helper) {
        if (!installed(helper, "refinedstorage")) return;
        RefinedStorageCases.energy(helper);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void rsRealWirelessNetworkRoundTripAndRange(GameTestHelper helper) {
        if (!installed(helper, "refinedstorage")) return;
        RefinedStorageCases.realNetwork(helper);
    }

    @GameTest(template = "empty", batch = "storage_change_bridge", timeoutTicks = 160)
    public static void rsNativeInsertRecordsOnlyAcceptedPerform(GameTestHelper helper) {
        if (!installed(helper, "refinedstorage") || !installed(helper, "ftbquests")) return;
        RefinedStorageCases.realNetwork(helper, true);
    }

    private static boolean installed(GameTestHelper helper, String mod) {
        if (ModList.get().isLoaded(mod)) return true;
        StorageFill.LOGGER.warn("SKIPPED storage integration GameTest: {} is absent", mod);
        helper.succeed();
        return false;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "storage-it"));
    }

    private static StorageSource onlySource(GameTestHelper helper, ServerPlayer player) {
        List<StorageSource> sources = StorageSources.find(player);
        helper.assertTrue(sources.size() == 1, "Expected exactly one real storage source, got " + sources.size());
        return sources.get(0);
    }

    private static int count(StorageSource source, ItemStack prototype) {
        return source.snapshot(Set.of(prototype.getItem())).stream()
            .filter(stack -> ItemStack.isSameItemSameTags(stack, prototype))
            .mapToInt(ItemStack::getCount).sum();
    }

    static void assertStreamingPreview(GameTestHelper helper, StorageSource source, int expectedCount) {
        int[] visits = {0};
        helper.assertFalse(source.visitPreview(stack -> {
            visits[0]++;
            helper.assertFalse(stack.isEmpty(), "Streaming preview emitted an empty stack");
            return false;
        }), "Streaming preview ignored visitor cancellation");
        helper.assertTrue(visits[0] == 1, "Streaming preview continued after cancellation");
        int[] count = {0};
        java.util.Map<net.minecraft.world.item.Item, Integer> byItem = new java.util.LinkedHashMap<>();
        helper.assertTrue(source.visitPreview(stack -> {
            count[0] += stack.getCount();
            byItem.merge(stack.getItem(), stack.getCount(), Integer::sum);
            stack.setCount(0);
            return true;
        }), "Streaming preview did not report completion");
        helper.assertTrue(count[0] == expectedCount, "Streaming preview omitted or duplicated inventory");
        int[] unchanged = {0};
        helper.assertTrue(source.visitPreview(stack -> {
            unchanged[0] += stack.getCount();
            return true;
        }) && unchanged[0] == expectedCount, "Streaming preview exposed mutable stored stacks");
        helper.assertTrue(source.visitPreview(Set.of(), stack -> {
            throw new AssertionError("Empty targeted streaming preview visited material");
        }), "Empty targeted streaming preview did not complete");
        for (var entry : byItem.entrySet()) {
            int[] selected = {0};
            helper.assertTrue(source.visitPreview(Set.of(entry.getKey()), stack -> {
                helper.assertTrue(stack.is(entry.getKey()), "Targeted streaming preview exposed an unrelated item");
                selected[0] += stack.getCount();
                stack.setCount(0);
                return true;
            }) && selected[0] == entry.getValue(), "Targeted streaming preview omitted, duplicated, or mutated material");
            int[] stopped = {0};
            helper.assertFalse(source.visitPreview(Set.of(entry.getKey()), stack -> { stopped[0]++; return false; }),
                "Targeted streaming preview ignored cancellation");
            helper.assertTrue(stopped[0] == 1, "Targeted streaming preview continued after cancellation");
        }
        int[] retained = {0};
        helper.assertTrue(source.visitPreview(stack -> { retained[0] += stack.getCount(); return true; })
            && retained[0] == expectedCount, "Targeted streaming preview changed stored quantities");
    }

    static void assertUnavailablePreview(GameTestHelper helper, StorageSource source) {
        helper.assertTrue(source.visitPreview(stack -> {
            throw new AssertionError("Unavailable source exposed streaming inventory");
        }), "Unavailable streaming preview did not complete");
        helper.assertTrue(source.visitPreview(Set.of(Items.DIAMOND), stack -> {
            throw new AssertionError("Unavailable source exposed targeted streaming inventory");
        }), "Unavailable targeted streaming preview did not complete");
    }

    static void assertRecordedChange(GameTestHelper helper, StorageSource source, net.minecraft.world.item.Item item) {
        StorageChanges.Batch batch = StorageChanges.drain();
        helper.assertTrue(!batch.allSources() && batch.changes().size() == 1,
            "Successful native insertion did not report exactly one source change");
        StorageChanges.Change change = batch.changes().get(0);
        helper.assertTrue(change.sourceId() == source.sourceId() && change.identity() == source.changeIdentity()
            && !change.allItems() && change.items().equals(Set.of(item)),
            "Native insertion change did not match the discovered source identity and item");
    }

    private static ItemStack tagged(int variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        stack.getOrCreateTag().putInt("storage_test_variant", variant);
        return stack;
    }

    private static final class BeyondDimensionsCases {
        private static void watch(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            var network = create(helper, player);
            try {
                StorageSource source = onlySource(helper, player);
                java.util.List<net.minecraft.world.item.Item> changed = new java.util.ArrayList<>();
                AutoCloseable subscription = source.watch(changed::add);
                helper.assertTrue(subscription != null, "BD did not provide a closable inventory subscription");
                java.util.List<net.minecraft.world.item.Item> otherChanges = new java.util.ArrayList<>();
                AutoCloseable otherSubscription = onlySource(helper, player).watch(otherChanges::add);
                helper.assertTrue(otherSubscription != null, "BD second subscriber failed");
                helper.assertTrue(source.identity().equals(onlySource(helper, player).identity()), "BD source identity changed on discovery");
                source.insert(tagged(1, 25));
                source.insert(tagged(2, 9));
                source.insert(new ItemStack(Items.EMERALD, 7));
                var indexesField = BeyondDimensionsSource.class.getDeclaredField("INDEXES");
                indexesField.setAccessible(true);
                Object index = ((java.util.Map<?, ?>) indexesField.get(null)).get(network);
                var itemsField = index.getClass().getDeclaredField("items");
                itemsField.setAccessible(true);
                var dirtyField = index.getClass().getDeclaredField("dirty");
                dirtyField.setAccessible(true);
                helper.assertTrue(dirtyField.getBoolean(index) && ((java.util.Map<?, ?>) itemsField.get(index)).isEmpty(),
                    "BD cold watch fixture already built the shared index");
                int[] visited = {0};
                helper.assertFalse(source.visitPreview(stack -> { visited[0]++; return false; }),
                    "BD cold preview ignored cancellation");
                helper.assertTrue(visited[0] == 1 && dirtyField.getBoolean(index)
                    && ((java.util.Map<?, ?>) itemsField.get(index)).isEmpty(),
                    "BD cancelled cold preview eagerly built the shared index");
                assertStreamingPreview(helper, source, 41);
                helper.assertTrue(dirtyField.getBoolean(index) && ((java.util.Map<?, ?>) itemsField.get(index)).isEmpty(),
                    "BD completed cold preview eagerly built the shared index");
                helper.assertTrue(changed.contains(Items.DIAMOND) && changed.contains(Items.EMERALD), "BD delta did not identify changed items");
                helper.assertTrue(source.snapshot(Set.of(Items.DIAMOND)).size() == 2, "BD wanted index omitted or included unrelated variants");
                helper.assertFalse(dirtyField.getBoolean(index), "BD requested snapshot did not prepare shared index");
                assertStreamingPreview(helper, source, 41);
                source.extract(tagged(1, 1), 25, false);
                helper.assertTrue(count(source, tagged(1, 1)) == 0 && count(source, tagged(2, 1)) == 9, "BD index retained depleted variant");
                network.getUnifiedStorage().clearStorage();
                helper.assertTrue(source.visitPreview(stack -> { throw new AssertionError("BD dirty preview exposed stale indexed items"); })
                    && dirtyField.getBoolean(index), "BD dirty preview rebuilt the shared index");
                helper.assertTrue(changed.contains(null) && source.snapshot(Set.of(Items.DIAMOND)).isEmpty(), "BD full invalidation retained stale entries");
                subscription.close();
                int events = changed.size();
                otherChanges.clear();
                source.insert(tagged(3, 6));
                helper.assertTrue(changed.size() == events, "Closed BD subscription still received events");
                helper.assertTrue(otherChanges.contains(Items.DIAMOND), "Closing one BD subscription disconnected the remaining subscriber");
                otherSubscription.close();
                source.insert(tagged(3, 1));
                helper.assertTrue(count(source, tagged(3, 1)) == 7, "BD direct snapshot after closing retained stale index");
            } catch (Exception exception) {
                throw new AssertionError(exception);
            } finally {
                network.destroySelf();
            }
        }

        private static com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet create(
                GameTestHelper helper, ServerPlayer player) {
            var network = com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet
                .createNewNetForPlayer(player, 10000L, 64);
            helper.assertTrue(network != null, "BD failed to create real persistent network");
            helper.assertTrue(com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.getNetFromPlayer(player) == network,
                "Created BD network is not player's primary");
            return network;
        }

        private static void roundTrip(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            var network = create(helper, player);
            try {
                StorageSource source = onlySource(helper, player);
                helper.assertTrue(source.insert(tagged(7, 300)).isEmpty(), "BD insertion left remainder");
                helper.assertTrue(source.insert(tagged(8, 19)).isEmpty(), "BD NBT variant insertion failed");
                helper.assertTrue(count(source, tagged(7, 1)) == 300, "Snapshot truncated stack to vanilla limit");
                helper.assertTrue(count(source, tagged(8, 1)) == 19, "Snapshot merged different NBT");
                ItemStack simulated = source.extract(tagged(7, 1), 40, true);
                helper.assertTrue(simulated.getCount() == 40 && count(source, tagged(7, 1)) == 300,
                    "BD simulation changed storage");
                helper.assertTrue(source.extract(tagged(9, 1), 1, false).isEmpty(), "BD extracted wrong NBT");
                ItemStack extracted = source.extract(tagged(7, 1), 40, false);
                helper.assertTrue(extracted.getCount() == 40 && ItemStack.isSameItemSameTags(extracted, tagged(7, 1)),
                    "BD actual extraction returned wrong stack");
                helper.assertTrue(count(source, tagged(7, 1)) == 260, "BD actual count did not decrement");
                helper.assertTrue(source.insert(extracted).isEmpty(), "BD refund failed");
                source.finish(0);
                helper.assertTrue(count(source, tagged(7, 1)) == 300 && count(source, tagged(8, 1)) == 19,
                    "BD refund lost or duplicated items");
            } finally {
                network.destroySelf();
            }
        }

        private static void membership(GameTestHelper helper) {
            ServerPlayer owner = player(helper);
            ServerPlayer member = player(helper);
            var network = create(helper, owner);
            try {
                StorageSource ownerSource = onlySource(helper, owner);
                helper.assertTrue(ownerSource.insert(new ItemStack(Items.DIAMOND, 10)).isEmpty(), "BD setup insertion failed");
                helper.assertTrue(StorageSources.find(member).isEmpty(), "Non-member discovered private BD network");
                network.addPlayer(member.getUUID());
                com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.setPrimaryNetForPlayer(member, network);
                helper.assertTrue(com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.getNetFromPlayer(member) == network,
                    "BD member primary selection failed");
                StorageSource old = onlySource(helper, member);
                helper.assertTrue(count(old, new ItemStack(Items.DIAMOND)) == 10, "Member could not see BD storage");
                network.removePlayer(member.getUUID());
                helper.assertTrue(old.snapshot(Set.of(Items.DIAMOND)).isEmpty(), "Revoked member retained snapshot access");
                helper.assertTrue(old.visitPreview(stack -> { throw new AssertionError("Revoked member retained streaming preview access"); }),
                    "Unavailable BD preview did not complete");
                helper.assertTrue(old.extract(new ItemStack(Items.DIAMOND), 1, true).isEmpty(), "Revoked member could simulate extraction");
                helper.assertTrue(old.extract(new ItemStack(Items.DIAMOND), 1, false).isEmpty(), "Revoked member extracted items");
                helper.assertTrue(StorageSources.find(member).isEmpty(), "Revoked member rediscovered BD source");
                helper.assertTrue(count(ownerSource, new ItemStack(Items.DIAMOND)) == 10, "Denied access changed BD inventory");
            } finally {
                network.destroySelf();
            }
        }

        private static void switchPrimary(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            ServerPlayer other = player(helper);
            var first = create(helper, player);
            var second = create(helper, other);
            try {
                StorageSource old = onlySource(helper, player);
                helper.assertTrue(old.insert(new ItemStack(Items.DIAMOND, 10)).isEmpty(), "BD first network setup failed");
                StorageSource secondOwner = onlySource(helper, other);
                helper.assertTrue(secondOwner.insert(new ItemStack(Items.DIAMOND, 5)).isEmpty(), "BD second network setup failed");
                second.addPlayer(player.getUUID());
                com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.setPrimaryNetForPlayer(player, first);
                helper.assertTrue(com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.getNetFromPlayer(player) == first,
                    "BD primary switch setup did not restore original network");
                helper.assertTrue(com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet.setPrimaryNetForPlayer(player, second),
                    "BD primary network switch failed");
                helper.assertTrue(old.snapshot(Set.of(Items.DIAMOND)).isEmpty(), "Old primary still exposed snapshot");
                helper.assertTrue(old.extract(new ItemStack(Items.DIAMOND), 1, false).isEmpty(), "Old primary still allowed extraction");
                helper.assertTrue(old.insert(new ItemStack(Items.DIAMOND)).getCount() == 1, "Old primary accepted insertion");
                helper.assertTrue(count(onlySource(helper, player), new ItemStack(Items.DIAMOND)) == 5,
                    "Discovery did not follow new primary network");
            } finally {
                first.destroySelf();
                second.destroySelf();
            }
        }
    }

    private static final class RefinedStorageCases {
        private static Block block(String name) {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage", name));
            if (block == null || block == Blocks.AIR) throw new AssertionError("Missing real RS block " + name);
            return block;
        }

        private static void realNetwork(GameTestHelper helper) {
            realNetwork(helper, false);
        }

        private static void realNetwork(GameTestHelper helper, boolean changesOnly) {
            BlockPos controllerPos = new BlockPos(1, 1, 1);
            BlockPos transmitterPos = new BlockPos(2, 1, 1);
            helper.setBlock(controllerPos, block("creative_controller"));
            helper.setBlock(new BlockPos(0, 1, 1), block("64k_storage_block"));
            helper.setBlock(transmitterPos, block("wireless_transmitter"));
            helper.runAfterDelay(40, () -> {
                var controller = (com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity)
                    helper.getLevel().getBlockEntity(helper.absolutePos(controllerPos));
                helper.assertTrue(controller != null, "Real RS controller block entity was not created");
                var network = controller.getNetwork();
                helper.assertTrue(network.canRun(), "Real creative RS network is not running after 40 ticks");
                helper.assertTrue(network.getNodeGraph().all().size() >= 3, "Real RS graph did not connect three adjacent blocks");
                helper.assertTrue(network.insertItem(tagged(7, 128), 128, com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty(),
                    "Real RS storage block did not accept first NBT variant");
                helper.assertTrue(network.insertItem(tagged(8, 16), 16, com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty(),
                    "Real RS storage block did not accept second NBT variant");

                ServerPlayer player = player(helper);
                BlockPos absolute = helper.absolutePos(controllerPos);
                player.setPos(absolute.getX() + 0.5, absolute.getY() + 1.5, absolute.getZ() + 0.5);
                ItemStack terminal = new ItemStack(com.refinedmods.refinedstorage.RSItems.WIRELESS_GRID.get());
                CompoundTag binding = terminal.getOrCreateTag();
                binding.putInt("NodeX", absolute.getX());
                binding.putInt("NodeY", absolute.getY());
                binding.putInt("NodeZ", absolute.getZ());
                binding.putString("Dimension", helper.getLevel().dimension().location().toString());
                var energy = terminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(() -> new AssertionError("Missing RS energy capability"));
                energy.receiveEnergy(Integer.MAX_VALUE, false);
                player.getInventory().setItem(0, terminal);
                ItemStack secondTerminal = terminal.copy();
                player.getInventory().setItem(1, secondTerminal);
                var secondEnergy = secondTerminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(() -> new AssertionError("Missing second energy capability"));
                int before = energy.getEnergyStored();
                int secondBefore = secondEnergy.getEnergyStored();
                StorageSource source = onlySource(helper, player);
                helper.assertTrue(source.identity().equals(onlySource(helper, player).identity()), "RS source identity changed on discovery");
                if (changesOnly) {
                    StorageChanges.clear();
                    helper.assertTrue(network.insertItem(new ItemStack(Items.LAPIS_LAZULI, 13), 13,
                        com.refinedmods.refinedstorage.api.util.Action.SIMULATE).isEmpty(),
                        "RS native simulation did not accept the bridge test material");
                    helper.assertFalse(StorageChanges.hasChanges(), "RS native simulation recorded an insertion change");
                    helper.assertTrue(network.getItemStorageCache().getList().getCount(new ItemStack(Items.LAPIS_LAZULI)) == 0,
                        "RS native insertion simulation changed stored materials");
                    helper.assertTrue(network.insertItem(new ItemStack(Items.LAPIS_LAZULI, 13), 13,
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty(),
                        "RS native insertion did not accept the bridge test material");
                    assertRecordedChange(helper, source, Items.LAPIS_LAZULI);
                    helper.assertTrue(network.getItemStorageCache().getList().getCount(new ItemStack(Items.LAPIS_LAZULI)) == 13,
                        "RS native insertion bridge did not preserve the accepted quantity");
                    helper.succeed();
                    return;
                }
                java.util.List<net.minecraft.world.item.Item> changes = new java.util.ArrayList<>();
                AutoCloseable subscription = source.watch(changes::add);
                helper.assertTrue(subscription != null, "RS did not provide a closable inventory subscription");
                network.insertItem(new ItemStack(Items.EMERALD, 5), 5, com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                network.getItemStorageCache().flush();
                helper.assertTrue(changes.contains(Items.EMERALD), "RS listener omitted inserted material");
                helper.assertTrue(source.snapshot(Set.of(Items.DIAMOND)).size() == 2, "RS wanted index included unrelated material");
                assertStreamingPreview(helper, source, 149);
                helper.assertTrue(energy.getEnergyStored() == before && secondEnergy.getEnergyStored() == secondBefore,
                    "RS streaming preview consumed terminal energy");
                changes.clear();
                network.getItemStorageCache().reAttachListeners();
                helper.assertTrue(changes.contains(null), "RS cache reattachment did not invalidate all preview materials");
                try { subscription.close(); } catch (Exception exception) { throw new AssertionError(exception); }
                int notifications = changes.size();
                network.extractItem(new ItemStack(Items.EMERALD), 5, com.refinedmods.refinedstorage.api.util.IComparer.COMPARE_NBT,
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                network.getItemStorageCache().flush();
                helper.assertTrue(changes.size() == notifications, "Closed RS subscription received changes");
                helper.assertTrue(count(source, tagged(7, 1)) == 128 && count(source, tagged(8, 1)) == 16,
                    "Real RS snapshot count or NBT variants are incorrect");
                helper.assertTrue(energy.getEnergyStored() == before, "RS discovery or snapshot consumed energy");
                helper.assertTrue(source.extract(tagged(7, 1), 32, true).getCount() == 32,
                    "Real RS simulation failed despite available materials");
                helper.assertTrue(count(source, tagged(7, 1)) == 128 && energy.getEnergyStored() == before,
                    "Real RS simulated extraction changed materials or energy");
                helper.assertTrue(source.extract(tagged(9, 1), 1, false).isEmpty(), "Real RS extraction ignored exact NBT");
                helper.assertTrue(energy.getEnergyStored() == before, "Failed NBT extraction consumed terminal energy");
                ItemStack first = source.extract(tagged(7, 1), 32, false);
                helper.assertTrue(first.getCount() == 32 && ItemStack.isSameItemSameTags(first, tagged(7, 1)),
                    "Real RS first extraction returned incorrect items");
                ItemStack second = source.extract(tagged(7, 1), 8, false);
                helper.assertTrue(second.getCount() == 8 && count(source, tagged(7, 1)) == 88,
                    "Real RS repeated extraction has incorrect totals");
                var config = com.refinedmods.refinedstorage.RS.SERVER_CONFIG.getWirelessGrid();
                int expectedCost = config.getUseEnergy() ? Math.max(0, config.getOpenUsage()) + 2 * Math.max(0, config.getExtractUsage()) : 0;
                helper.assertTrue(energy.getEnergyStored() == before - expectedCost,
                    "RS did not charge one opening and two actual extraction operations");
                helper.assertTrue(secondEnergy.getEnergyStored() == secondBefore, "Deduplicated RS terminal was charged twice");
                int afterExtraction = energy.getEnergyStored();
                helper.assertTrue(source.insert(first).isEmpty() && source.insert(second).isEmpty(), "Real RS refund left items");
                helper.assertTrue(count(source, tagged(7, 1)) == 128 && count(source, tagged(8, 1)) == 16,
                    "Real RS refund lost, duplicated, or mixed items");
                helper.assertTrue(energy.getEnergyStored() == afterExtraction, "RS refund charged additional energy");
                source.finish(0);

                int beforeTopUpEnergy = energy.getEnergyStored();
                helper.assertTrue(TransferService.topUp(player, player.containerMenu.containerId,
                    player.containerMenu.getStateId(), List.of(tagged(8, 1))), "Real RS inventory top-up failed");
                int carried = player.getInventory().items.stream()
                    .filter(stack -> ItemStack.isSameItemSameTags(stack, tagged(8, 1)))
                    .mapToInt(ItemStack::getCount).sum();
                helper.assertTrue(carried == 1 && count(onlySource(helper, player), tagged(8, 1)) == 15,
                    "RS top-up did not conserve the requested material");
                helper.assertTrue(player.getInventory().getItem(0) == terminal,
                    "RS top-up replaced the wireless terminal stack instance");
                if (config.getUseEnergy()) helper.assertTrue(energy.getEnergyStored() < beforeTopUpEnergy,
                    "RS top-up restored or failed to charge terminal energy");

                ItemStack replacement = terminal.copy();
                player.getInventory().setItem(0, replacement);
                player.getInventory().setItem(1, ItemStack.EMPTY);
                helper.assertTrue(count(source, tagged(7, 1)) == 128, "Persistent RS source lost replaced same-network terminal");
                replacement.getOrCreateTag().putInt("NodeY", -1000);
                helper.assertTrue(source.snapshot(Set.of(Items.DIAMOND)).isEmpty(), "Persistent RS source ignored terminal rebinding");
                player.getInventory().setItem(0, terminal);
                player.getInventory().setItem(1, secondTerminal);

                helper.assertTrue(network.insertItem(tagged(7, 6000), 6000,
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM).isEmpty(), "RS count seed exceeded storage capacity");
                int beforeCountEnergy = energy.getEnergyStored();
                int beforeSecondCountEnergy = secondEnergy.getEnergyStored();
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> {
                    helper.assertTrue(stack.getCount() == 1, "RS matcher received aggregate quantities");
                    return ItemStack.isSameItemSameTags(stack, tagged(7, 1));
                }, Long.MAX_VALUE) == 6128, "RS count retained JEI cap or merged NBT variants");
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> true, 6100) == 6100,
                    "RS count ignored the requested limit");
                helper.assertTrue(source.changeIdentity() == network && energy.getEnergyStored() == beforeCountEnergy
                    && secondEnergy.getEnergyStored() == beforeSecondCountEnergy,
                    "RS count changed identity or consumed wireless terminal energy");
                helper.assertTrue(network.extractItem(tagged(7, 1), 6000,
                    com.refinedmods.refinedstorage.api.util.IComparer.COMPARE_NBT,
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM).getCount() == 6000,
                    "RS count changed the actual stored quantity");

                player.setPos(absolute.getX() + 10000.5, absolute.getY() + 1.5, absolute.getZ() + 0.5);
                helper.assertTrue(StorageSources.find(player).isEmpty(), "RS discovery ignored wireless range");
                helper.assertTrue(source.snapshot(Set.of(Items.DIAMOND)).isEmpty()
                    && source.extract(tagged(7, 1), 1, false).isEmpty(), "Old RS source allowed out-of-range access");
                assertUnavailablePreview(helper, source);
                helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> true, Long.MAX_VALUE) == 0,
                    "RS count ignored wireless range");
                player.setPos(absolute.getX() + 0.5, absolute.getY() + 1.5, absolute.getZ() + 0.5);
                if (config.getUseEnergy()) {
                    energy.extractEnergy(Integer.MAX_VALUE, false);
                    secondEnergy.extractEnergy(Integer.MAX_VALUE, false);
                    helper.assertTrue(StorageSources.find(player).isEmpty(), "Depleted bound RS terminals exposed storage");
                    assertUnavailablePreview(helper, source);
                    helper.assertTrue(source.countMatching(Items.DIAMOND, stack -> true, Long.MAX_VALUE) == 0,
                        "RS count accepted depleted wireless terminals");
                    energy.receiveEnergy(Integer.MAX_VALUE, false);
                }
                helper.assertTrue(count(onlySource(helper, player), tagged(7, 1)) == 128, "Returning into range did not restore access");
                helper.setBlock(transmitterPos, Blocks.AIR);
                helper.runAfterDelay(10, () -> {
                    helper.assertTrue(StorageSources.find(player).isEmpty(), "Removed real RS transmitter still allowed wireless access");
                    helper.assertTrue(network.getItemStorageCache().getList().getCount(tagged(7, 1)) == 128,
                        "Denied wireless operations changed stored materials");
                    helper.succeed();
                });
            });
        }

        private static void invalidNetworks(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            ItemStack terminal = new ItemStack(com.refinedmods.refinedstorage.RSItems.WIRELESS_GRID.get());
            player.getInventory().setItem(0, terminal);
            helper.assertTrue(StorageSources.find(player).isEmpty(), "Unbound wireless terminal exposed storage");
            var energy = terminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(() -> new AssertionError("Missing RS energy capability"));
            energy.receiveEnergy(Integer.MAX_VALUE, false);
            helper.assertTrue(energy.getEnergyStored() > 0, "RS terminal did not charge");
            helper.assertTrue(StorageSources.find(player).isEmpty(), "Charged but unbound terminal exposed storage");
            CompoundTag tag = terminal.getOrCreateTag();
            tag.putInt("NodeX", 0);
            tag.putInt("NodeY", -1000);
            tag.putInt("NodeZ", 0);
            tag.putString("Dimension", player.level().dimension().location().toString());
            int before = energy.getEnergyStored();
            helper.assertTrue(StorageSources.find(player).isEmpty(), "Nonexistent RS network exposed storage");
            helper.assertTrue(energy.getEnergyStored() == before, "Failed RS discovery consumed energy");
            tag.putString("Dimension", "invalid dimension");
            helper.assertTrue(StorageSources.find(player).isEmpty(), "Malformed bound dimension exposed storage");
            tag.putString("Dimension", "storage_fill:missing_dimension");
            helper.assertTrue(StorageSources.find(player).isEmpty(), "Absent bound dimension exposed storage");
        }

        private static void energy(GameTestHelper helper) {
            ItemStack terminal = new ItemStack(com.refinedmods.refinedstorage.RSItems.WIRELESS_GRID.get());
            var energy = terminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(() -> new AssertionError("Missing RS energy capability"));
            helper.assertTrue(energy.getEnergyStored() == 0 && energy.extractEnergy(1, true) == 0,
                "Empty RS terminal could simulate energy extraction");
            energy.receiveEnergy(100, false);
            int stored = energy.getEnergyStored();
            helper.assertTrue(stored == 100, "RS energy setup charge failed");
            CompoundTag before = terminal.serializeNBT().copy();
            helper.assertTrue(energy.extractEnergy(30, true) == 30, "RS simulation returned incorrect energy");
            helper.assertTrue(before.equals(terminal.serializeNBT()) && energy.getEnergyStored() == stored,
                "RS energy simulation changed real terminal NBT");
            helper.assertTrue(energy.extractEnergy(100, false) == 100 && energy.getEnergyStored() == 0,
                "Actual RS energy extraction failed");
            helper.assertTrue(energy.extractEnergy(1, true) == 0, "Depleted RS terminal allowed another extraction");
        }
    }
}

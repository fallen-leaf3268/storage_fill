package dev.airun.storagefill;

import dev.airun.storagefill.client.RemoteInventory;
import dev.airun.storagefill.client.CustomTransfer;
import dev.airun.storagefill.client.ClientTransfer;
import dev.airun.storagefill.client.RewardDestinationState;
import dev.airun.storagefill.compat.ftb.RewardDestination;
import dev.airun.storagefill.compat.ftb.RewardPreferences;
import dev.airun.storagefill.storage.StorageSource;
import dev.airun.storagefill.storage.StorageSources;
import dev.airun.storagefill.storage.RecipeQueryService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;

import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid = StorageFill.ID)
public final class FillNetwork {
    public static final int QUERY_CANCEL = 0;
    public static final int QUERY_START = 1;
    public static final int QUERY_NEXT = 2;
    public static final int QUERY_FINISH = 3;
    private static final Map<ServerPlayer, Rate> RATES = new WeakHashMap<>();
    private static final Deque<QueryJob> QUERY_JOBS = new ArrayDeque<>();
    private static final int MAX_QUERY_JOBS_PER_TICK = 16;
    private static final long QUERY_TICK_BUDGET_NANOS = 2_000_000;
    private static final StreamCodec<RegistryFriendlyByteBuf, ItemStack> STACK_CODEC =
            ItemStack.validatedStreamCodec(ItemStack.STREAM_CODEC);

    private FillNetwork() { }

    public static void remove(ServerPlayer player) {
        Rate rate = RATES.remove(player);
        if (rate != null) rate.close();
    }
    public static void clear() {
        RATES.values().forEach(Rate::close);
        RATES.clear();
        QUERY_JOBS.clear();
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("12").executesOn(net.neoforged.neoforge.network.registration.HandlerThread.MAIN);
        registrar.playToServer(Query.TYPE, codec(Query::encode, Query::decode), FillNetwork::query);
        registrar.playToClient(Snapshot.TYPE, codec(Snapshot::encode, Snapshot::decode), FillNetwork::snapshot);
        registrar.playToServer(Fill.TYPE, codec(Fill::encode, Fill::decode), FillNetwork::fill);
        registrar.playToServer(TopUp.TYPE, codec(TopUp::encode, TopUp::decode), FillNetwork::topUp);
        registrar.playToClient(TopUpResult.TYPE, codec(TopUpResult::encode, TopUpResult::decode), FillNetwork::topUpResult);
        registrar.playToClient(SnapshotFailure.TYPE, codec(SnapshotFailure::encode, SnapshotFailure::decode), FillNetwork::snapshotFailure);
        registrar.playToClient(FillResult.TYPE, codec(FillResult::encode, FillResult::decode), FillNetwork::fillResult);
        registrar.playToServer(RecipeQuery.TYPE, codec(RecipeQuery::encode, RecipeQuery::decode), FillNetwork::recipeQuery);
        registrar.playToClient(RecipeQueryBatch.TYPE, codec(RecipeQueryBatch::encode, RecipeQueryBatch::decode), FillNetwork::recipeQueryBatch);
        registrar.playToServer(RewardDestinationSelect.TYPE, codec(RewardDestinationSelect::encode, RewardDestinationSelect::decode), FillNetwork::rewardDestinationSelect);
        registrar.playToClient(RewardDestinationSync.TYPE, codec(RewardDestinationSync::encode, RewardDestinationSync::decode), FillNetwork::rewardDestinationSync);
    }

    private static <T extends CustomPacketPayload> StreamCodec<RegistryFriendlyByteBuf, T> codec(
            java.util.function.BiConsumer<T, RegistryFriendlyByteBuf> encoder,
            java.util.function.Function<RegistryFriendlyByteBuf, T> decoder) {
        return StreamCodec.of((buffer, value) -> encoder.accept(value, buffer), decoder::apply);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static boolean isRemotePresent(ICommonPacketListener connection) {
        return connection != null && connection.hasChannel(RecipeQuery.TYPE);
    }

    private static ServerPlayer sender(IPayloadContext context) {
        return context.player() instanceof ServerPlayer player ? player : null;
    }

    private static void runOnClient(Runnable action) {
        if (FMLEnvironment.dist == Dist.CLIENT) action.run();
    }

    public record RewardDestinationSelect(int target) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RewardDestinationSelect> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "reward_destination_select"));

        @Override
        public CustomPacketPayload.Type<RewardDestinationSelect> type() { return TYPE; }

        static void encode(RewardDestinationSelect value, RegistryFriendlyByteBuf buf) { buf.writeByte(value.target); }
        static RewardDestinationSelect decode(RegistryFriendlyByteBuf buf) { return new RewardDestinationSelect(readRewardDestination(buf)); }
    }

    public record RewardDestinationSync(int target) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RewardDestinationSync> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "reward_destination_sync"));

        @Override
        public CustomPacketPayload.Type<RewardDestinationSync> type() { return TYPE; }

        static void encode(RewardDestinationSync value, RegistryFriendlyByteBuf buf) { buf.writeByte(value.target); }
        static RewardDestinationSync decode(RegistryFriendlyByteBuf buf) { return new RewardDestinationSync(readRewardDestination(buf)); }
    }

    private static int readRewardDestination(RegistryFriendlyByteBuf buf) {
        if (buf.readableBytes() != 1) throw new IllegalArgumentException("Invalid reward destination message");
        int target = buf.readUnsignedByte();
        if (target > 4) throw new IllegalArgumentException("Invalid reward destination");
        return target;
    }

    private static void rewardDestinationSelect(RewardDestinationSelect message, IPayloadContext context) {
        ServerPlayer player = sender(context);
        if (player != null && net.neoforged.fml.ModList.get().isLoaded("ftbquests")) {
            RewardPreferences.set(player, RewardDestination.fromId(message.target));
            RewardPreferences.sync(player);
        }
    }

    private static void rewardDestinationSync(RewardDestinationSync message, IPayloadContext context) {
        runOnClient(() -> RewardDestinationState.accept(RewardDestination.fromId(message.target)));
    }

    private static List<ItemStack> readStacks(RegistryFriendlyByteBuf buf, int maximum, boolean largeCounts) {
        int size = buf.readVarInt();
        if (size < 0 || size > maximum) throw new IllegalArgumentException("Too many stacks");
        List<ItemStack> result = new ArrayList<>(size);
        int budget = 262144;
        for (int i = 0; i < size; i++) {
            int before = buf.readerIndex();
            ItemStack stack = STACK_CODEC.decode(buf);
            ItemStack.validateComponents(stack.getComponents()).getOrThrow(io.netty.handler.codec.DecoderException::new);
            int wireSize = buf.readerIndex() - before;
            if (wireSize > 16384 || stack.getCount() != 1)
                throw new IllegalArgumentException("Item data too large or noncanonical count");
            int decodedSize = StackKey.of(stack).encodedSize(buf.registryAccess());
            if (decodedSize > 16384 || (budget -= decodedSize) < 0)
                throw new IllegalArgumentException("Item component data too large");
            int count = buf.readVarInt();
            if (stack.isEmpty() || count < 1 || count > (largeCounts ? RequestLimits.MAX_COUNT : 64)) {
                throw new IllegalArgumentException("Invalid stack count");
            }
            stack.setCount(count);
            result.add(stack);
        }
        return result;
    }

    private static void writeStacks(RegistryFriendlyByteBuf buf, List<ItemStack> stacks, boolean largeCounts) {
        buf.writeVarInt(stacks.size());
        for (ItemStack stack : stacks) {
            STACK_CODEC.encode(buf, stack.copyWithCount(1));
            buf.writeVarInt(stack.getCount());
        }
    }

    public record Query(int menu, int request, List<ItemStack> candidates, boolean prepare) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Query> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "query"));

        @Override
        public CustomPacketPayload.Type<Query> type() { return TYPE; }

        public Query(int menu, int request, List<ItemStack> candidates) {
            this(menu, request, candidates, false);
        }
        static void encode(Query value, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(value.menu); buf.writeVarInt(value.request);
            buf.writeBoolean(value.prepare);
            buf.writeVarInt(value.candidates.size());
            value.candidates.forEach(stack -> buf.writeVarInt(Item.getId(stack.getItem())));
        }
        static Query decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() > 524288) throw new IllegalArgumentException("Query too large");
            int menu = buf.readVarInt(), request = buf.readVarInt();
            boolean prepare = buf.readBoolean();
            int size = buf.readVarInt();
            if (size != 0) throw new IllegalArgumentException("Unexpected session candidates");
            return new Query(menu, request, List.of(), prepare);
        }
    }

    public record RecipeQuery(int menu, int request, List<ItemStack> preferred, int action, FillPriority priority) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RecipeQuery> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "recipe_query"));

        @Override
        public CustomPacketPayload.Type<RecipeQuery> type() { return TYPE; }

        public RecipeQuery(int menu, int request, List<ItemStack> preferred, int action) {
            this(menu, request, preferred, action, FillPriority.DEFAULT);
        }
        static void encode(RecipeQuery value, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(value.menu); buf.writeVarInt(value.request); buf.writeVarInt(value.action);
            writeStacks(buf, value.preferred, false);
            value.priority.ids().forEach(buf::writeByte);
        }
        static RecipeQuery decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() > 262144) throw new IllegalArgumentException("Recipe query too large");
            int menu = buf.readVarInt(), request = buf.readVarInt(), action = buf.readVarInt();
            if (request <= 0 || action < QUERY_CANCEL || action > QUERY_FINISH) {
                throw new IllegalArgumentException("Invalid recipe query");
            }
            List<ItemStack> preferred = readStacks(buf, RequestLimits.MAX_CANDIDATES, false);
            if ((action == QUERY_START) == preferred.isEmpty()) throw new IllegalArgumentException("Invalid query candidates");
            if (buf.readableBytes() != 4) throw new IllegalArgumentException("Invalid fill priority size");
            List<Integer> ids = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) ids.add((int) buf.readUnsignedByte());
            return new RecipeQuery(menu, request, preferred, action, FillPriority.fromIds(ids));
        }
    }

    public record RecipeQueryBatch(int menu, int request, int batch, List<ItemStack> stacks,
                                   List<Integer> sourceIds, boolean exhausted, boolean truncated) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RecipeQueryBatch> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "recipe_query_batch"));

        @Override
        public CustomPacketPayload.Type<RecipeQueryBatch> type() { return TYPE; }

        static void encode(RecipeQueryBatch value, RegistryFriendlyByteBuf buf) {
            if (value.stacks.size() != value.sourceIds.size() || value.stacks.size() > 256) {
                throw new IllegalArgumentException("Invalid query result");
            }
            buf.writeVarInt(value.menu); buf.writeVarInt(value.request); buf.writeVarInt(value.batch);
            writeStacks(buf, value.stacks, true);
            value.sourceIds.forEach(buf::writeVarInt);
            buf.writeBoolean(value.exhausted); buf.writeBoolean(value.truncated);
        }
        static RecipeQueryBatch decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() > 262144) throw new IllegalArgumentException("Recipe result too large");
            int menu = buf.readVarInt(), request = buf.readVarInt(), batch = buf.readVarInt();
            if (request <= 0 || batch < 0 || batch >= 64) throw new IllegalArgumentException("Invalid query batch");
            List<ItemStack> stacks = readStacks(buf, 256, true);
            List<Integer> origins = new ArrayList<>(stacks.size());
            for (int index = 0; index < stacks.size(); index++) {
                int source = buf.readVarInt();
                if (source < 1 || source > 4) throw new IllegalArgumentException("Invalid query source");
                origins.add(source);
            }
            return new RecipeQueryBatch(menu, request, batch, stacks, origins, buf.readBoolean(), buf.readBoolean());
        }
    }

    public record Snapshot(int menu, int request, List<ItemStack> stacks, List<Integer> sourceIds,
                           int part, boolean last, boolean truncated, int revision, List<Item> covered, boolean reset) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Snapshot> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "snapshot"));

        @Override
        public CustomPacketPayload.Type<Snapshot> type() { return TYPE; }

        public Snapshot(int menu, int request, List<ItemStack> stacks, List<Integer> sourceIds,
                        int part, boolean last, boolean truncated, int revision, List<Item> covered) {
            this(menu, request, stacks, sourceIds, part, last, truncated, revision, covered, false);
        }
        public Snapshot(int menu, int request, List<ItemStack> stacks, List<Integer> sourceIds,
                        int part, boolean last, boolean truncated) {
            this(menu, request, stacks, sourceIds, part, last, truncated, 0, List.of());
        }
        public Snapshot(int menu, int request, List<ItemStack> stacks, List<Integer> sourceIds) {
            this(menu, request, stacks, sourceIds, 0, true, false);
        }
        public Snapshot {
            stacks = List.copyOf(stacks);
            sourceIds = List.copyOf(sourceIds);
            covered = List.copyOf(covered);
            if (stacks.size() > RequestLimits.MAX_SNAPSHOT || stacks.size() != sourceIds.size()
                    || sourceIds.stream().anyMatch(id -> id < 1 || id > 4)
                    || part < 0 || part >= RequestLimits.MAX_SESSION_STACKS || (truncated && !last)
                    || revision < 0 || covered.size() > 4096 || (part != 0 && (!covered.isEmpty() || reset))) {
                throw new IllegalArgumentException("Invalid snapshot sources");
            }
        }
        static void encode(Snapshot value, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(value.menu); buf.writeVarInt(value.request);
            buf.writeVarInt(value.part); buf.writeBoolean(value.last); buf.writeBoolean(value.truncated);
            buf.writeVarInt(value.revision);
            buf.writeBoolean(value.reset);
            buf.writeVarInt(value.covered.size());
            value.covered.forEach(item -> buf.writeVarInt(Item.getId(item)));
            writeStacks(buf, value.stacks, true);
            value.sourceIds.forEach(buf::writeByte);
        }
        static Snapshot decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() > 524288) throw new IllegalArgumentException("Snapshot too large");
            int menu = buf.readVarInt(), request = buf.readVarInt();
            int part = buf.readVarInt();
            boolean last = buf.readBoolean(), truncated = buf.readBoolean();
            int revision = buf.readVarInt();
            boolean reset = buf.readBoolean();
            int coverageSize = buf.readVarInt();
            if (coverageSize < 0 || coverageSize > 4096) throw new IllegalArgumentException("Too many covered items");
            List<Item> covered = new ArrayList<>(coverageSize);
            for (int i = 0; i < coverageSize; i++) {
                Item item = Item.byId(buf.readVarInt());
                if (item == net.minecraft.world.item.Items.AIR) throw new IllegalArgumentException("Invalid covered item");
                covered.add(item);
            }
            List<ItemStack> stacks = readStacks(buf, RequestLimits.MAX_SNAPSHOT, true);
            List<Integer> sources = new ArrayList<>(stacks.size());
            for (int i = 0; i < stacks.size(); i++) sources.add((int) buf.readUnsignedByte());
            return new Snapshot(menu, request, stacks, sources, part, last, truncated, revision, covered, reset);
        }
    }

    public record SnapshotFailure(int menu, int request, net.minecraft.network.chat.Component message) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SnapshotFailure> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "snapshot_failure"));

        @Override
        public CustomPacketPayload.Type<SnapshotFailure> type() { return TYPE; }

        static void encode(SnapshotFailure value, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(value.menu); buf.writeVarInt(value.request); ComponentSerialization.STREAM_CODEC.encode(buf, value.message);
        }
        static SnapshotFailure decode(RegistryFriendlyByteBuf buf) {
            return new SnapshotFailure(buf.readVarInt(), buf.readVarInt(), ComponentSerialization.STREAM_CODEC.decode(buf));
        }
    }

    public record Input(int slot, List<ItemStack> candidates) { }

    public record TopUp(int menu, int state, int request, int operation, List<ItemStack> required) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TopUp> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "top_up"));

        @Override
        public CustomPacketPayload.Type<TopUp> type() { return TYPE; }

        static void encode(TopUp value, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(value.menu); buf.writeVarInt(value.state);
            buf.writeVarInt(value.request); buf.writeVarInt(value.operation);
            writeStacks(buf, value.required, true);
        }
        static TopUp decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() > 524288) throw new IllegalArgumentException("Top-up too large");
            return new TopUp(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    readStacks(buf, RequestLimits.MAX_SLOTS, true));
        }
    }

    public record TopUpResult(int menu, int operation, boolean success, net.minecraft.network.chat.Component message) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TopUpResult> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "top_up_result"));

        @Override
        public CustomPacketPayload.Type<TopUpResult> type() { return TYPE; }

        public TopUpResult(int menu, int operation, boolean success) {
            this(menu, operation, success, success ? net.minecraft.network.chat.Component.empty()
                    : net.minecraft.network.chat.Component.translatable("storage_fill.failed"));
        }
        static void encode(TopUpResult value, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(value.menu); buf.writeVarInt(value.operation); buf.writeBoolean(value.success);
            ComponentSerialization.STREAM_CODEC.encode(buf, value.message);
        }
        static TopUpResult decode(RegistryFriendlyByteBuf buf) {
            return new TopUpResult(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), ComponentSerialization.STREAM_CODEC.decode(buf));
        }
    }

    public record Fill(int menu, int state, int request, List<Input> inputs, int[] inventory,
                       boolean maximum, boolean completeSets, int operation) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Fill> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "fill"));

        @Override
        public CustomPacketPayload.Type<Fill> type() { return TYPE; }

        public Fill(int menu, int state, int request, List<Input> inputs, int[] inventory,
                    boolean maximum, boolean completeSets) {
            this(menu, state, request, inputs, inventory, maximum, completeSets, 0);
        }
        static void encode(Fill value, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(value.menu); buf.writeVarInt(value.state); buf.writeVarInt(value.request);
            buf.writeVarInt(value.operation);
            buf.writeVarInt(value.inputs.size());
            for (Input input : value.inputs) {
                buf.writeVarInt(input.slot);
                writeStacks(buf, input.candidates, false);
            }
            buf.writeVarIntArray(value.inventory);
            buf.writeBoolean(value.maximum); buf.writeBoolean(value.completeSets);
        }
        static Fill decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() > 524288) throw new IllegalArgumentException("Fill too large");
            int menu = buf.readVarInt(), state = buf.readVarInt(), request = buf.readVarInt();
            int operation = buf.readVarInt();
            int size = buf.readVarInt(), budget = RequestLimits.MAX_CANDIDATES;
            if (size < 1 || size > RequestLimits.MAX_SLOTS) throw new IllegalArgumentException("Invalid inputs");
            List<Input> inputs = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int slot = buf.readVarInt();
                List<ItemStack> candidates = readStacks(buf, budget, false);
                budget -= candidates.size();
                inputs.add(new Input(slot, candidates));
            }
            return new Fill(menu, state, request, inputs, buf.readVarIntArray(256), buf.readBoolean(), buf.readBoolean(), operation);
        }
    }

    public record FillResult(int menu, int operation, boolean success, net.minecraft.network.chat.Component message) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<FillResult> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(StorageFill.ID, "fill_result"));

        @Override
        public CustomPacketPayload.Type<FillResult> type() { return TYPE; }

        static void encode(FillResult value, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(value.menu); buf.writeVarInt(value.operation); buf.writeBoolean(value.success); ComponentSerialization.STREAM_CODEC.encode(buf, value.message);
        }
        static FillResult decode(RegistryFriendlyByteBuf buf) {
            return new FillResult(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), ComponentSerialization.STREAM_CODEC.decode(buf));
        }
    }

    private static void query(Query query, IPayloadContext context) {
        ServerPlayer player = sender(context);
        requestSnapshot(player, query);
    }

    static void requestSnapshot(ServerPlayer player, Query query) {
        if (player == null || query.prepare) return;
        Rate rate = RATES.get(player);
        if (rate != null && rate.request == query.request && rate.menu == player.containerMenu
            && rate.menu.containerId == query.menu) rate.close();
    }

    private static void recipeQuery(RecipeQuery query, IPayloadContext context) {
        requestRecipeQuery(sender(context), query);
    }

    static void requestRecipeQuery(ServerPlayer player, RecipeQuery query) {
        if (player == null || query.request <= 0 || query.action < QUERY_CANCEL || query.action > QUERY_FINISH
            || (query.action == QUERY_START) == query.preferred.isEmpty()
            || player.containerMenu.containerId != query.menu || !player.containerMenu.stillValid(player)) return;
        Rate rate = RATES.get(player);
        if (query.action == QUERY_START) {
            if (rate != null && query.request <= rate.request) return;
            if (rate == null) {
                rate = new Rate();
                RATES.put(player, rate);
            }
            rate.close();
            rate.menu = player.containerMenu;
            rate.request = query.request;
            rate.priority = query.priority;
            rate.session = RecipeQueryService.start(query.preferred, rate.priority, player.registryAccess());
            rate.activityTick = player.level().getGameTime();
            enqueueQuery(player, rate);
            return;
        }
        if (rate == null || rate.menu != player.containerMenu || query.request != rate.request) return;
        rate.activityTick = player.level().getGameTime();
        if (query.action == QUERY_CANCEL) {
            rate.close();
        } else if (query.action == QUERY_FINISH && rate.awaitingClient && rate.session != null) {
            RecipeQueryService.cancel(rate.session);
            rate.session = null;
            rate.awaitingClient = false;
            rate.ready = true;
        } else if (query.action == QUERY_NEXT && rate.awaitingClient && !rate.exhausted
                && rate.session != null && !rate.ready) {
            rate.awaitingClient = false;
            enqueueQuery(player, rate);
        }
    }

    private static void recipeQueryBatch(RecipeQueryBatch batch, IPayloadContext context) {
        runOnClient(() -> RemoteInventory.acceptRecipe(batch));
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void serverTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var iterator = RATES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ServerPlayer player = entry.getKey();
            Rate rate = entry.getValue();
            long tick = player.level().getGameTime();
            if (player.isRemoved()) {
                rate.close();
                iterator.remove();
                continue;
            }
            if (rate.menu != null && (player.containerMenu != rate.menu || !rate.menu.stillValid(player))) rate.close();
            if (rate.menu == null) {
                if (tick - rate.queryTick >= 20) iterator.remove();
            } else if (!rate.ready && tick - rate.activityTick > 600) {
                failQuery(player, rate, null);
            }
        }
        advanceQueries();
    }

    private static void enqueueQuery(ServerPlayer player, Rate rate) {
        if (rate.session != null && !rate.queued) {
            rate.queued = true;
            QUERY_JOBS.addLast(new QueryJob(player, rate));
        }
    }

    private static void advanceQueries() {
        int queued = QUERY_JOBS.size(), completed = 0;
        long started = System.nanoTime();
        while (queued-- > 0 && completed < MAX_QUERY_JOBS_PER_TICK) {
            QueryJob job = QUERY_JOBS.removeFirst();
            Rate rate = job.rate();
            ServerPlayer player = job.player();
            if (rate.menu == null || RATES.get(player) != rate || rate.session == null
                || player.containerMenu != rate.menu || !rate.menu.stillValid(player)) {
                rate.queued = false;
                continue;
            }
            if (player.level().getGameTime() - rate.queryTick < 2) {
                QUERY_JOBS.addLast(job);
                continue;
            }
            rate.queued = false;
            rate.queryTick = player.level().getGameTime();
            rate.activityTick = rate.queryTick;
            try {
                RecipeQueryService.Batch result = RecipeQueryService.next(player, rate.session);
                rate.exhausted = result.exhausted() || result.truncated() || rate.batch >= 63;
                RecipeQueryBatch batch = new RecipeQueryBatch(rate.menu.containerId, rate.request, rate.batch++,
                    result.stacks(), result.sourceIds(), rate.exhausted,
                    result.truncated() || (!result.exhausted() && rate.batch >= 64));
                rate.awaitingClient = true;
                sendToPlayer(player, batch);
            } catch (RuntimeException | LinkageError error) {
                failQuery(player, rate, error);
            }
            completed++;
            if (System.nanoTime() - started >= QUERY_TICK_BUDGET_NANOS) break;
        }
    }

    private static void failQuery(ServerPlayer player, Rate rate, Throwable error) {
        if (error != null) StorageFill.LOGGER.error("Unable to query recipe storage inventory", error);
        try {
            sendToPlayer(player, new SnapshotFailure(rate.menu.containerId, rate.request,
                net.minecraft.network.chat.Component.translatable("storage_fill.snapshot_failed")));
        } catch (RuntimeException | LinkageError sendError) {
            StorageFill.LOGGER.error("Unable to send recipe query failure", sendError);
        } finally {
            rate.close();
        }
    }

    static List<Snapshot> collectInitialSnapshot(int menu, int request, List<StorageSource> sources, net.minecraft.core.HolderLookup.Provider registries) {
        Map<OriginKey, Integer> counts = new LinkedHashMap<>();
        Set<Item> items = new LinkedHashSet<>();
        long[] bytes = {0};
        int[] visited = {0};
        boolean[] truncated = {false};
        for (StorageSource source : sources) {
            boolean complete = source.visitPreview(stack -> {
                if (++visited[0] > RequestLimits.MAX_SESSION_STACKS) return false;
                if (stack.isEmpty() || stack.getCount() <= 0) return true;
                OriginKey key = new OriginKey(StackKey.of(stack), source.sourceId());
                int size = key.stack().encodedSize(registries);
                if (size > 16384) { truncated[0] = true; return true; }
                if (!counts.containsKey(key)) {
                    if ((!items.contains(stack.getItem()) && items.size() >= 4096)
                        || counts.size() >= RequestLimits.MAX_SESSION_STACKS
                        || bytes[0] + size + 64 > RequestLimits.MAX_SESSION_BYTES) return false;
                    bytes[0] += size + 64;
                    items.add(stack.getItem());
                }
                counts.merge(key, Math.min(RequestLimits.MAX_COUNT, stack.getCount()),
                    (a, b) -> Math.min(RequestLimits.MAX_COUNT, a + b));
                return true;
            });
            if (!complete) { truncated[0] = true; break; }
        }
        return pages(menu, request, counts, truncated[0], registries);
    }

    private static List<Snapshot> pages(int menu, int request, Map<OriginKey, Integer> counts,
                                        boolean truncated, net.minecraft.core.HolderLookup.Provider registries) {
        List<Snapshot> pages = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        List<Integer> origins = new ArrayList<>();
        int pageBytes = 0;
        for (var entry : counts.entrySet()) {
            int size = entry.getKey().stack().encodedSize(registries) + 64;
            if (!stacks.isEmpty() && (stacks.size() >= RequestLimits.MAX_SNAPSHOT
                    || pageBytes + size > RequestLimits.MAX_SNAPSHOT_BYTES)) {
                pages.add(new Snapshot(menu, request, stacks, origins, pages.size(), false, false));
                stacks = new ArrayList<>();
                origins = new ArrayList<>();
                pageBytes = 0;
            }
            stacks.add(entry.getKey().stack().stack(entry.getValue()));
            origins.add(entry.getKey().source());
            pageBytes += size;
        }
        pages.add(new Snapshot(menu, request, stacks, origins, pages.size(), true, truncated));
        return pages;
    }

    private static void snapshot(Snapshot snapshot, IPayloadContext context) {
        runOnClient(() -> RemoteInventory.accept(snapshot));
    }

    private static void snapshotFailure(SnapshotFailure failure, IPayloadContext context) {
        runOnClient(() -> RemoteInventory.fail(failure.menu, failure.request, failure.message));
    }

    private static void fill(Fill fill, IPayloadContext context) {
        ServerPlayer player = sender(context);
        if (player == null) return;
        Rate rate = RATES.get(player);
        long tick = player.level().getGameTime();
        if (fill.operation <= 0 || (rate != null && fill.operation <= rate.fillOperation)) return;
        TransferResult result = TransferResult.failure(net.minecraft.network.chat.Component.translatable("storage_fill.failure.stale_session"));
        if (rate != null && rate.menu == player.containerMenu && fill.request == rate.request && rate.ready) {
            rate.fillOperation = fill.operation;
            if (tick - rate.fillTick >= 2) {
                rate.fillTick = tick;
                result = TransferService.fillDetailed(player, fill, rate.priority);
            } else {
                result = TransferResult.failure(net.minecraft.network.chat.Component.translatable("storage_fill.failure.too_fast"));
            }
        }
        sendToPlayer(player, new FillResult(fill.menu, fill.operation, result.success(), result.message()));
    }

    private record OriginKey(StackKey stack, int source) { }

    private static void fillResult(FillResult result, IPayloadContext context) {
        runOnClient(() -> ClientTransfer.accept(result));
    }

    private static void topUp(TopUp request, IPayloadContext context) {
        ServerPlayer player = sender(context);
        if (player == null) return;
        Rate rate = RATES.get(player);
        long tick = player.level().getGameTime();
        if (request.operation <= 0 || (rate != null && request.operation <= rate.topUpOperation)) return;
        TransferResult result = new TransferResult(false,
                net.minecraft.network.chat.Component.translatable("storage_fill.failure.stale_session"));
        if (rate != null && rate.menu == player.containerMenu && request.request == rate.request
                && rate.ready && tick - rate.fillTick >= 2
                && request.operation > rate.topUpOperation) {
            rate.fillTick = tick;
            rate.topUpOperation = request.operation;
            result = TransferService.topUpDetailed(player, request.menu, request.state, request.required, rate.priority);
        } else if (rate != null && rate.menu == player.containerMenu && rate.ready
                && request.request == rate.request && tick - rate.fillTick < 2) {
            result = new TransferResult(false,
                    net.minecraft.network.chat.Component.translatable("storage_fill.failure.too_fast"));
        }
        sendToPlayer(player,
                new TopUpResult(request.menu, request.operation, result.success(), result.message()));
    }

    private static void topUpResult(TopUpResult result, IPayloadContext context) {
        runOnClient(() -> CustomTransfer.accept(result));
    }

    private static final class Rate {
        long queryTick = Long.MIN_VALUE / 2;
        long activityTick;
        long fillTick = Long.MIN_VALUE / 2;
        AbstractContainerMenu menu;
        int request;
        int topUpOperation;
        int fillOperation;
        boolean ready;
        boolean queued;
        boolean awaitingClient;
        boolean exhausted;
        int batch;
        RecipeQueryService.Session session;
        FillPriority priority = FillPriority.DEFAULT;

        void close() {
            QUERY_JOBS.removeIf(job -> job.rate() == this);
            if (session != null) RecipeQueryService.cancel(session);
            session = null;
            menu = null;
            ready = false;
            queued = false;
            awaitingClient = false;
            exhausted = false;
            batch = 0;
            priority = FillPriority.DEFAULT;
        }
    }

    private record QueryJob(ServerPlayer player, Rate rate) { }
}

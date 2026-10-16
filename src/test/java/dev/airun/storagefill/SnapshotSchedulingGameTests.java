package dev.airun.storagefill;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class SnapshotSchedulingGameTests {
    @GameTest(template = "empty", batch = "snapshot_scheduling")
    public static void legacyOpeningDoesNotCreateWarehouseWork(GameTestHelper helper) throws Exception {
        ServerPlayer player = player(helper);
        try {
            FillNetwork.requestSnapshot(player, new FillNetwork.Query(player.containerMenu.containerId, 1, List.of(), true));
            helper.assertTrue(rate(player) == null, "Legacy opening retained automatic warehouse work");
        } finally {
            FillNetwork.remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "snapshot_scheduling")
    public static void replacingQueryCancelsOldWorkAndIgnoresLateControls(GameTestHelper helper) throws Exception {
        ServerPlayer player = player(helper);
        try {
            start(player, 1);
            Object rate = rate(player);
            Object first = field(rate, "session");
            start(player, 2);
            helper.assertTrue(field(rate, "session") != first && queued(rate) == 1 && (int) field(rate, "request") == 2,
                "Replacing A with B did not replace the single queued session");
            control(player, 1, FillNetwork.QUERY_CANCEL);
            control(player, 1, FillNetwork.QUERY_FINISH);
            helper.assertTrue(field(rate, "session") != null && queued(rate) == 1 && !(boolean) field(rate, "ready"),
                "A late control changed query B");
            tick(helper);
            helper.assertTrue((int) field(rate, "batch") == 1 && !(boolean) field(rate, "ready"),
                "A query became transferable before the client selected its recipe plan");
            control(player, 2, FillNetwork.QUERY_FINISH);
            helper.assertTrue((boolean) field(rate, "ready") && field(rate, "session") == null && queued(rate) == 0,
                "Finishing did not retain only a transferable request marker");
            start(player, 3);
            helper.assertFalse((boolean) field(rate, "ready"), "Query C retained query B transfer permission");
            control(player, 3, FillNetwork.QUERY_CANCEL);
            helper.assertTrue(field(rate, "menu") == null && field(rate, "session") == null && queued(rate) == 0,
                "Closing retained query candidates or queued native work");
        } finally {
            FillNetwork.remove(player);
        }
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            new GameProfile(UUID.randomUUID(), "snapshot-schedule"));
        player.connection = new ServerGamePacketListenerImpl(player.server, new Connection(PacketFlow.SERVERBOUND), player);
        return player;
    }

    @GameTest(template = "empty", batch = "snapshot_scheduling")
    public static void duplicateAndPrematureControlsDoNotRunExtraQueries(GameTestHelper helper) throws Exception {
        ServerPlayer player = player(helper);
        try {
            start(player, 1);
            Object rate = rate(player), session = field(rate, "session");
            start(player, 1);
            control(player, 1, FillNetwork.QUERY_NEXT);
            control(player, 1, FillNetwork.QUERY_FINISH);
            helper.assertTrue(field(rate, "session") == session && queued(rate) == 1 && !(boolean) field(rate, "ready"),
                "Duplicate start or premature controls duplicated work or enabled transfer");
            tick(helper);
            control(player, 1, FillNetwork.QUERY_NEXT);
            helper.assertTrue(queued(rate) == 0 && (int) field(rate, "batch") == 1,
                "Exhausted query accepted additional native reads");
            control(player, 1, FillNetwork.QUERY_FINISH);
            helper.assertTrue((boolean) field(rate, "ready"), "Completed first response could not be finalized");
        } finally {
            FillNetwork.remove(player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "snapshot_scheduling")
    public static void changedMenuAndAbandonedQueryReleaseQueuedWork(GameTestHelper helper) throws Exception {
        ServerPlayer player = player(helper);
        try {
            start(player, 1);
            Object rate = rate(player);
            player.containerMenu = new net.minecraft.world.inventory.InventoryMenu(player.getInventory(), false, player);
            tick(helper);
            helper.assertTrue(field(rate, "menu") == null && field(rate, "session") == null && queued(rate) == 0,
                "Changing the menu retained remote query work");
            start(player, 2);
            rate = rate(player);
            Field activity = rate.getClass().getDeclaredField("activityTick");
            activity.setAccessible(true);
            activity.setLong(rate, player.level().getGameTime() - 601);
            tick(helper);
            helper.assertTrue(field(rate, "menu") == null && field(rate, "session") == null && queued(rate) == 0,
                "Abandoned query did not expire");
        } finally {
            FillNetwork.remove(player);
        }
        helper.succeed();
    }

    private static void start(ServerPlayer player, int request) {
        FillNetwork.requestRecipeQuery(player, new FillNetwork.RecipeQuery(player.containerMenu.containerId, request,
            List.of(new ItemStack(Items.DIAMOND)), FillNetwork.QUERY_START));
    }

    private static void control(ServerPlayer player, int request, int action) {
        FillNetwork.requestRecipeQuery(player, new FillNetwork.RecipeQuery(player.containerMenu.containerId, request, List.of(), action));
    }

    private static void tick(GameTestHelper helper) {
        FillNetwork.serverTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END, () -> true, helper.getLevel().getServer()));
    }

    private static long queued(Object rate) throws Exception {
        return ((Deque<?>) field(FillNetwork.class, "QUERY_JOBS")).stream().filter(job -> {
            try { return field(job, "rate") == rate; }
            catch (Exception error) { throw new AssertionError(error); }
        }).count();
    }

    private static Object rate(ServerPlayer player) throws Exception {
        return ((Map<?, ?>) field(FillNetwork.class, "RATES")).get(player);
    }

    private static Object field(Object owner, String name) throws Exception {
        Class<?> type = owner instanceof Class<?> clazz ? clazz : owner.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner instanceof Class<?> ? null : owner);
    }
}

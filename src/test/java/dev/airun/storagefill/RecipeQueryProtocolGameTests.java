package dev.airun.storagefill;

import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class RecipeQueryProtocolGameTests {
    @GameTest(template = "empty", batch = "recipe_query_protocol")
    public static void queryAndBatchPreserveConcreteNbtAndShiftQuantity(GameTestHelper helper) {
        ItemStack first = new ItemStack(Items.IRON_INGOT, 1);
        first.getOrCreateTag().putInt("variant", 1);
        ItemStack second = first.copy();
        second.getOrCreateTag().putInt("variant", 2);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var query = new FillNetwork.RecipeQuery(4, 7, List.of(first, second), FillNetwork.QUERY_START);
            FillNetwork.RecipeQuery.encode(query, buffer);
            var decoded = FillNetwork.RecipeQuery.decode(buffer);
            helper.assertTrue(decoded.menu() == 4 && decoded.request() == 7 && decoded.action() == FillNetwork.QUERY_START
                && decoded.preferred().size() == 2 && !StackKey.of(decoded.preferred().get(0)).equals(StackKey.of(decoded.preferred().get(1))),
                "Query protocol lost concrete NBT alternatives");
            buffer.clear();
            FillNetwork.RecipeQueryBatch.encode(new FillNetwork.RecipeQueryBatch(4, 7, 0,
                List.of(first.copyWithCount(RequestLimits.MAX_COUNT), second.copyWithCount(64)), List.of(1, 4), true, false), buffer);
            var batch = FillNetwork.RecipeQueryBatch.decode(buffer);
            helper.assertTrue(batch.stacks().get(0).getCount() == RequestLimits.MAX_COUNT
                && batch.stacks().get(1).getCount() == 64 && batch.sourceIds().equals(List.of(1, 4))
                && batch.exhausted() && !batch.truncated(), "Query batch cropped shift stock or changed origins");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_protocol")
    public static void malformedQueryActionsCandidatesAndOversizedPacketsAreRejected(GameTestHelper helper) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(0).writeVarInt(1).writeVarInt(FillNetwork.QUERY_START).writeVarInt(0);
            helper.assertTrue(rejected(() -> FillNetwork.RecipeQuery.decode(buffer)), "Empty START was accepted");
            buffer.clear();
            FillNetwork.RecipeQuery.encode(new FillNetwork.RecipeQuery(0, 1, List.of(new ItemStack(Items.IRON_INGOT)), FillNetwork.QUERY_NEXT), buffer);
            helper.assertTrue(rejected(() -> FillNetwork.RecipeQuery.decode(buffer)), "NEXT accepted new candidates");
            buffer.clear();
            buffer.writeVarInt(0).writeVarInt(1).writeVarInt(4).writeVarInt(0);
            helper.assertTrue(rejected(() -> FillNetwork.RecipeQuery.decode(buffer)), "Unknown query action was accepted");
            buffer.clear();
            buffer.writeZero(262145);
            helper.assertTrue(rejected(() -> FillNetwork.RecipeQuery.decode(buffer)), "Oversized query packet was accepted");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "recipe_query_protocol")
    public static void invalidBatchOriginsAndOutOfRangeSequenceAreRejected(GameTestHelper helper) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FillNetwork.RecipeQueryBatch.encode(new FillNetwork.RecipeQueryBatch(0, 1, 0,
                List.of(new ItemStack(Items.IRON_INGOT)), List.of(5), true, false), buffer);
            helper.assertTrue(rejected(() -> FillNetwork.RecipeQueryBatch.decode(buffer)), "Invalid storage origin was accepted");
            buffer.clear();
            buffer.writeVarInt(0).writeVarInt(1).writeVarInt(64);
            helper.assertTrue(rejected(() -> FillNetwork.RecipeQueryBatch.decode(buffer)), "Unbounded query batch sequence was accepted");
            buffer.clear();
            buffer.writeVarInt(0).writeVarInt(1).writeVarInt(0).writeVarInt(257);
            helper.assertTrue(rejected(() -> FillNetwork.RecipeQueryBatch.decode(buffer)), "Too many batch rows were accepted");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static boolean rejected(Runnable action) {
        try { action.run(); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }
}

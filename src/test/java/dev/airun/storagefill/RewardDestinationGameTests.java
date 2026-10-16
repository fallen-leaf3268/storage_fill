package dev.airun.storagefill;

import com.mojang.authlib.GameProfile;
import dev.airun.storagefill.compat.ftb.RewardDestination;
import dev.airun.storagefill.compat.ftb.RewardPreferences;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("storage_fill")
@PrefixGameTestTemplate(false)
public final class RewardDestinationGameTests {
    @GameTest(template = "empty")
    public static void rewardDestinationDefaultsToPlayerInventory(GameTestHelper helper) {
        helper.assertTrue(RewardPreferences.get(player(helper)) == RewardDestination.INVENTORY,
            "A new player did not default to player inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardPreferencePreservesOtherPlayerData(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        var saved = new net.minecraft.nbt.CompoundTag();
        saved.putString("foreign_player_setting", "keep");
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, saved);
        for (RewardDestination destination : RewardDestination.values()) {
            RewardPreferences.set(player, destination);
            helper.assertTrue(RewardPreferences.get(player) == (destination.available() ? destination : RewardDestination.INVENTORY),
                "Reward preference did not preserve an available target or reject an absent target");
            helper.assertTrue(player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG)
                .getString("foreign_player_setting").equals("keep"), "Reward preference overwrote unrelated player data");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardPreferenceSurvivesNativePlayerClone(GameTestHelper helper) {
        ServerPlayer original = player(helper);
        RewardDestination target = java.util.Arrays.stream(RewardDestination.values()).filter(RewardDestination::available)
            .reduce((first, second) -> second).orElseThrow();
        RewardPreferences.set(original, target);
        ServerPlayer cloned = player(helper);
        cloned.restoreFrom(original, true);
        helper.assertTrue(RewardPreferences.get(cloned) == target, "Native player clone lost the reward preference");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidSavedRewardDestinationFallsBackToInventory(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        var saved = new net.minecraft.nbt.CompoundTag();
        saved.putByte("storage_fill_reward_destination", (byte) 255);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, saved);
        helper.assertTrue(RewardPreferences.get(player) == RewardDestination.INVENTORY, "Invalid saved target escaped inventory fallback");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardDestinationMessagesRoundTripEveryTarget(GameTestHelper helper) {
        for (RewardDestination target : RewardDestination.values()) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                FillNetwork.RewardDestinationSelect.encode(new FillNetwork.RewardDestinationSelect(target.id()), buffer);
                helper.assertTrue(buffer.readableBytes() == 1, "Reward selection serialized more than its target");
                helper.assertTrue(FillNetwork.RewardDestinationSelect.decode(buffer).target() == target.id() && !buffer.isReadable(),
                    "Reward selection changed its target");
                FillNetwork.RewardDestinationSync.encode(new FillNetwork.RewardDestinationSync(target.id()), buffer);
                helper.assertTrue(FillNetwork.RewardDestinationSync.decode(buffer).target() == target.id() && !buffer.isReadable(),
                    "Reward acknowledgement changed its target");
            } finally { buffer.release(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardDestinationMessagesRejectMalformedPayloads(GameTestHelper helper) {
        for (byte[] payload : new byte[][]{new byte[0], new byte[]{5}, new byte[]{-1}, new byte[]{0, 1}}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
            try {
                boolean rejected = false;
                try { FillNetwork.RewardDestinationSelect.decode(buffer); }
                catch (IllegalArgumentException error) { rejected = true; }
                helper.assertTrue(rejected, "Malformed reward selection was accepted");
                buffer.readerIndex(0);
                rejected = false;
                try { FillNetwork.RewardDestinationSync.decode(buffer); }
                catch (IllegalArgumentException error) { rejected = true; }
                helper.assertTrue(rejected, "Malformed reward acknowledgement was accepted");
            } finally { buffer.release(); }
        }
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            new GameProfile(UUID.randomUUID(), "RewardPrefTest"));
        player.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(),
            new Connection(PacketFlow.SERVERBOUND), player);
        return player;
    }
}

package com.moulberry.flashback.record;

import com.moulberry.flashback.gui.GuiPlayback;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;

import java.util.Set;

/** Packet classes that are deliberately omitted from or skipped during replay playback. */
public class IgnoredPacketSet {

    public static boolean isIgnored(Packet<?> packet) {
        return isIgnoredType(packet.getClass());
    }

    public static boolean isIgnoredType(Class<?> packetClass) {
        if (CONTAINER_PACKETS.contains(packetClass) && !GuiPlayback.enabled()) {
            return true;
        }
        return IGNORED.contains(packetClass);
    }

    public static boolean isIgnoredInReplay(Packet<?> packet) {
        return IGNORED_IN_REPLAY.contains(packet.getClass());
    }

    public static Set<Class<?>> containerPackets() {
        return CONTAINER_PACKETS;
    }

    public static Set<Class<?>> alwaysIgnored() {
        return IGNORED;
    }

    /** Container streams are optional because slot updates are among the largest packet sources. */
    private static final Set<Class<?>> CONTAINER_PACKETS = Set.of(
        ClientboundOpenScreenPacket.class,
        ClientboundContainerClosePacket.class,
        ClientboundContainerSetContentPacket.class,
        ClientboundContainerSetDataPacket.class,
        ClientboundContainerSetSlotPacket.class,
        ClientboundMerchantOffersPacket.class,
        ClientboundHorseScreenOpenPacket.class
    );

    /** These packets are recorded for compatibility but have no effect on the replay world. */
    private static final Set<Class<?>> IGNORED_IN_REPLAY = Set.of(
        ClientboundAwardStatsPacket.class,
        ClientboundRecipePacket.class,
        ClientboundUpdateRecipesPacket.class,
        ClientboundTransferPacket.class,
        ClientboundUpdateAdvancementsPacket.class
    );

    private static final Set<Class<?>> IGNORED = Set.of(
        // Added directly by mixin/record/MixinClientLevel.
        ClientboundLevelEventPacket.class,
        ClientboundSoundPacket.class,
        ClientboundSoundEntityPacket.class,

        // Common protocol packets that are either handled elsewhere or are not replay state.
        ClientboundStoreCookiePacket.class,
        ClientboundCustomReportDetailsPacket.class,
        ClientboundServerLinksPacket.class,
        ClientboundCookieRequestPacket.class,
        ClientboundDisconnectPacket.class,
        ClientboundPingPacket.class,
        ClientboundKeepAlivePacket.class,
        ClientboundTransferPacket.class,

        // Configuration transition packets are handled by the replay server itself.
        ClientboundFinishConfigurationPacket.class,

        // Game protocol packets that the replay server derives, forwards, or deliberately does not apply.
        ClientboundAwardStatsPacket.class,
        ClientboundRecipePacket.class,
        ClientboundOpenSignEditorPacket.class,
        ClientboundRotateHeadPacket.class,
        ClientboundMoveEntityPacket.Pos.class,
        ClientboundMoveEntityPacket.Rot.class,
        ClientboundMoveEntityPacket.PosRot.class,
        ClientboundPlayerPositionPacket.class,
        ClientboundPlayerChatPacket.class,
        ClientboundDeleteChatPacket.class,
        ClientboundForgetLevelChunkPacket.class,
        ClientboundPlayerAbilitiesPacket.class,
        ClientboundSetCarriedItemPacket.class,
        ClientboundSetExperiencePacket.class,
        ClientboundSetHealthPacket.class,
        ClientboundPlayerCombatEndPacket.class,
        ClientboundPlayerCombatEnterPacket.class,
        ClientboundPlayerCombatKillPacket.class,
        ClientboundSetCameraPacket.class,
        ClientboundCooldownPacket.class,
        ClientboundUpdateAdvancementsPacket.class,
        ClientboundSelectAdvancementsTabPacket.class,
        ClientboundPlaceGhostRecipePacket.class,
        ClientboundCommandsPacket.class,
        ClientboundCommandSuggestionsPacket.class,
        ClientboundUpdateRecipesPacket.class,
        ClientboundTagQueryPacket.class,
        ClientboundOpenBookPacket.class,
        ClientboundSetChunkCacheRadiusPacket.class,
        ClientboundSetSimulationDistancePacket.class,
        ClientboundSetChunkCacheCenterPacket.class,
        ClientboundBlockChangedAckPacket.class,
        ClientboundCustomChatCompletionsPacket.class,
        ClientboundStartConfigurationPacket.class,
        ClientboundChunkBatchStartPacket.class,
        ClientboundChunkBatchFinishedPacket.class,
        ClientboundPongResponsePacket.class
    );
}

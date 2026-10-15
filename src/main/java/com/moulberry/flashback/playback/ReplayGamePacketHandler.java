package com.moulberry.flashback.playback;

import com.moulberry.flashback.gui.GuiDisplayForwarder;
import com.moulberry.flashback.gui.GuiPlayback;
import com.moulberry.flashback.gui.InventoryMenuSlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MerchantMenu;
import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import com.mojang.authlib.GameProfile;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.PacketHelper;
import com.moulberry.flashback.exception.UnsupportedPacketException;
import com.moulberry.flashback.ext.LevelChunkExt;
import com.moulberry.flashback.ext.ServerLevelExt;
import com.moulberry.flashback.ext.ThreadedLevelLightEngineExt;
import com.moulberry.flashback.packet.FlashbackRemoteExperience;
import com.moulberry.flashback.packet.FlashbackRemoteFoodData;
import com.moulberry.flashback.packet.FlashbackRemoteSelectHotbarSlot;
import com.moulberry.flashback.packet.FlashbackRemoteSetSlot;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ReplayGamePacketHandler implements ClientGamePacketListener {

    private static final int PLAYER_SKIN_PARTS_DATA_ID = 17;
    private static final int PLAYER_MAIN_ARM_DATA_ID = 18;

    private final BlockState FALLBACK_BLOCK_STATE = Blocks.VOID_AIR.defaultBlockState();
    private final ReplayServer replayServer;
    private final Map<UUID, PlayerInfo> playerInfoMap = new HashMap<>();
    private final Map<UUID, GameType> playerGameModes = new HashMap<>();
    private Int2ObjectMap<Entity> pendingEntities = new Int2ObjectOpenHashMap<>();
    private ResourceKey<Level> currentDimension = null;
    public int localPlayerId = -1;
    public LongSet forceSendChunksDueToMovingPistonShenanigans = new LongOpenHashSet();

    public ReplayGamePacketHandler(ReplayServer replayServer) {
        this.replayServer = replayServer;
    }

    public void clearDataForPlayingSnapshot() {
        this.playerInfoMap.clear();
        this.playerGameModes.clear();
    }

    private void forward(Packet<?> packet) {
        for (ServerPlayer replayViewer : this.replayServer.getReplayViewers()) {
            replayViewer.connection.send(packet);
        }
    }

    private void forward(Entity entity, Packet<? super ClientGamePacketListener> packet) {
        if (entity == null) {
            this.forward(packet);
        } else {
            ServerChunkCache serverChunkCache = this.level().getChunkSource();
            serverChunkCache.broadcast(entity, packet);
        }
    }

    private void setBlockState(ServerLevel level, BlockPos blockPos, BlockState blockState) {
        if (blockState == null) {
            blockState = FALLBACK_BLOCK_STATE;
        }
        LevelChunk levelChunk = level.getChunkAt(blockPos);
        ((LevelChunkExt)levelChunk).flashback$setBlockStateWithoutUpdates(blockPos, blockState);
    }

    public void flushPendingEntities() {
        ServerLevel level = this.level();
        boolean canSpawnLightningBolt = switch (this.replayServer.getEditorState().replayVisuals.overrideWeatherMode) {
            case NONE, THUNDERING -> true;
            case CLEAR, OVERCAST, RAINING, SNOWING -> false;
        };
        for (Entity pendingEntity : this.pendingEntities.values()) {
            if (pendingEntity instanceof LightningBolt && !canSpawnLightningBolt) {
                continue;
            }

            Entity existingEntity = level.getEntity(pendingEntity.getId());
            if (existingEntity != null) {
                if (existingEntity instanceof ServerPlayer existingPlayer) {
                    existingPlayer.discard();
                } else if (existingEntity.getType().equals(pendingEntity.getType())) {
                    try {
                        existingEntity.restoreFrom(pendingEntity);
                        existingEntity.setPos(pendingEntity.getX(), pendingEntity.getY(), pendingEntity.getZ());
                        existingEntity.setXRot(pendingEntity.getXRot());
                        existingEntity.setYRot(pendingEntity.getYRot());
                        existingEntity.setYHeadRot(pendingEntity.getYHeadRot());
                        existingEntity.setDeltaMovement(pendingEntity.getDeltaMovement());
                        if (pendingEntity instanceof LivingEntity pendingLiving) {
                            existingEntity.setYBodyRot(pendingLiving.yBodyRot);
                        }
                        for (SynchedEntityData.DataItem<?> dataItem : pendingEntity.getEntityData().itemsById) {
                            existingEntity.getEntityData().set((EntityDataAccessor) dataItem.getAccessor(), dataItem.getValue());
                        }
                        continue;
                    } catch (Exception ignored) {
                        existingEntity.discard();
                    }
                } else {
                    existingEntity.discard();
                }
            }

            ((ServerLevelExt) level).flashback$setCanSpawnEntities(true);
            try {
                level.addFreshEntity(pendingEntity);
                ChunkPos chunkPos = new ChunkPos(pendingEntity.blockPosition());
                level.getChunkSource().addRegionTicket(ReplayServer.ENTITY_LOAD_TICKET, chunkPos, 3, chunkPos);
            } catch (Exception e) {
                Flashback.LOGGER.error("Unable to spawn entity", e);
            }
            ((ServerLevelExt) level).flashback$setCanSpawnEntities(false);
        }
        this.pendingEntities.clear();
    }

    private Entity getEntityOrPending(int entityId) {
        if (this.pendingEntities.containsKey(entityId)) {
            return this.pendingEntities.get(entityId);
        }
        this.flushPendingEntities();
        return this.level().getEntity(entityId);
    }

    public ServerLevel level() {
        return this.currentDimension == null ? null : this.replayServer.getLevel(this.currentDimension);
    }

    @Override
    public void handleAddEntity(ClientboundAddEntityPacket clientboundAddEntityPacket) {
        if (Entity.ENTITY_COUNTER.get() <= clientboundAddEntityPacket.getId()) {
            Entity.ENTITY_COUNTER.set(clientboundAddEntityPacket.getId() + 1000);
        }
        if (clientboundAddEntityPacket.id >= ReplayServer.REPLAY_VIEWER_IDS_START && clientboundAddEntityPacket.id <= ReplayServer.REPLAY_VIEWER_IDS_START+128) {
            clientboundAddEntityPacket.id += 128;
        }

        Entity entity = this.createEntityFromPacket(clientboundAddEntityPacket);
        if (entity != null) {
            if (entity instanceof Painting) {
                Direction direction = Direction.from3DDataValue(clientboundAddEntityPacket.getData());
                if (!direction.getAxis().isHorizontal()) {
                    clientboundAddEntityPacket.data = Direction.NORTH.get3DDataValue();
                }
            }
            entity.recreateFromPacket(clientboundAddEntityPacket);
            this.pendingEntities.put(clientboundAddEntityPacket.getId(), entity);
        }
    }

    private ServerPlayer spawnPlayer(ClientboundAddEntityPacket addEntityPacket, GameProfile gameProfile, GameType gameType) {
        this.flushPendingEntities();

        CommonListenerCookie commonListenerCookie = CommonListenerCookie.createInitial(gameProfile, false);
        ServerPlayer serverPlayer = new FlashbackFakePlayer(this.replayServer, this.level(), commonListenerCookie.gameProfile(), commonListenerCookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };

        ServerPlayer existingPlayer = this.replayServer.getPlayerList().getPlayer(serverPlayer.getUUID());
        if (existingPlayer != null) {
            existingPlayer.discard();
        }

        Entity existingEntity = this.level().getEntity(serverPlayer.getId());
        if (existingEntity != null) {
            existingEntity.discard();
        }

        for (ReplayPlayer replayViewer : this.replayServer.getReplayViewers()) {
            if (Objects.equals(replayViewer.lastFirstPersonDataUUID, addEntityPacket.getUUID())) {
                replayViewer.lastFirstPersonDataUUID = null;
                // The client is told about this player before it has them, so the state is sent for a
                // few ticks rather than once and the payloads that arrive too early are not the only
                // ones the hotbar gets.
                replayViewer.resendFirstPersonTicks = 20;
            }
        }

        Connection connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener packetSendListener) {
            }
        };
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(connection);
        serverPlayer.recreateFromPacket(addEntityPacket);
        try {
            this.replayServer.getPlayerList().placeNewPlayer(connection, serverPlayer, commonListenerCookie);
        } catch (Exception e) {
            this.replayServer.failedToSpawnPlayerWarning = true;
            Flashback.LOGGER.error("Failed to spawn player", e);
            return null;
        }
        serverPlayer.setGameMode(gameType);

        if (serverPlayer.isRemoved()) {
            this.replayServer.failedToSpawnPlayerWarning = true;
            Flashback.LOGGER.error("ServerPlayer {} was removed while spawning. Incompatible mod?", serverPlayer.getUUID());
            return null;
        }

        return serverPlayer;
    }

    @Nullable
    private Entity createEntityFromPacket(ClientboundAddEntityPacket packet) {
        EntityType<?> entityType = packet.getType();
        if (entityType == EntityType.PLAYER) {
            PlayerInfo playerInfo = this.playerInfoMap.get(packet.getUUID());
            if (playerInfo == null) {
                return null;
            }

            this.spawnPlayer(packet, playerInfo.getProfile(), this.playerGameModes.getOrDefault(packet.getUUID(), GameType.SURVIVAL));
            return null;
        } else {
            return entityType.create(this.level());
        }
    }

    @Override
    public void handleAddExperienceOrb(ClientboundAddExperienceOrbPacket packet) {
        double x = packet.getX();
        double y = packet.getY();
        double z = packet.getZ();
        ExperienceOrb orb = new ExperienceOrb(this.level(), x, y, z, packet.getValue());
        orb.syncPacketPositionCodec(x, y, z);
        orb.setYRot(0.0F);
        orb.setXRot(0.0F);
        orb.setId(packet.getId());
        orb.setDeltaMovement(Vec3.ZERO);

        Entity existing = this.level().getEntity(packet.getId());
        if (existing instanceof ExperienceOrb) {
            existing.restoreFrom(orb);
            return;
        }
        if (existing instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.disconnect(net.minecraft.network.chat.Component.empty());
        } else if (existing != null) {
            existing.discard();
        }

        ServerLevel level = this.level();
        ((ServerLevelExt) level).flashback$setCanSpawnEntities(true);
        level.addFreshEntity(orb);
        ((ServerLevelExt) level).flashback$setCanSpawnEntities(false);
    }

    @Override
    public void handleAddObjective(ClientboundSetObjectivePacket clientboundSetObjectivePacket) {
        forward(clientboundSetObjectivePacket);
    }

    @Override
    public void handleAnimate(ClientboundAnimatePacket clientboundAnimatePacket) {
        Entity entity = this.level().getEntity(clientboundAnimatePacket.getId());
        forward(entity, clientboundAnimatePacket);
    }

    @Override
    public void handleHurtAnimation(ClientboundHurtAnimationPacket clientboundHurtAnimationPacket) {
        Entity entity = this.level().getEntity(clientboundHurtAnimationPacket.id());
        forward(entity, clientboundHurtAnimationPacket);
    }

    @Override
    public void handleAwardStats(ClientboundAwardStatsPacket clientboundAwardStatsPacket) {
        throw new UnsupportedPacketException(clientboundAwardStatsPacket);
    }

    @Override
    public void handleAddOrRemoveRecipes(ClientboundRecipePacket packet) {
        throw new UnsupportedPacketException(packet);
    }

    @Override
    public void handleBlockDestruction(ClientboundBlockDestructionPacket clientboundBlockDestructionPacket) {
        forward(clientboundBlockDestructionPacket);
    }

    @Override
    public void handleOpenSignEditor(ClientboundOpenSignEditorPacket clientboundOpenSignEditorPacket) {
        throw new UnsupportedPacketException(clientboundOpenSignEditorPacket);
    }

    @Override
    public void handleBlockEntityData(ClientboundBlockEntityDataPacket clientboundBlockEntityDataPacket) {
        BlockPos blockPos = clientboundBlockEntityDataPacket.getPos();
        this.level().getBlockEntity(blockPos, clientboundBlockEntityDataPacket.getType()).ifPresent(blockEntity -> {
            blockEntity.loadWithComponents(clientboundBlockEntityDataPacket.getTag(), this.replayServer.registryAccess());

            // Sync
            blockEntity.setChanged();
            BlockState blockState = this.level().getBlockState(blockPos);
            Level level = blockEntity.getLevel();
            if (level != null) {
                level.sendBlockUpdated(blockPos, blockState, blockState, 3);
            }
        });
    }

    @Override
    public void handleBlockEvent(ClientboundBlockEventPacket clientboundBlockEventPacket) {
        BlockPos pos = clientboundBlockEventPacket.getPos();

        BlockState blockState = this.level().getBlockState(pos);

        if (blockState.getBlock() instanceof PistonBaseBlock) {
            this.forceSendChunksDueToMovingPistonShenanigans.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            this.forceSendChunksDueToMovingPistonShenanigans.add(ChunkPos.asLong((pos.getX() >> 4) + 1, pos.getZ() >> 4));
            this.forceSendChunksDueToMovingPistonShenanigans.add(ChunkPos.asLong(pos.getX() >> 4, (pos.getZ() >> 4) + 1));
            this.forceSendChunksDueToMovingPistonShenanigans.add(ChunkPos.asLong((pos.getX() >> 4) - 1, pos.getZ() >> 4));
            this.forceSendChunksDueToMovingPistonShenanigans.add(ChunkPos.asLong(pos.getX() >> 4, (pos.getZ() >> 4) - 1));
        }

        this.replayServer.getPlayerList().broadcastAll(clientboundBlockEventPacket);
    }

    @Override
    public void handleBlockUpdate(ClientboundBlockUpdatePacket clientboundBlockUpdatePacket) {
        BlockState blockState = clientboundBlockUpdatePacket.getBlockState();
        this.setBlockState(this.level(), clientboundBlockUpdatePacket.getPos(), blockState);
        forward(clientboundBlockUpdatePacket);
    }

    @Override
    public void handleSystemChat(ClientboundSystemChatPacket clientboundSystemChatPacket) {
        forward(clientboundSystemChatPacket);
    }

    @Override
    public void handlePlayerChat(ClientboundPlayerChatPacket clientboundPlayerChatPacket) {
        throw new UnsupportedPacketException(clientboundPlayerChatPacket);
    }

    @Override
    public void handleMovePlayer(ClientboundPlayerPositionPacket packet) {
        Entity entity = this.level().getEntity(this.localPlayerId);
        if (!(entity instanceof Player player)) {
            return;
        }

        Set<RelativeMovement> relative = packet.getRelativeArguments();
        double x = relative.contains(RelativeMovement.X) ? player.getX() + packet.getX() : packet.getX();
        double y = relative.contains(RelativeMovement.Y) ? player.getY() + packet.getY() : packet.getY();
        double z = relative.contains(RelativeMovement.Z) ? player.getZ() + packet.getZ() : packet.getZ();
        float yaw = relative.contains(RelativeMovement.Y_ROT) ? player.getYRot() + packet.getYRot() : packet.getYRot();
        float pitch = relative.contains(RelativeMovement.X_ROT) ? player.getXRot() + packet.getXRot() : packet.getXRot();
        player.moveTo(x, y, z, net.minecraft.util.Mth.wrapDegrees(yaw), net.minecraft.util.Mth.wrapDegrees(pitch));
        player.setYHeadRot(player.getYRot());
    }

    @Override
    public void handleDisguisedChat(ClientboundDisguisedChatPacket clientboundDisguisedChatPacket) {
        forward(clientboundDisguisedChatPacket);
    }

    @Override
    public void handleDeleteChat(ClientboundDeleteChatPacket clientboundDeleteChatPacket) {
        throw new UnsupportedPacketException(clientboundDeleteChatPacket);
    }

    @Override
    public void handleChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket clientboundSectionBlocksUpdatePacket) {
        ServerLevel level = this.level();
        clientboundSectionBlocksUpdatePacket.runUpdates((blockPos, blockState) -> {
            this.setBlockState(level, blockPos, blockState);
        });
        forward(clientboundSectionBlocksUpdatePacket);
    }

    @Override
    public void handleMapItemData(ClientboundMapItemDataPacket clientboundMapItemDataPacket) {
        forward(clientboundMapItemDataPacket);

        ServerLevel level = this.level();
        if (level == null) {
            return;
        }

        MapItemSavedData mapItemSavedData = level.getMapData(clientboundMapItemDataPacket.mapId());
        if (mapItemSavedData == null) {
            mapItemSavedData = MapItemSavedData.createForClient(clientboundMapItemDataPacket.scale(), clientboundMapItemDataPacket.locked(), level.dimension());
            level.setMapData(clientboundMapItemDataPacket.mapId(), mapItemSavedData);
        }
        clientboundMapItemDataPacket.applyToMap(mapItemSavedData);
    }

    @Override
    public void handleContainerClose(ClientboundContainerClosePacket packet) {
        GuiDisplayForwarder.close(this.replayServer, packet.getContainerId());
    }

    @Override
    public void handleContainerContent(ClientboundContainerSetContentPacket packet) {
        if (packet.getContainerId() == 0) {
            // The detached container draws these menu slots, but the HUD reads the recorded
            // player's inventory. Keep both views in step even when GUI display is disabled.
            for (int i = 0; i < packet.getItems().size(); i++) {
                mirrorOwnInventory(i, packet.getItems().get(i));
            }
        }
        GuiDisplayForwarder.content(this.replayServer, packet.getContainerId(), packet.getItems(), packet.getCarriedItem());
    }

    @Override
    public void handleHorseScreenOpen(ClientboundHorseScreenOpenPacket packet) {
        GuiDisplayForwarder.openMount(this.replayServer, packet.getContainerId(), packet.getEntityId(),
            packet.getInventoryColumns(), net.minecraft.network.chat.Component.empty());
    }

    @Override
    public void handleContainerSetData(ClientboundContainerSetDataPacket packet) {
        // Furnace flames and progress arrows are animation rather than contents, and the screen the
        // client draws is built fresh, so there is nothing here to send.
    }

    @Override
    public void handleContainerSetSlot(ClientboundContainerSetSlotPacket packet) {
        int slot = packet.getSlot();
        ItemStack itemStack = packet.getItem();

        // In 1.21.1, container id -1 / slot -1 is the cursor stack, and id -2 addresses the
        // recorded player's inventory directly. Newer protocol packets split these updates out.
        if (packet.getContainerId() == -1 && slot == -1) {
            GuiDisplayForwarder.carried(this.replayServer, itemStack);
            return;
        }
        if (packet.getContainerId() == -2) {
            int menuSlot = InventoryMenuSlots.menuSlot(slot);
            if (menuSlot >= 0) {
                mirrorOwnInventory(menuSlot, itemStack);
            }
            return;
        }

        // The recorded player's own inventory is not part of the container interface. It is what the
        // first-person hotbar draws, so it is kept current whether or not containers are displayed -
        // and, just as importantly, both the client and this side's record of it are updated together
        // or not at all. Keeping them apart is what let them drift: the per-tick sync below only
        // sends what differs from the record, so a change this skipped on its way to the client was
        // also assumed to have arrived.
        if (packet.getContainerId() == 0) {
            mirrorOwnInventory(slot, itemStack);
        }

        // Only in a replay when containers are being shown, so with that off this is skipped exactly
        // as it always was.
        if (!GuiPlayback.enabled()) {
            return;
        }

        // Whether or not it was the player's own inventory, a container the recording has open has
        // to follow it too.
        GuiDisplayForwarder.slot(this.replayServer, packet.getContainerId(), slot, itemStack);
    }

    @Override
    public void handleEntityEvent(ClientboundEntityEventPacket clientboundEntityEventPacket) {
        Entity entity = clientboundEntityEventPacket.getEntity(this.level());
        forward(entity, clientboundEntityEventPacket);
    }

    @Override
    public void handleEntityLinkPacket(ClientboundSetEntityLinkPacket clientboundSetEntityLinkPacket) {
        Entity source = this.level().getEntity(clientboundSetEntityLinkPacket.getSourceId());
        if (source == null) {
            forward(clientboundSetEntityLinkPacket);
            return;
        }

        if (source instanceof Leashable leashable) {
            if (clientboundSetEntityLinkPacket.getDestId() == 0) {
                leashable.setLeashedTo(null, true);
            } else {
                Entity dest = this.level().getEntity(clientboundSetEntityLinkPacket.getDestId());
                if (dest == null) {
                    forward(clientboundSetEntityLinkPacket);
                    return;
                }
                leashable.setLeashedTo(dest, true);
            }
        }
    }

    @Override
    public void handleSetEntityPassengersPacket(ClientboundSetPassengersPacket clientboundSetPassengersPacket) {
        Entity vehicle = this.level().getEntity(clientboundSetPassengersPacket.getVehicle());
        if (vehicle == null) {
            return;
        }
        vehicle.ejectPassengers();
        for (int passengerId : clientboundSetPassengersPacket.getPassengers()) {
            Entity passenger = this.level().getEntity(passengerId);
            if (passenger == null) {
                continue;
            }

            passenger.startRiding(vehicle, true);
        }
    }

    @Override
    public void handleExplosion(ClientboundExplodePacket e) {
        ClientboundExplodePacket withoutKnockback = new ClientboundExplodePacket(e.getX(), e.getY(), e.getZ(),
            e.getPower(), e.getToBlow(), null, e.getBlockInteraction(), e.getSmallExplosionParticles(),
            e.getLargeExplosionParticles(), e.getExplosionSound());
        forward(withoutKnockback);
    }

    @Override
    public void handleGameEvent(ClientboundGameEventPacket clientboundGameEventPacket) {
        ClientboundGameEventPacket.Type type = clientboundGameEventPacket.getEvent();
        ServerLevel level = this.level();
        float floatValue = clientboundGameEventPacket.getParam();
        if (type == ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE) {
            return;
        } else if (type == ClientboundGameEventPacket.START_RAINING) {
            level.getLevelData().setRaining(true);
            level.setRainLevel(0.0f);
        } else if (type == ClientboundGameEventPacket.STOP_RAINING) {
            level.getLevelData().setRaining(false);
            level.setRainLevel(1.0f);
        } else if (type == ClientboundGameEventPacket.CHANGE_GAME_MODE) {
            return;
        } else if (type == ClientboundGameEventPacket.WIN_GAME) {
            return;
        } else if (type == ClientboundGameEventPacket.DEMO_EVENT) {
            return;
        } else if (type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
            level.setRainLevel(floatValue);
        } else if (type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
            level.setThunderLevel(floatValue);
        } else if (type == ClientboundGameEventPacket.PUFFER_FISH_STING) {
            return;
        } else if (type == ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT) {
            return;
        } else if (type == ClientboundGameEventPacket.IMMEDIATE_RESPAWN) {
            return;
        } else if (type == ClientboundGameEventPacket.LIMITED_CRAFTING) {
            return;
        }

        forward(clientboundGameEventPacket);
    }

    @Override
    public void handleLevelChunkWithLight(ClientboundLevelChunkWithLightPacket levelChunkWithLightPacket) {
        int x = levelChunkWithLightPacket.getX();
        int z = levelChunkWithLightPacket.getZ();

        LevelLightEngine levelLightEngine = this.level().getChunkSource().getLightEngine();
        LevelChunk chunk = this.level().getChunk(x, z);

        var chunkData = levelChunkWithLightPacket.getChunkData();
        chunk.replaceWithPacketData(chunkData.getReadBuffer(), chunkData.getHeightmaps(), chunkData.getBlockEntitiesTagsConsumer(x, z));

        var lightData = levelChunkWithLightPacket.getLightData();
        if (FabricLoader.getInstance().isModLoaded("starlight")) {
            chunk.setLightCorrect(false);
            try {
                var blockNibbles = StarLightEngine.getFilledEmptyLight(this.level());
                var skyNibbles = StarLightEngine.getFilledEmptyLight(this.level());
                extractStarlightData(levelLightEngine, lightData.getBlockYMask(), lightData.getEmptyBlockYMask(), lightData.getBlockUpdates().iterator(), blockNibbles);
                extractStarlightData(levelLightEngine, lightData.getSkyYMask(), lightData.getEmptySkyYMask(), lightData.getSkyUpdates().iterator(), skyNibbles);
                ((ExtendedChunk)chunk).setBlockNibbles(blockNibbles);
                ((ExtendedChunk)chunk).setSkyNibbles(skyNibbles);
            } catch (Exception e) {
                Flashback.LOGGER.error("Error while setting starlight light data", e);
            }
            chunk.setLightCorrect(true);
        } else {
            this.applyLightData(levelLightEngine, x, z, lightData);
        }

        ChunkPos chunkPos = chunk.getPos();
        ((ServerLevelExt)this.level()).flashback$markChunkAsSendable(chunkPos.toLong());
        for (ServerPlayer serverPlayer : this.replayServer.getReplayViewers()) {
            if (serverPlayer.getChunkTrackingView().contains(chunkPos)) {
                serverPlayer.connection.chunkSender.markChunkPendingToSend(chunk);
            }
        }

        chunk.setUnsaved(true);
    }

    private static void extractStarlightData(LevelLightEngine levelLightEngine, BitSet yMask, BitSet emptyYMask, Iterator<byte[]> iterator, SWMRNibbleArray[] nibbles) {
        for (int index = 0; index < levelLightEngine.getLightSectionCount(); index++) {
            boolean hasData = yMask.get(index);
            boolean isEmpty = emptyYMask.get(index);
            if (hasData || isEmpty) {
                var dataLayer = hasData ? new DataLayer(iterator.next().clone()) : new DataLayer();
                nibbles[index] = SWMRNibbleArray.fromVanilla(dataLayer);
            }
        }
    }

    private void applyLightData(LevelLightEngine levelLightEngine, int x, int z, ClientboundLightUpdatePacketData clientboundLightUpdatePacketData) {
        levelLightEngine.retainData(new ChunkPos(x, z), true);

        BitSet skyYMask = clientboundLightUpdatePacketData.getSkyYMask();
        BitSet emptySkyYMask = clientboundLightUpdatePacketData.getEmptySkyYMask();
        Iterator<byte[]> iterator = clientboundLightUpdatePacketData.getSkyUpdates().iterator();
        this.readSectionList(x, z, levelLightEngine, LightLayer.SKY, skyYMask, emptySkyYMask, iterator);

        BitSet blockYMask = clientboundLightUpdatePacketData.getBlockYMask();
        BitSet emptyBlockYMask = clientboundLightUpdatePacketData.getEmptyBlockYMask();
        Iterator<byte[]> iterator2 = clientboundLightUpdatePacketData.getBlockUpdates().iterator();
        this.readSectionList(x, z, levelLightEngine, LightLayer.BLOCK, blockYMask, emptyBlockYMask, iterator2);

        ((ThreadedLevelLightEngineExt)levelLightEngine).flashback$submitPost(x, z, () -> {
            // Initialize light
            LevelChunk chunkAccess = this.level().getChunk(x, z);
            chunkAccess.initializeLightSources();
            boolean isLightCorrect = chunkAccess.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT) && chunkAccess.isLightCorrect();
            ((ThreadedLevelLightEngine)levelLightEngine).initializeLight(chunkAccess, isLightCorrect).thenRun(() -> {
                // Send light data
                LevelChunk chunkAccess2 = this.level().getChunk(x, z);
                chunkAccess2.setLightCorrect(true);

                ChunkPos chunkPos = new ChunkPos(x, z);
                var lightPacket = new ClientboundLightUpdatePacket(chunkPos, levelLightEngine, null, null);
                for (ServerPlayer serverPlayer : this.replayServer.getReplayViewers()) {
                    if (serverPlayer.getChunkTrackingView().contains(chunkPos)) {
                        serverPlayer.connection.send(lightPacket);
                    }
                }
            });
        });
    }

    private void readSectionList(int x, int z, LevelLightEngine levelLightEngine, LightLayer lightLayer, BitSet yMask, BitSet emptyYMask, Iterator<byte[]> iterator) {
        for (int index = 0; index < levelLightEngine.getLightSectionCount(); index++) {
            int y = levelLightEngine.getMinLightSection() + index;
            boolean hasData = yMask.get(index);
            boolean isEmpty = emptyYMask.get(index);
            if (hasData || isEmpty) {
                levelLightEngine.queueSectionData(lightLayer, SectionPos.of(x, y, z), hasData ? new DataLayer(iterator.next().clone()) : new DataLayer());
            }
        }
    }

    @Override
    public void handleChunksBiomes(ClientboundChunksBiomesPacket clientboundChunksBiomesPacket) {
        List<ChunkAccess> chunks = new ArrayList<>(clientboundChunksBiomesPacket.chunkBiomeData().size());
        for (ClientboundChunksBiomesPacket.ChunkBiomeData chunkBiomeData : clientboundChunksBiomesPacket.chunkBiomeData()) {
            LevelChunk chunk = this.level().getChunk(chunkBiomeData.pos().x, chunkBiomeData.pos().z);
            chunk.replaceBiomes(chunkBiomeData.getReadBuffer());
            chunks.add(chunk);
        }
        this.level().getChunkSource().chunkMap.resendBiomesForChunks(chunks);
    }

    @Override
    public void handleForgetLevelChunk(ClientboundForgetLevelChunkPacket clientboundForgetLevelChunkPacket) {
        throw new UnsupportedPacketException(clientboundForgetLevelChunkPacket);
    }

    @Override
    public void handleLevelEvent(ClientboundLevelEventPacket clientboundLevelEventPacket) {
        if (clientboundLevelEventPacket.isGlobalEvent()) {
            this.replayServer.getPlayerList().broadcastAll(clientboundLevelEventPacket);
        } else {
            BlockPos blockPos = clientboundLevelEventPacket.getPos();
            ResourceKey<Level> dimension = this.level().dimension();
            this.replayServer.getPlayerList().broadcast(null, blockPos.getX(), blockPos.getY(), blockPos.getZ(), 64.0, dimension, clientboundLevelEventPacket);
        }
    }

    @Override
    public void handleLogin(ClientboundLoginPacket clientboundLoginPacket) {
        ServerPlayer localPlayer = null;
        boolean recreatePlayer = false;

        ServerLevel level = this.level();
        if (level != null && level.getEntity(this.localPlayerId) instanceof ServerPlayer serverPlayer) {
            localPlayer = serverPlayer;
            if (this.localPlayerId != clientboundLoginPacket.playerId()) {
                localPlayer.discard();
                recreatePlayer = true;
            }
        }

        this.localPlayerId = clientboundLoginPacket.playerId();
        this.ensureWorldCreated(clientboundLoginPacket.commonPlayerSpawnInfo(), recreatePlayer ? null : localPlayer, true);

        // Update special world property
        CommonPlayerSpawnInfo spawnInfo = clientboundLoginPacket.commonPlayerSpawnInfo();
        if (this.replayServer.getWorldData() instanceof PrimaryLevelData primaryLevelData) {
            PrimaryLevelData.SpecialWorldProperty specialWorldProperty;
            if (spawnInfo.isFlat()) {
                specialWorldProperty = PrimaryLevelData.SpecialWorldProperty.FLAT;
            } else if (spawnInfo.isDebug()) {
                specialWorldProperty = PrimaryLevelData.SpecialWorldProperty.DEBUG;
            } else {
                specialWorldProperty = PrimaryLevelData.SpecialWorldProperty.NONE;
            }
            primaryLevelData.specialWorldProperty = specialWorldProperty;
            primaryLevelData.setDifficulty(Difficulty.NORMAL);
            primaryLevelData.setDifficultyLocked(true);
        }

        if (recreatePlayer) {
            ClientboundAddEntityPacket addEntityPacket = (ClientboundAddEntityPacket) PacketHelper.createAddEntity(localPlayer);
            addEntityPacket.id = clientboundLoginPacket.playerId();
            this.spawnPlayer(addEntityPacket, localPlayer.getGameProfile(), localPlayer.gameMode.getGameModeForPlayer());
        }
    }

    public void handleCreateLocalPlayer(RegistryFriendlyByteBuf registryFriendlyByteBuf) {
        UUID uuid = registryFriendlyByteBuf.readUUID();
        double x = registryFriendlyByteBuf.readDouble();
        double y = registryFriendlyByteBuf.readDouble();
        double z = registryFriendlyByteBuf.readDouble();
        float xRot = registryFriendlyByteBuf.readFloat();
        float yRot = registryFriendlyByteBuf.readFloat();
        float yHeadRot = registryFriendlyByteBuf.readFloat();
        Vec3 velocity = new Vec3(
            registryFriendlyByteBuf.readDouble(),
            registryFriendlyByteBuf.readDouble(),
            registryFriendlyByteBuf.readDouble()
        );

        ClientboundAddEntityPacket addEntityPacket = new ClientboundAddEntityPacket(this.localPlayerId, uuid, x, y, z, xRot, yRot, EntityType.PLAYER, 0, velocity, yHeadRot);
        GameProfile gameProfile = ByteBufCodecs.GAME_PROFILE.decode(registryFriendlyByteBuf);
        GameType gameType = GameType.byId(registryFriendlyByteBuf.readVarInt());

        Entity existing = this.localPlayerId == -1 ? null : this.getEntityOrPending(this.localPlayerId);
        if (existing == null) {
            this.spawnPlayer(addEntityPacket, gameProfile, gameType);
        } else {
            existing.moveTo(x, y, z, yRot, xRot);
            existing.setDeltaMovement(velocity);
        }
    }

    private void ensureWorldCreated(CommonPlayerSpawnInfo commonPlayerSpawnInfo, Entity localPlayer, boolean forceReset) {
        ResourceKey<Level> dimension = commonPlayerSpawnInfo.dimension();

        if (forceReset) {
            this.replayServer.clearReplayTempFolder();
            if (this.currentDimension == dimension) {
                this.replayServer.clearLevel(this.level());
            }
        }

        ServerLevel oldLevel = this.replayServer.getLevel(dimension);

        // Force recreate level if the DimensionType has changed
        boolean forceRecreate = oldLevel != null && !oldLevel.dimensionType().equals(commonPlayerSpawnInfo.dimensionType().value());

        if (oldLevel == null || forceRecreate) {
            ServerLevelData serverLevelData = this.replayServer.worldData.overworldData();
            if (dimension != Level.OVERWORLD) {
                serverLevelData = new DerivedLevelData(this.replayServer.worldData, serverLevelData);
            }
            Holder.Reference<Biome> plains = this.replayServer.registryAccess().lookupOrThrow(Registries.BIOME).get(Biomes.PLAINS).get();
            LevelStem levelStem = new LevelStem(commonPlayerSpawnInfo.dimensionType(), new EmptyLevelSource(plains));
            ServerLevel existingOverworld = this.replayServer.getLevel(Level.OVERWORLD);
            RandomSequences randomSequences = existingOverworld == null
                ? new RandomSequences(commonPlayerSpawnInfo.seed())
                : existingOverworld.getRandomSequences();
            ChunkProgressListener progressListener = this.replayServer.progressListenerFactory.create(0);
            ServerLevel serverLevel = new ServerLevel(this.replayServer, this.replayServer.executor, this.replayServer.storageSource,
                serverLevelData, dimension, levelStem, progressListener, false, commonPlayerSpawnInfo.seed(), List.of(), false, randomSequences);
            serverLevel.noSave = true;
            ServerWorldEvents.LOAD.invoker().onWorldLoad(this.replayServer, serverLevel);
            this.replayServer.levels.put(dimension, serverLevel);
            this.replayServer.getPlayerList().addWorldborderListener(serverLevel);

            if (oldLevel != null) {
                this.replayServer.closeLevel(oldLevel);
            }
        }

        ServerLevel newLevel = this.replayServer.getLevel(dimension);
        ((ServerLevelExt) newLevel).flashback$setCanSpawnEntities(false);
        ((ServerLevelExt) newLevel).flashback$setSeedHash(commonPlayerSpawnInfo.seed());
        this.replayServer.followLocalPlayerNextTickIfWrongDimension = true;

        if (newLevel != oldLevel) {
            // Move local player and replay viewer
            if (localPlayer != null) {
                localPlayer.teleportTo(newLevel, 0.0, 0.0, 0.0, Set.of(), 0.0f, 0.0f);
            }
            for (ReplayPlayer replayViewer : this.replayServer.getReplayViewers()) {
                replayViewer.teleportTo(newLevel, replayViewer.getX(), replayViewer.getY(), replayViewer.getZ(), Set.of(),
                    replayViewer.getYRot(), replayViewer.getXRot());
                replayViewer.followLocalPlayerNextTick = true;
            }

            this.forceSendChunksDueToMovingPistonShenanigans.clear();
        }

        // Delete current dimension
        if (this.currentDimension != null && this.currentDimension != dimension) {
            if (this.currentDimension == Level.OVERWORLD) {
                this.replayServer.clearLevel(this.level());
            } else if (this.currentDimension != null) {
                ServerLevel level = this.replayServer.levels.remove(this.currentDimension);
                if (level != null) {
                    this.replayServer.closeLevel(level);
                }
            }
        }

        // Change to new dimension
        this.currentDimension = commonPlayerSpawnInfo.dimension();
        this.replayServer.spawnLevel = this.currentDimension;
    }

    @Override
    public void handleMoveEntity(ClientboundMoveEntityPacket clientboundMoveEntityPacket) {
        throw new UnsupportedPacketException(clientboundMoveEntityPacket);
//        Entity entity = clientboundMoveEntityPacket.getEntity(this.level());
//        if (entity == null) {
//            return;
//        }
//        if (entity.getId() == this.localPlayerId) {
//            return;
//        }
//
//        if (clientboundMoveEntityPacket.hasPosition()) {
//            VecDeltaCodec vecDeltaCodec = entity.getPositionCodec();
//            Vec3 position = vecDeltaCodec.decode(clientboundMoveEntityPacket.getXa(), clientboundMoveEntityPacket.getYa(), clientboundMoveEntityPacket.getZa());
//            vecDeltaCodec.setBase(position);
//            float yaw = clientboundMoveEntityPacket.hasRotation() ? (float)(clientboundMoveEntityPacket.getyRot() * 360) / 256.0f : entity.lerpTargetYRot();
//            float pitch = clientboundMoveEntityPacket.hasRotation() ? (float)(clientboundMoveEntityPacket.getxRot() * 360) / 256.0f : entity.lerpTargetXRot();
//            entity.moveTo(position.x(), position.y(), position.z(), yaw, pitch);
//        } else if (clientboundMoveEntityPacket.hasRotation()) {
//            float yaw = (float)(clientboundMoveEntityPacket.getyRot() * 360) / 256.0f;
//            float pitch = (float)(clientboundMoveEntityPacket.getxRot() * 360) / 256.0f;
//            entity.setYRot(yaw);
//            entity.setXRot(pitch);
//        }
//
//        entity.setOnGround(clientboundMoveEntityPacket.isOnGround());
    }

    @Override
    public void handleParticleEvent(ClientboundLevelParticlesPacket clientboundLevelParticlesPacket) {
        for (ServerPlayer viewer : this.replayServer.getReplayViewers()) {
            boolean overrideLimiter = clientboundLevelParticlesPacket.isOverrideLimiter();
            double x = clientboundLevelParticlesPacket.getX();
            double y = clientboundLevelParticlesPacket.getY();
            double z = clientboundLevelParticlesPacket.getZ();
            this.level().sendParticles(viewer, overrideLimiter, x, y, z, clientboundLevelParticlesPacket);
        }
    }

    @Override
    public void handlePlayerAbilities(ClientboundPlayerAbilitiesPacket clientboundPlayerAbilitiesPacket) {
        throw new UnsupportedPacketException(clientboundPlayerAbilitiesPacket);
    }

    @Override
    public void handlePlayerInfoRemove(ClientboundPlayerInfoRemovePacket clientboundPlayerInfoRemovePacket) {
        for (UUID uuid : clientboundPlayerInfoRemovePacket.profileIds()) {
            this.playerInfoMap.remove(uuid);
            this.playerGameModes.remove(uuid);
        }
        forward(clientboundPlayerInfoRemovePacket);
    }

    @Override
    public void handlePlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket clientboundPlayerInfoUpdatePacket) {
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : clientboundPlayerInfoUpdatePacket.newEntries()) {
            PlayerInfo playerInfo = new PlayerInfo(Objects.requireNonNull(entry.profile()), false);
            this.playerInfoMap.putIfAbsent(entry.profileId(), playerInfo);
            if (entry.gameMode() != null) {
                this.playerGameModes.putIfAbsent(entry.profileId(), entry.gameMode());
            }
        }
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : clientboundPlayerInfoUpdatePacket.entries()) {
            var playerInfo = this.playerInfoMap.get(entry.profileId());
            if (playerInfo == null) {
                continue;
            }
            for (ClientboundPlayerInfoUpdatePacket.Action action : clientboundPlayerInfoUpdatePacket.actions()) {
                this.applyPlayerInfoUpdate(action, entry, playerInfo);
            }
        }
        forward(clientboundPlayerInfoUpdatePacket);
    }

    private void applyPlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket.Action action, ClientboundPlayerInfoUpdatePacket.Entry entry, PlayerInfo playerInfo) {
        switch (action) {
            case ADD_PLAYER, INITIALIZE_CHAT, UPDATE_LISTED -> {
            }
            case UPDATE_GAME_MODE -> {
                this.playerGameModes.put(entry.profileId(), entry.gameMode());
            }
            case UPDATE_LATENCY -> {
                // Latency only affects the live tab list forwarded above.
            }
            case UPDATE_DISPLAY_NAME -> playerInfo.setTabListDisplayName(entry.displayName());
        }
    }

    @Override
    public void handleRemoveEntities(ClientboundRemoveEntitiesPacket clientboundRemoveEntitiesPacket) {
        IntList forwardRemoveUnknown = new IntArrayList();
        clientboundRemoveEntitiesPacket.getEntityIds().forEach(i -> {
            Entity entity = this.level().getEntity(i);
            if (entity == null) {
                forwardRemoveUnknown.add(i);
            } else {
                entity.discard();
            }
        });
        if (!forwardRemoveUnknown.isEmpty()) {
            forward(new ClientboundRemoveEntitiesPacket(forwardRemoveUnknown));
        }
    }

    @Override
    public void handleRemoveMobEffect(ClientboundRemoveMobEffectPacket clientboundRemoveMobEffectPacket) {
        Entity entity = this.getEntityOrPending(clientboundRemoveMobEffectPacket.entityId());
        if (entity == null) {
            forward(clientboundRemoveMobEffectPacket);
        } else if (entity instanceof LivingEntity livingEntity) {
            livingEntity.removeEffect(clientboundRemoveMobEffectPacket.effect());
        }
    }

    @Override
    public void handleRespawn(ClientboundRespawnPacket clientboundRespawnPacket) {
        Entity localPlayer = null;
        ServerLevel level = this.level();
        if (level != null) {
            localPlayer = level.getEntity(this.localPlayerId);
        }

        this.ensureWorldCreated(clientboundRespawnPacket.commonPlayerSpawnInfo(), localPlayer, false);

        if (localPlayer instanceof ServerPlayer oldServerPlayer) {
            ClientboundAddEntityPacket addEntityPacket = new ClientboundAddEntityPacket(oldServerPlayer.getId(), oldServerPlayer.getUUID(),
                oldServerPlayer.getX(), oldServerPlayer.getY(), oldServerPlayer.getZ(), oldServerPlayer.getXRot(), oldServerPlayer.getYRot(), EntityType.PLAYER,
                0, Vec3.ZERO, oldServerPlayer.getYHeadRot());

            ServerPlayer newServerPlayer = this.spawnPlayer(addEntityPacket, oldServerPlayer.getGameProfile(), oldServerPlayer.gameMode.getGameModeForPlayer());
            if (newServerPlayer == null) {
                return;
            }

            // Player exposes getters but protects its two appearance accessors in 1.21.1. Copy only
            // those synced-data slots here so respawn still retains skin layers and main arm without
            // carrying unrelated state when the packet does not request it.
            List<SynchedEntityData.DataValue<?>> oldAppearanceData = oldServerPlayer.getEntityData().getNonDefaultValues();
            if (oldAppearanceData != null) {
                newServerPlayer.getEntityData().assignValues(oldAppearanceData.stream()
                    .filter(value -> value.id() == PLAYER_SKIN_PARTS_DATA_ID || value.id() == PLAYER_MAIN_ARM_DATA_ID)
                    .toList());
            }

            if (clientboundRespawnPacket.shouldKeep((byte)2)) {
                newServerPlayer.setShiftKeyDown(oldServerPlayer.isShiftKeyDown());
                newServerPlayer.setSprinting(oldServerPlayer.isSprinting());

                var oldEntityData = oldServerPlayer.getEntityData().getNonDefaultValues();
                if (oldEntityData != null) {
                    newServerPlayer.getEntityData().assignValues(oldEntityData);
                }
            }

            if (clientboundRespawnPacket.shouldKeep((byte)1)) {
                newServerPlayer.getAttributes().assignAllValues(oldServerPlayer.getAttributes());
            } else {
                newServerPlayer.getAttributes().assignBaseValues(oldServerPlayer.getAttributes());
            }
        }
    }

    @Override
    public void handleRotateMob(ClientboundRotateHeadPacket clientboundRotateHeadPacket) {
        Entity entity = this.getEntityOrPending(clientboundRotateHeadPacket.entityId);
        if (entity == null) {
            forward(clientboundRotateHeadPacket);
            return;
        }

        if (entity.getId() == this.localPlayerId) {
            return;
        }

        entity.setYHeadRot(clientboundRotateHeadPacket.getYHeadRot());
    }

    @Override
    public void handleSetCarriedItem(ClientboundSetCarriedItemPacket clientboundSetHeldSlotPacket) {
        if (!Inventory.isHotbarSlot(clientboundSetHeldSlotPacket.getSlot())) {
            return;
        }

        Entity entity = this.level().getEntity(this.localPlayerId);
        if (!(entity instanceof Player player)) {
            return;
        }

        Inventory inventory = player.getInventory();

        if (inventory.selected == clientboundSetHeldSlotPacket.getSlot()) {
            return;
        }

        inventory.selected = clientboundSetHeldSlotPacket.getSlot();

        for (ReplayPlayer replayViewer : this.replayServer.getReplayViewers()) {
            if (Objects.equals(replayViewer.lastFirstPersonDataUUID, player.getUUID())) {
                replayViewer.lastFirstPersonSelectedSlot = inventory.selected;
                ServerPlayNetworking.send(replayViewer, new FlashbackRemoteSelectHotbarSlot(player.getId(), inventory.selected));
            }
        }
    }

    @Override
    public void handleSetDisplayObjective(ClientboundSetDisplayObjectivePacket clientboundSetDisplayObjectivePacket) {
        forward(clientboundSetDisplayObjectivePacket);
    }

    @Override
    public void handleSetEntityData(ClientboundSetEntityDataPacket clientboundSetEntityDataPacket) {
        Entity entity = this.getEntityOrPending(clientboundSetEntityDataPacket.id());
        forward(entity, clientboundSetEntityDataPacket);

        if (entity != null) {
            SynchedEntityData entityData = entity.getEntityData();
            for (SynchedEntityData.DataValue<?> dataValue : clientboundSetEntityDataPacket.packedItems()) {
                SynchedEntityData.DataItem<?> dataItem = entityData.itemsById[dataValue.id()];
                entityData.set((EntityDataAccessor) dataItem.getAccessor(), dataValue.value(), true);
            }
        }
    }

    @Override
    public void handleSetEntityMotion(ClientboundSetEntityMotionPacket clientboundSetEntityMotionPacket) {
        Entity entity = this.getEntityOrPending(clientboundSetEntityMotionPacket.getId());
        forward(entity, clientboundSetEntityMotionPacket);

        if (entity != null) {
            entity.setDeltaMovement(clientboundSetEntityMotionPacket.getXa(), clientboundSetEntityMotionPacket.getYa(), clientboundSetEntityMotionPacket.getZa());
            entity.hasImpulse = true;
        }
    }

    @Override
    public void handleSetEquipment(ClientboundSetEquipmentPacket clientboundSetEquipmentPacket) {
        Entity entity = this.getEntityOrPending(clientboundSetEquipmentPacket.getEntity());
        forward(entity, clientboundSetEquipmentPacket);

        if (entity instanceof LivingEntity livingEntity) {
            clientboundSetEquipmentPacket.getSlots().forEach((pair) -> {
                livingEntity.setItemSlot(pair.getFirst(), pair.getSecond());
            });
        }
    }

    @Override
    public void handleSetExperience(ClientboundSetExperiencePacket clientboundSetExperiencePacket) {
        Entity entity = this.level().getEntity(this.localPlayerId);
        if (entity instanceof Player player) {
            player.experienceProgress = clientboundSetExperiencePacket.getExperienceProgress();
            player.totalExperience = clientboundSetExperiencePacket.getTotalExperience();
            player.experienceLevel = clientboundSetExperiencePacket.getExperienceLevel();

            for (ReplayPlayer replayViewer : this.replayServer.getReplayViewers()) {
                if (Objects.equals(replayViewer.lastFirstPersonDataUUID, player.getUUID())) {
                    replayViewer.lastFirstPersonExperienceProgress = player.experienceProgress;
                    replayViewer.lastFirstPersonTotalExperience = player.totalExperience;
                    replayViewer.lastFirstPersonExperienceLevel = player.experienceLevel;
                    ServerPlayNetworking.send(replayViewer, new FlashbackRemoteExperience(player.getId(), player.experienceProgress,
                        player.totalExperience, player.experienceLevel));
                }
            }
        }
    }

    @Override
    public void handleSetHealth(ClientboundSetHealthPacket clientboundSetHealthPacket) {
        Entity entity = this.level().getEntity(this.localPlayerId);
        if (entity instanceof Player player) {
            player.setHealth(clientboundSetHealthPacket.getHealth());

            int food = clientboundSetHealthPacket.getFood();
            float saturation = clientboundSetHealthPacket.getSaturation();

            player.getFoodData().setFoodLevel(food);
            player.getFoodData().setSaturation(saturation);

            for (ReplayPlayer replayViewer : this.replayServer.getReplayViewers()) {
                if (Objects.equals(replayViewer.lastFirstPersonDataUUID, player.getUUID())) {
                    replayViewer.lastFirstPersonFoodLevel = food;
                    replayViewer.lastFirstPersonSaturationLevel = saturation;
                    ServerPlayNetworking.send(replayViewer, new FlashbackRemoteFoodData(player.getId(), food, saturation));
                }
            }
        }
    }

    @Override
    public void handleSetPlayerTeamPacket(ClientboundSetPlayerTeamPacket clientboundSetPlayerTeamPacket) {
        forward(clientboundSetPlayerTeamPacket);
    }

    /**
     * Applies a menu slot of the recorded player's own inventory to the player, their interface and
     * the viewer's first-person hotbar.
     *
     * <p>The slot arrives numbered as a menu slot - the first five are the crafting grid, the next
     * four are armour, then the main inventory, then the hotbar and the offhand - and the client is
     * told the number it needs, which is the inventory's own. Writing one number where the other is
     * meant is what puts a hotbar change in an armour slot, or a chest's contents in a hotbar.
     */
    private void mirrorOwnInventory(int slot, ItemStack itemStack) {
        Entity entity = this.level().getEntity(this.localPlayerId);
        if (!(entity instanceof Player player)) {
            return;
        }
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;
        if (slot < 0 || slot >= inventoryMenu.slots.size()) {
            return;
        }

        net.minecraft.world.inventory.Slot menuSlot = inventoryMenu.getSlot(slot);
        // A server update may legitimately fill the crafting result or clear an armour slot;
        // mayPlace is a rule for player clicks, not for authoritative slot updates.
        menuSlot.set(itemStack.copy());

        if (menuSlot.container != player.getInventory()) {
            // The crafting result and grid are menu slots, not player inventory slots.
            return;
        }
        int inventoryIndex = InventoryMenuSlots.inventoryIndex(slot);

        if (inventoryIndex < 0 || inventoryIndex >= 9) {
            return;
        }
        for (ReplayPlayer replayViewer : this.replayServer.getReplayViewers()) {
            if (Objects.equals(replayViewer.lastFirstPersonDataUUID, player.getUUID())) {
                replayViewer.lastFirstPersonHotbarItems[inventoryIndex] = itemStack.copy();
                ServerPlayNetworking.send(replayViewer,
                    new FlashbackRemoteSetSlot(player.getId(), inventoryIndex, itemStack.copy()));
            }
        }
    }

    @Override
    public void handleSetScore(ClientboundSetScorePacket clientboundSetScorePacket) {
        forward(clientboundSetScorePacket);
    }

    @Override
    public void handleResetScore(ClientboundResetScorePacket clientboundResetScorePacket) {
        forward(clientboundResetScorePacket);
    }

    @Override
    public void handleSetSpawn(ClientboundSetDefaultSpawnPositionPacket clientboundSetDefaultSpawnPositionPacket) {
        this.level().setDefaultSpawnPos(clientboundSetDefaultSpawnPositionPacket.getPos(), clientboundSetDefaultSpawnPositionPacket.getAngle());
    }

    @Override
    public void handleSetTime(ClientboundSetTimePacket clientboundSetTimePacket) {
        boolean updateDaylight = true;
        long dayTime = clientboundSetTimePacket.getDayTime();
        if (dayTime < 0L) {
            dayTime = -dayTime;
            updateDaylight = false;
        }

        for (ServerLevel level : this.replayServer.getAllLevels()) {
            if (level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT) != updateDaylight) {
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(updateDaylight, this.replayServer);
            }
            level.setDayTime(dayTime);
            if (level.getLevelData() instanceof ServerLevelData serverLevelData) {
                serverLevelData.setGameTime(clientboundSetTimePacket.getGameTime());
            }
        }

        forward(clientboundSetTimePacket);
    }

    @Override
    public void handleSoundEvent(ClientboundSoundPacket clientboundSoundPacket) {
        Holder<SoundEvent> sound = clientboundSoundPacket.getSound();
        double x = clientboundSoundPacket.getX();
        double y = clientboundSoundPacket.getY();
        double z = clientboundSoundPacket.getZ();
        float volume = clientboundSoundPacket.getVolume();
        ResourceKey<Level> dimension = this.level().dimension();
        this.replayServer.getPlayerList().broadcast(null, x, y, z, sound.value().getRange(volume), dimension, clientboundSoundPacket);
    }

    @Override
    public void handleSoundEntityEvent(ClientboundSoundEntityPacket clientboundSoundEntityPacket) {
        Entity entity = this.level().getEntity(clientboundSoundEntityPacket.getId());
        if (entity == null) {
            forward(clientboundSoundEntityPacket);
            return;
        }

        Holder<SoundEvent> sound = clientboundSoundEntityPacket.getSound();
        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        float volume = clientboundSoundEntityPacket.getVolume();
        ResourceKey<Level> dimension = this.level().dimension();
        this.replayServer.getPlayerList().broadcast(null, x, y, z, sound.value().getRange(volume), dimension, clientboundSoundEntityPacket);
    }

    @Override
    public void handleTakeItemEntity(ClientboundTakeItemEntityPacket clientboundTakeItemEntityPacket) {
        Entity taker = this.level().getEntity(clientboundTakeItemEntityPacket.getPlayerId());
        forward(taker, clientboundTakeItemEntityPacket);

        Entity taken = this.level().getEntity(clientboundTakeItemEntityPacket.getItemId());
        if (taken != null) {
            taken.discard();
        }
    }

    @Override
    public void handleTeleportEntity(ClientboundTeleportEntityPacket clientboundTeleportEntityPacket) {
        Entity entity = this.getEntityOrPending(clientboundTeleportEntityPacket.getId());
        if (entity == null) {
            forward(clientboundTeleportEntityPacket);
            return;
        }

        float yaw = clientboundTeleportEntityPacket.getyRot() * 360.0F / 256.0F;
        float pitch = clientboundTeleportEntityPacket.getxRot() * 360.0F / 256.0F;
        entity.moveTo(clientboundTeleportEntityPacket.getX(), clientboundTeleportEntityPacket.getY(), clientboundTeleportEntityPacket.getZ(), yaw, pitch);
        entity.setOnGround(clientboundTeleportEntityPacket.isOnGround());
        entity.setYHeadRot(entity.getYRot());
    }

    @Override
    public void handleUpdateAttributes(ClientboundUpdateAttributesPacket clientboundUpdateAttributesPacket) {
        Entity entity = this.getEntityOrPending(clientboundUpdateAttributesPacket.getEntityId());
        if (entity == null) {
            forward(clientboundUpdateAttributesPacket);
            return;
        }
        if (entity instanceof LivingEntity livingEntity) {
            AttributeMap attributeMap = livingEntity.getAttributes();
            for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : clientboundUpdateAttributesPacket.getValues()) {
                AttributeInstance attributeInstance = attributeMap.getInstance(snapshot.attribute());
                if (attributeInstance == null) {
                    continue;
                }

                attributeInstance.setBaseValue(snapshot.base());
                attributeInstance.removeModifiers();
                for (AttributeModifier modifier : snapshot.modifiers()) {
                    attributeInstance.addTransientModifier(modifier);
                }
            }
        }
    }

    @Override
    public void handleUpdateMobEffect(ClientboundUpdateMobEffectPacket clientboundUpdateMobEffectPacket) {
        Entity entity = this.getEntityOrPending(clientboundUpdateMobEffectPacket.getEntityId());
        if (entity == null) {
            forward(clientboundUpdateMobEffectPacket);
            return;
        }
        if (entity instanceof LivingEntity livingEntity) {
            Holder<MobEffect> holder = clientboundUpdateMobEffectPacket.getEffect();
            MobEffectInstance mobEffectInstance = new MobEffectInstance(holder, clientboundUpdateMobEffectPacket.getEffectDurationTicks(), clientboundUpdateMobEffectPacket.getEffectAmplifier(),
                clientboundUpdateMobEffectPacket.isEffectAmbient(), clientboundUpdateMobEffectPacket.isEffectVisible(), clientboundUpdateMobEffectPacket.effectShowsIcon(), null);
            if (!clientboundUpdateMobEffectPacket.shouldBlend()) {
                mobEffectInstance.skipBlending();
            }

            livingEntity.forceAddEffect(mobEffectInstance, null);
        }
    }

    @Override
    public void handlePlayerCombatEnd(ClientboundPlayerCombatEndPacket clientboundPlayerCombatEndPacket) {
        throw new UnsupportedPacketException(clientboundPlayerCombatEndPacket);
    }

    @Override
    public void handlePlayerCombatEnter(ClientboundPlayerCombatEnterPacket clientboundPlayerCombatEnterPacket) {
        throw new UnsupportedPacketException(clientboundPlayerCombatEnterPacket);
    }

    @Override
    public void handlePlayerCombatKill(ClientboundPlayerCombatKillPacket clientboundPlayerCombatKillPacket) {
        throw new UnsupportedPacketException(clientboundPlayerCombatKillPacket);
    }

    @Override
    public void handleTickingState(ClientboundTickingStatePacket packet) {
        // Replay playback is driven by ReplayServer's own tick-rate controller.
    }

    @Override
    public void handleTickingStep(ClientboundTickingStepPacket packet) {
        // Single-step requests from the recorded server must not advance the replay server.
    }

    @Override
    public void handleChangeDifficulty(ClientboundChangeDifficultyPacket clientboundChangeDifficultyPacket) {
        this.replayServer.setDifficultyLocked(clientboundChangeDifficultyPacket.isLocked());
        this.replayServer.setDifficulty(clientboundChangeDifficultyPacket.getDifficulty(), true);
    }

    @Override
    public void handleSetCamera(ClientboundSetCameraPacket clientboundSetCameraPacket) {
        throw new UnsupportedPacketException(clientboundSetCameraPacket);
    }

    @Override
    public void handleInitializeBorder(ClientboundInitializeBorderPacket clientboundInitializeBorderPacket) {
        WorldBorder worldBorder = this.level().getWorldBorder();

        worldBorder.setCenter(clientboundInitializeBorderPacket.getNewCenterX(), clientboundInitializeBorderPacket.getNewCenterZ());
        long lerpTime = clientboundInitializeBorderPacket.getLerpTime();
        if (lerpTime > 0L) {
            worldBorder.lerpSizeBetween(clientboundInitializeBorderPacket.getOldSize(), clientboundInitializeBorderPacket.getNewSize(), lerpTime);
        } else {
            worldBorder.setSize(clientboundInitializeBorderPacket.getNewSize());
        }
        worldBorder.setAbsoluteMaxSize(clientboundInitializeBorderPacket.getNewAbsoluteMaxSize());
        worldBorder.setWarningBlocks(clientboundInitializeBorderPacket.getWarningBlocks());
        worldBorder.setWarningTime(clientboundInitializeBorderPacket.getWarningTime());
    }

    @Override
    public void handleSetBorderLerpSize(ClientboundSetBorderLerpSizePacket clientboundSetBorderLerpSizePacket) {
        WorldBorder worldBorder = this.level().getWorldBorder();

        long lerpTime = clientboundSetBorderLerpSizePacket.getLerpTime();
        if (lerpTime > 0L) {
            worldBorder.lerpSizeBetween(clientboundSetBorderLerpSizePacket.getOldSize(), clientboundSetBorderLerpSizePacket.getNewSize(), lerpTime);
        } else {
            worldBorder.setSize(clientboundSetBorderLerpSizePacket.getNewSize());
        }
    }

    @Override
    public void handleSetBorderSize(ClientboundSetBorderSizePacket clientboundSetBorderSizePacket) {
        WorldBorder worldBorder = this.level().getWorldBorder();
        worldBorder.setSize(clientboundSetBorderSizePacket.getSize());
    }

    @Override
    public void handleSetBorderWarningDelay(ClientboundSetBorderWarningDelayPacket clientboundSetBorderWarningDelayPacket) {
        WorldBorder worldBorder = this.level().getWorldBorder();
        worldBorder.setWarningTime(clientboundSetBorderWarningDelayPacket.getWarningDelay());
    }

    @Override
    public void handleSetBorderWarningDistance(ClientboundSetBorderWarningDistancePacket clientboundSetBorderWarningDistancePacket) {
        WorldBorder worldBorder = this.level().getWorldBorder();
        worldBorder.setWarningBlocks(clientboundSetBorderWarningDistancePacket.getWarningBlocks());
    }

    @Override
    public void handleSetBorderCenter(ClientboundSetBorderCenterPacket clientboundSetBorderCenterPacket) {
        WorldBorder worldBorder = this.level().getWorldBorder();
        worldBorder.setCenter(clientboundSetBorderCenterPacket.getNewCenterX(), clientboundSetBorderCenterPacket.getNewCenterZ());
    }

    @Override
    public void handleTabListCustomisation(ClientboundTabListPacket clientboundTabListPacket) {
        this.replayServer.setTabListCustomization(clientboundTabListPacket.header(),
            clientboundTabListPacket.footer());
    }

    @Override
    public void handleBossUpdate(ClientboundBossEventPacket clientboundBossEventPacket) {
        this.replayServer.updateBossBar(clientboundBossEventPacket);
    }

    @Override
    public void handleItemCooldown(ClientboundCooldownPacket clientboundCooldownPacket) {
        throw new UnsupportedPacketException(clientboundCooldownPacket);
    }

    @Override
    public void handleMoveVehicle(ClientboundMoveVehiclePacket clientboundMoveVehiclePacket) {
        Entity player = this.level().getEntity(this.localPlayerId);
        if (player instanceof Player) {
            Entity vehicle = player.getRootVehicle();
            if (vehicle != player) {
                vehicle.moveTo(clientboundMoveVehiclePacket.getX(), clientboundMoveVehiclePacket.getY(),
                    clientboundMoveVehiclePacket.getZ(), clientboundMoveVehiclePacket.getYRot(),
                    clientboundMoveVehiclePacket.getXRot());
            }
        }
    }

    @Override
    public void handleUpdateAdvancementsPacket(ClientboundUpdateAdvancementsPacket clientboundUpdateAdvancementsPacket) {
        throw new UnsupportedPacketException(clientboundUpdateAdvancementsPacket);
    }

    @Override
    public void handleSelectAdvancementsTab(ClientboundSelectAdvancementsTabPacket clientboundSelectAdvancementsTabPacket) {
        throw new UnsupportedPacketException(clientboundSelectAdvancementsTabPacket);
    }

    @Override
    public void handlePlaceRecipe(ClientboundPlaceGhostRecipePacket clientboundPlaceGhostRecipePacket) {
        throw new UnsupportedPacketException(clientboundPlaceGhostRecipePacket);
    }

    @Override
    public void handleCommands(ClientboundCommandsPacket clientboundCommandsPacket) {
        throw new UnsupportedPacketException(clientboundCommandsPacket);
    }

    @Override
    public void handleStopSoundEvent(ClientboundStopSoundPacket clientboundStopSoundPacket) {
        forward(clientboundStopSoundPacket);
    }

    @Override
    public void handleCommandSuggestions(ClientboundCommandSuggestionsPacket clientboundCommandSuggestionsPacket) {
        throw new UnsupportedPacketException(clientboundCommandSuggestionsPacket);
    }

    @Override
    public void handleUpdateRecipes(ClientboundUpdateRecipesPacket clientboundUpdateRecipesPacket) {
        throw new UnsupportedPacketException(clientboundUpdateRecipesPacket);
    }

    @Override
    public void handleLookAt(ClientboundPlayerLookAtPacket clientboundPlayerLookAtPacket) {
        Vec3 position = clientboundPlayerLookAtPacket.getPosition(this.level());
        if (position != null) {
            Entity player = this.level().getEntity(this.localPlayerId);
            if (player instanceof Player) {
                player.lookAt(clientboundPlayerLookAtPacket.getFromAnchor(), position);
            }
        }
    }

    @Override
    public void handleTagQueryPacket(ClientboundTagQueryPacket clientboundTagQueryPacket) {
        throw new UnsupportedPacketException(clientboundTagQueryPacket);
    }

    @Override
    public void handleLightUpdatePacket(ClientboundLightUpdatePacket clientboundLightUpdatePacket) {
        int x = clientboundLightUpdatePacket.getX();
        int z = clientboundLightUpdatePacket.getZ();

        LevelLightEngine levelLightEngine = this.level().getChunkSource().getLightEngine();
        LevelChunk chunk = this.level().getChunk(x, z);

        var lightData = clientboundLightUpdatePacket.getLightData();
        this.applyLightData(levelLightEngine, x, z, lightData);

        chunk.setUnsaved(true);
    }

    @Override
    public void handleOpenBook(ClientboundOpenBookPacket clientboundOpenBookPacket) {
        throw new UnsupportedPacketException(clientboundOpenBookPacket);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void handleOpenScreen(ClientboundOpenScreenPacket packet) {
        GuiDisplayForwarder.open(this.replayServer, packet.getContainerId(), packet.getType(), packet.getTitle());
    }

    @Override
    public void handleMerchantOffers(ClientboundMerchantOffersPacket packet) {
        // The screen itself comes from the open packet, but a villager's rows are the offers, not the
        // container's slots - nothing else in the recording carries them, so they have to be passed on
        // or the trading screen draws with no trades in it.
        GuiDisplayForwarder.offers(this.replayServer, packet);
    }

    @Override
    public void handleSetChunkCacheRadius(ClientboundSetChunkCacheRadiusPacket clientboundSetChunkCacheRadiusPacket) {
        throw new UnsupportedPacketException(clientboundSetChunkCacheRadiusPacket);
    }

    @Override
    public void handleSetSimulationDistance(ClientboundSetSimulationDistancePacket clientboundSetSimulationDistancePacket) {
        throw new UnsupportedPacketException(clientboundSetSimulationDistancePacket);
    }

    @Override
    public void handleSetChunkCacheCenter(ClientboundSetChunkCacheCenterPacket clientboundSetChunkCacheCenterPacket) {
        throw new UnsupportedPacketException(clientboundSetChunkCacheCenterPacket);
    }

    @Override
    public void handleBlockChangedAck(ClientboundBlockChangedAckPacket clientboundBlockChangedAckPacket) {
        throw new UnsupportedPacketException(clientboundBlockChangedAckPacket);
    }

    @Override
    public void setActionBarText(ClientboundSetActionBarTextPacket clientboundSetActionBarTextPacket) {
        forward(clientboundSetActionBarTextPacket);
    }

    @Override
    public void setSubtitleText(ClientboundSetSubtitleTextPacket clientboundSetSubtitleTextPacket) {
        forward(clientboundSetSubtitleTextPacket);
    }

    @Override
    public void setTitleText(ClientboundSetTitleTextPacket clientboundSetTitleTextPacket) {
        forward(clientboundSetTitleTextPacket);
    }

    @Override
    public void setTitlesAnimation(ClientboundSetTitlesAnimationPacket clientboundSetTitlesAnimationPacket) {
        forward(clientboundSetTitlesAnimationPacket);
    }

    @Override
    public void handleTitlesClear(ClientboundClearTitlesPacket clientboundClearTitlesPacket) {
        forward(clientboundClearTitlesPacket);
    }

    @Override
    public void handleServerData(ClientboundServerDataPacket clientboundServerDataPacket) {
        forward(clientboundServerDataPacket);
    }

    @Override
    public void handleCustomChatCompletions(ClientboundCustomChatCompletionsPacket clientboundCustomChatCompletionsPacket) {
        throw new UnsupportedPacketException(clientboundCustomChatCompletionsPacket);
    }

    @Override
    public void handleBundlePacket(ClientboundBundlePacket clientboundBundlePacket) {
        for (Packet<? super ClientGamePacketListener> subPacket : clientboundBundlePacket.subPackets()) {
            subPacket.handle(this);
        }
    }

    @Override
    public void handleDamageEvent(ClientboundDamageEventPacket clientboundDamageEventPacket) {
        Entity entity = this.level().getEntity(clientboundDamageEventPacket.entityId());
        forward(entity, clientboundDamageEventPacket);
    }

    @Override
    public void handleConfigurationStart(ClientboundStartConfigurationPacket clientboundStartConfigurationPacket) {
        throw new UnsupportedPacketException(clientboundStartConfigurationPacket);
    }

    @Override
    public void handleChunkBatchStart(ClientboundChunkBatchStartPacket clientboundChunkBatchStartPacket) {
        throw new UnsupportedPacketException(clientboundChunkBatchStartPacket);
    }

    @Override
    public void handleChunkBatchFinished(ClientboundChunkBatchFinishedPacket clientboundChunkBatchFinishedPacket) {
        throw new UnsupportedPacketException(clientboundChunkBatchFinishedPacket);
    }

    @Override
    public void handleDebugSample(ClientboundDebugSamplePacket clientboundDebugSamplePacket) {
        throw new UnsupportedPacketException(clientboundDebugSamplePacket);
    }

    @Override
    public void handleProjectilePowerPacket(ClientboundProjectilePowerPacket clientboundProjectilePowerPacket) {
        Entity entity = this.level().getEntity(clientboundProjectilePowerPacket.getId());
        forward(entity, clientboundProjectilePowerPacket);
    }

    @Override
    public void handleKeepAlive(ClientboundKeepAlivePacket clientboundKeepAlivePacket) {
        throw new UnsupportedPacketException(clientboundKeepAlivePacket);
    }

    @Override
    public void handlePing(ClientboundPingPacket clientboundPingPacket) {
        throw new UnsupportedPacketException(clientboundPingPacket);
    }

    @Override
    public void handleCustomPayload(ClientboundCustomPayloadPacket clientboundCustomPayloadPacket) {
        throw new UnsupportedPacketException(clientboundCustomPayloadPacket);
    }

    @Override
    public void handleDisconnect(ClientboundDisconnectPacket clientboundDisconnectPacket) {
        throw new UnsupportedPacketException(clientboundDisconnectPacket);
    }

    @Override
    public void handleResourcePackPush(ClientboundResourcePackPushPacket clientboundResourcePackPushPacket) {
        this.replayServer.pushRemotePack(clientboundResourcePackPushPacket.id(),
            clientboundResourcePackPushPacket.url(), clientboundResourcePackPushPacket.hash());
    }

    @Override
    public void handleResourcePackPop(ClientboundResourcePackPopPacket clientboundResourcePackPopPacket) {
        if (clientboundResourcePackPopPacket.id().isEmpty()) {
            this.replayServer.popAllRemotePacks();
        } else {
            this.replayServer.popRemotePack(clientboundResourcePackPopPacket.id().get());
        }
    }

    @Override
    public void handleUpdateTags(ClientboundUpdateTagsPacket clientboundUpdateTagsPacket) {
        clientboundUpdateTagsPacket.getTags().forEach((resourceKey, networkPayload) -> {
            var registry = this.replayServer.registryAccess().registryOrThrow(resourceKey);
            registry.resetTags();
            networkPayload.applyToRegistry(registry);
        });

        forward(clientboundUpdateTagsPacket);
    }

    @Override
    public void handleRequestCookie(ClientboundCookieRequestPacket packet) {
        throw new UnsupportedPacketException(packet);
    }

    @Override
    public void handleStoreCookie(ClientboundStoreCookiePacket clientboundStoreCookiePacket) {
        throw new UnsupportedPacketException(clientboundStoreCookiePacket);
    }

    @Override
    public void handleTransfer(ClientboundTransferPacket clientboundTransferPacket) {
        throw new UnsupportedPacketException(clientboundTransferPacket);
    }

    @Override
    public void handleCustomReportDetails(ClientboundCustomReportDetailsPacket clientboundCustomReportDetailsPacket) {
        throw new UnsupportedPacketException(clientboundCustomReportDetailsPacket);
    }

    @Override
    public void handleServerLinks(ClientboundServerLinksPacket clientboundServerLinksPacket) {
        throw new UnsupportedPacketException(clientboundServerLinksPacket);
    }

    @Override
    public void handlePongResponse(ClientboundPongResponsePacket packet) {
        throw new UnsupportedPacketException(packet);
    }

    @Override
    public void onDisconnect(DisconnectionDetails disconnectionDetails) {
    }

    @Override
    public boolean isAcceptingMessages() {
        return false;
    }

}

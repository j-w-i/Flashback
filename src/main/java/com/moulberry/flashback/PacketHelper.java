package com.moulberry.flashback;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.util.ArrayList;

public class PacketHelper {

    private static final Constructor<ClientboundTeleportEntityPacket> TELEPORT_FROM_BUFFER = teleportPacketBufferConstructor();

    private static Constructor<ClientboundTeleportEntityPacket> teleportPacketBufferConstructor() {
        try {
            Constructor<ClientboundTeleportEntityPacket> constructor = ClientboundTeleportEntityPacket.class
                .getDeclaredConstructor(FriendlyByteBuf.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static boolean shouldIgnoreEntity(Entity entity) {
        return entity == null || entity.isRemoved() || entity instanceof EnderDragonPart || entity.getType().clientTrackingRange() <= 0;
    }

    /**
     * The 1.21.1 teleport packet only exposes an Entity constructor. Reuse its vanilla wire decoder
     * to create the same packet for a tracked id whose server-side entity is already gone.
     */
    public static Packet<ClientGamePacketListener> createTeleportForUnknown(int id, double x, double y, double z, byte yRot, byte xRot, boolean onGround) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(id);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeByte(yRot);
            buf.writeByte(xRot);
            buf.writeBoolean(onGround);
            return TELEPORT_FROM_BUFFER.newInstance(buf);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to construct a 1.21.1 entity teleport packet", e);
        } finally {
            buf.release();
        }
    }

    public static Packet<ClientGamePacketListener> createAddEntity(Entity entity) {
        ServerEntity serverEntity = null;

        // Try to construct ServerEntity with dummy values.
        try {
            serverEntity = new ServerEntity(null, entity, 1, false, packet -> { });
        } catch (Exception ignored) {
        }

        // Some mods make the constructor fail; populate only the state used by add-packet creation.
        if (serverEntity == null) {
            try {
                serverEntity = (ServerEntity) UnsafeWrapper.UNSAFE.allocateInstance(ServerEntity.class);
                serverEntity.positionCodec = new VecDeltaCodec();
                serverEntity.lastPassengers = new ArrayList<>();
                serverEntity.broadcast = packet -> { };
                serverEntity.entity = entity;
                serverEntity.positionCodec.setBase(entity.trackingPosition());
                serverEntity.lastSentMovement = entity.getDeltaMovement();
                serverEntity.lastSentYRot = Mth.floor(entity.getYRot() * 256.0f / 360.0f);
                serverEntity.lastSentXRot = Mth.floor(entity.getXRot() * 256.0f / 360.0f);
                serverEntity.lastSentYHeadRot = Mth.floor(entity.getYHeadRot() * 256.0f / 360.0f);
                serverEntity.wasOnGround = entity.onGround();
                serverEntity.trackedDataValues = entity.getEntityData().getNonDefaultValues();
            } catch (Exception ignored) {
            }
        }

        try {
            return entity.getAddEntityPacket(serverEntity);
        } catch (Exception ignored) {
        }

        return createAddEntity(entity, 0);
    }

    public static ClientboundAddEntityPacket createAddEntity(Entity entity, int data) {
        return new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
            entity.getXRot(), entity.getYRot(), entity.getType(), data, entity.getDeltaMovement(), entity.getYHeadRot());
    }
}

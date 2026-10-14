package com.moulberry.flashback;

import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;

public class CachedChunkPacket {
    private static final VarHandle LONG_ARRAY = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private final int x;
    private final int z;
    private final byte[] bigHash;
    public final long longHashCode;
    public int index;

    public CachedChunkPacket(ClientboundLevelChunkWithLightPacket packet, int index) {
        //keysmash random numbers used
        this.x = packet.getX();
        this.z = packet.getZ();
        this.bigHash = computePacketBigHash(packet);
        if (this.bigHash.length == 64) {//sha-512
            long hash = 982374698276290847L;
            for (int i = 0; i < 8; i++) {
                hash ^= (long)LONG_ARRAY.get(this.bigHash, i);
                hash *= 209648290153981L;
                hash += 164923702968709L;
            }
            this.longHashCode = hash;
        } else if (this.bigHash.length == 32) {//sha-256
            long hash = 150939871908751L;
            for (int i = 0; i < 4; i++) {
                hash ^= (long)LONG_ARRAY.get(this.bigHash, i);
                hash *= 209648290153981L;
                hash += 164923702968709L;
            }
            this.longHashCode = hash;
        } else {//Something went horrifically wrong
            this.longHashCode = Arrays.hashCode(this.bigHash);
        }
        this.index = index;
    }

    private static byte[] computePacketBigHash(ClientboundLevelChunkWithLightPacket packet) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e2) {
                throw new RuntimeException(e2);
            }
        }

        ClientboundLevelChunkPacketData chunkData = packet.getChunkData();
        digest.update(intToByteArray(packet.getX()));
        digest.update(intToByteArray(packet.getZ()));
        digest.update(chunkData.buffer);

        FriendlyByteBuf lightBuffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.getLightData().write(lightBuffer);
            digest.update(lightBuffer.array(), 0, lightBuffer.writerIndex());
        } finally {
            lightBuffer.release();
        }

        // BlockEntityInfo fields are package-private in 1.21.1. Encode each complete entry with the
        // packet's public codec, then sort the encoded entries to keep the hash independent of list
        // iteration order (including block entities with no update tag).
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        ArrayList<byte[]> blockEntityEntries = new ArrayList<>(chunkData.blockEntitiesData.size());
        for (ClientboundLevelChunkPacketData.BlockEntityInfo blockEntityInfo : chunkData.blockEntitiesData) {
            FriendlyByteBuf entryBuffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                RegistryFriendlyByteBuf registryBuffer = new RegistryFriendlyByteBuf(entryBuffer, registryAccess);
                ClientboundLevelChunkPacketData.BlockEntityInfo.STREAM_CODEC.encode(registryBuffer, blockEntityInfo);
                byte[] encodedEntry = new byte[entryBuffer.writerIndex()];
                entryBuffer.getBytes(0, encodedEntry);
                blockEntityEntries.add(encodedEntry);
            } finally {
                entryBuffer.release();
            }
        }
        blockEntityEntries.sort(Arrays::compareUnsigned);
        for (byte[] entry : blockEntityEntries) {
            digest.update(entry);
        }

        return digest.digest();
    }

    private static byte[] intToByteArray(int value) {
        return new byte[] {
                (byte)(value >>> 24),
                (byte)(value >>> 16),
                (byte)(value >>> 8),
                (byte)value};
    }

    @Override
    public int hashCode() {
        return (int) this.longHashCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CachedChunkPacket that)) return false;
        if (this.longHashCode != that.longHashCode) {
            return false;
        }
        if (this.x != that.x || this.z != that.z) {
            return false;
        }

        return Arrays.equals(this.bigHash, that.bigHash);
    }
}

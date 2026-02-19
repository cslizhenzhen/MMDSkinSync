package com.opdent.mmdskin.sync.network;

import com.opdent.mmdskin.sync.MMDSyncMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SyncUrlPacket(String url) implements CustomPacketPayload {
    public static final Type<SyncUrlPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MMDSyncMod.MODID, "sync_url"));

    public static final StreamCodec<FriendlyByteBuf, SyncUrlPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SyncUrlPacket::url,
            SyncUrlPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

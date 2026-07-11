package com.kishku7.chunksmith.lod.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The one and only in-band payload: a raw byte block.
 *
 * <p>Deliberately dumb. ALL the protocol lives in {@code CsLodMessages} in shared_common, which knows
 * nothing about Minecraft -- so the Chunksmith server and Chunksmith-Client (a different mod, in a
 * different repo, possibly on a different loader) can share one implementation of the wire format instead
 * of maintaining two that drift.
 *
 * <p>The same bytes also travel over the HTTP backchannel and sit on disk in the store. One format,
 * three uses.
 */
public record CsLodPayload(byte[] data) implements CustomPacketPayload {

    public static final Type<CsLodPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(CsLodProtocol.NAMESPACE, CsLodProtocol.CHANNEL));

    public static final StreamCodec<RegistryFriendlyByteBuf, CsLodPayload> CODEC =
            StreamCodec.of(CsLodPayload::write, CsLodPayload::read);

    private static void write(final RegistryFriendlyByteBuf buf, final CsLodPayload payload) {
        buf.writeByteArray(payload.data());
    }

    private static CsLodPayload read(final RegistryFriendlyByteBuf buf) {
        return new CsLodPayload(buf.readByteArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

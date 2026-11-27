package sinanmap.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：报到。
 *
 * <p>同时告诉服务端两件事：本端司南的协议版本，以及<b>愿不愿意把自己的位置共享给别人</b>。
 * 设置里那个开关一改就重发一次，服务端据此立刻停止或恢复下发。
 */
public record HelloC2S(int protocol, boolean sharePosition) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<HelloC2S> TYPE =
			new CustomPacketPayload.Type<>(SinanNet.id("hello"));

	public static final StreamCodec<RegistryFriendlyByteBuf, HelloC2S> STREAM_CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeVarInt(value.protocol());
				buf.writeBoolean(value.sharePosition());
			},
			buf -> new HelloC2S(buf.readVarInt(), buf.readBoolean()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

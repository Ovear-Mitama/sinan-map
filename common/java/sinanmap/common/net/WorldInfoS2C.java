package sinanmap.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：这个世界的身份。
 *
 * <p>{@code worldId} 由服务端在存档里生成一次、永久不变，客户端拿它当"这是哪个世界"的凭据
 * （见 {@code MapStorage#setServerWorldId}）。有了它，同一个服务器换个地址连进来时地图数据与路径点
 * 仍然能对上——地址不是身份，会变。
 */
public record WorldInfoS2C(int protocol, String worldId) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<WorldInfoS2C> TYPE =
			new CustomPacketPayload.Type<>(SinanNet.id("world_info"));

	public static final StreamCodec<RegistryFriendlyByteBuf, WorldInfoS2C> STREAM_CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeVarInt(value.protocol());
				buf.writeUtf(value.worldId());
			},
			buf -> new WorldInfoS2C(buf.readVarInt(), buf.readUtf()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

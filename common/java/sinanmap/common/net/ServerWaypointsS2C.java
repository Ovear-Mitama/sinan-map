package sinanmap.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：服务端那边定义的一组路径点。
 *
 * <p>内容直接用<b>路径点自己的文本格式</b>（见 {@code WaypointStore.serialize}）：两端共用同一份
 * 序列化，以后给路径点加字段时两边自动跟着走，不必在这里再维护一套字段级编解码——那份格式本来就
 * 为"存得住、读得回来"设计过。
 */
public record ServerWaypointsS2C(String encoded) implements CustomPacketPayload {

	/** 单包上限（字符）：路径点整份也就几十 KB，留 1 MB 足够，同时挡住畸形包。 */
	private static final int MAX_LENGTH = 1 << 20;

	public static final CustomPacketPayload.Type<ServerWaypointsS2C> TYPE =
			new CustomPacketPayload.Type<>(SinanNet.id("server_waypoints"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ServerWaypointsS2C> STREAM_CODEC = StreamCodec.of(
			(buf, value) -> buf.writeUtf(value.encoded(), MAX_LENGTH),
			buf -> new ServerWaypointsS2C(buf.readUtf(MAX_LENGTH)));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

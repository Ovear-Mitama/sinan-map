package sinanmap.common.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：这一批"愿意被看到"的玩家坐标。
 *
 * <p>整批下发而不是逐条增删：列表很短（服务端会按距离裁剪），整批覆盖比维护增量简单得多，
 * 也不会因为丢一个包就留下一个不存在的幽灵头像。空列表就是"现在一个都看不到"。
 */
public record TrackedPlayersS2C(List<Player> players) implements CustomPacketPayload {

	/** 一条被同步过来的玩家位置。 */
	public record Player(UUID id, String name, String dimension, double x, double y, double z) {
	}

	public static final CustomPacketPayload.Type<TrackedPlayersS2C> TYPE =
			new CustomPacketPayload.Type<>(SinanNet.id("tracked_players"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrackedPlayersS2C> STREAM_CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeVarInt(value.players().size());
				for (Player player : value.players()) {
					buf.writeUUID(player.id());
					buf.writeUtf(player.name());
					buf.writeUtf(player.dimension());
					buf.writeDouble(player.x());
					buf.writeDouble(player.y());
					buf.writeDouble(player.z());
				}
			},
			buf -> {
				int size = buf.readVarInt();
				List<Player> players = new ArrayList<>(size);
				for (int index = 0; index < size; index++) {
					players.add(new Player(buf.readUUID(), buf.readUtf(), buf.readUtf(),
							buf.readDouble(), buf.readDouble(), buf.readDouble()));
				}
				return new TrackedPlayersS2C(List.copyOf(players));
			});

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

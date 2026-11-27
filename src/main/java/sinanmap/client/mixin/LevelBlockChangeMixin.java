package sinanmap.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sinanmap.client.map.data.ClientChunkColorProvider;

/**
 * 客户端每次方块变化后，通知地图立刻重采那一块。
 *
 * <p>{@code Level#setBlock} 是客户端所有方块变化的共同入口：自己放/破、附近玩家改、爆炸、
 * 服务端推来的方块更新包最后都走到这里，因此不必区分改动来自哪里。
 */
@Mixin(Level.class)
public class LevelBlockChangeMixin {

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
			at = @At("RETURN"))
	private void sinanmap$recordBlockChange(BlockPos pos, BlockState state, int flags, int recursionLeft,
			CallbackInfoReturnable<Boolean> info) {
		if (info.getReturnValueZ()) {
			ClientChunkColorProvider.recordBlockChange(pos.getX() >> 4, pos.getZ() >> 4);
		}
	}
}

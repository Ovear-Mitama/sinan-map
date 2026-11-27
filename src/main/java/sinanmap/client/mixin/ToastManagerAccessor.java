package sinanmap.client.mixin;

import java.util.BitSet;

import net.minecraft.client.gui.components.toasts.ToastManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读右上角弹窗占着的槽位（原版没有公开查询），小地图据此决定往下让多少。 */
@Mixin(ToastManager.class)
public interface ToastManagerAccessor {

	@Accessor("occupiedSlots")
	BitSet sinanmap$occupiedSlots();
}

package jp.six.fastfastmap.mixin.client;

import jp.six.fastfastmap.map.FastMap;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ClientWorld.handleBlockUpdate() にフックして、
 * サーバーからブロック変更を受信したときにミニマップを更新する。
 *
 * JourneyMap の ClientLevelMixin と同じアプローチ。
 */
@Mixin(ClientWorld.class)
public class ClientWorldMixin {

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void onHandleBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        FastMap.getInstance().onBlockUpdate((ClientWorld) (Object) this, pos);
    }
}

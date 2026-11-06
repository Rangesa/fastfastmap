package jp.six.fastfastmap.mixin.client;

import jp.six.fastfastmap.map.FastMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
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
@Mixin(ClientLevel.class)
public class ClientWorldMixin {

    @Inject(method = "setServerVerifiedBlockState", at = @At("TAIL"))
    private void onHandleBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        FastMap.getInstance().onBlockUpdate((ClientLevel) (Object) this, pos);
    }
}

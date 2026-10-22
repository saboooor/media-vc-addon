package ca.saboor.discs.screen.companion.mixin;

import ca.saboor.discs.screen.companion.ScreensClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class VideoFrameUploadMixin {
    // Omit target arguments: render takes DeltaTracker/boolean before 26.3 and no arguments in 26.3.
    @Inject(method = "render", at = @At("HEAD"))
    private void vcb$uploadVideoFrames(CallbackInfo ci) {
        ScreensClient.uploadReadyFrames();
    }
}

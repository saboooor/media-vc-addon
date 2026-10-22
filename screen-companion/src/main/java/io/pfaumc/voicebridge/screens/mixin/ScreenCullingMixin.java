package io.pfaumc.voicebridge.screens.mixin;

import io.pfaumc.voicebridge.screens.ScreensClient;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public class ScreenCullingMixin {
    // A large screen can be visible while its upper-left anchor is outside the frustum.
    @Inject(method = "affectedByCulling", at = @At("HEAD"), cancellable = true)
    private void vcb$wholeScreen(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof ItemFrame frame) {
            var id = frame.getFramedMapId(frame.getItem());
            if (id != null && ScreensClient.isAnchor(id.id())) cir.setReturnValue(false);
        }
    }
}

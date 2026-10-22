package io.pfaumc.voicebridge.screens.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import io.pfaumc.voicebridge.screens.ScreensClient;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draw the complete video as one screen-sized surface, bypassing the frame model and map decorations. */
@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameVideoMixin {
    @Shadow @Final private MapRenderer mapRenderer;

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void vcb$videoSurface(ItemFrameRenderState state, PoseStack pose,
                                  SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if (state.mapId == null) return;
        ScreensClient.Texture surface = ScreensClient.surface(state.mapId.id());
        if (surface == null) return;
        // Every other frame is only a placement anchor: the complete image is submitted once.
        if (!ScreensClient.isAnchor(state.mapId.id())) { ci.cancel(); return; }

        Direction facing = state.direction;
        pose.pushPose();
        // Undo the entity renderer's frame offset, then locate the block-sized face.
        pose.translate(-facing.getStepX() * 0.3, 0.25, -facing.getStepZ() * 0.3);
        pose.translate(facing.getStepX() * 0.46875, facing.getStepY() * 0.46875,
                facing.getStepZ() * 0.46875);
        if (facing.getAxis().isHorizontal()) {
            pose.mulPose(new Matrix4f().rotation(Axis.YP.rotationDegrees(180.0F - facing.toYRot())));
        } else {
            pose.mulPose(new Matrix4f().rotation(Axis.XP.rotationDegrees(-90.0F * facing.getAxisDirection().getStep())));
            pose.mulPose(new Matrix4f().rotation(Axis.YP.rotationDegrees(180.0F)));
        }
        // Orient the complete display from the server grid, independently of item rotation.
        pose.translate(0.0F, 0.0F, 0.5F);
        pose.mulPose(new Matrix4f().rotation(Axis.ZP.rotationDegrees(180.0F)));
        if (facing == Direction.DOWN) pose.scale(1.0F, -1.0F, 1.0F);
        pose.translate((surface.columns() - 1) * 0.5F, (surface.rows() - 1) * 0.5F, 0.0F);
        // MapRenderer's quad is 128 units wide: stretch that single quad over the screen.
        pose.scale(surface.columns() / 128.0F, surface.rows() / 128.0F, 1.0F / 128.0F);
        pose.translate(-64.0F, -64.0F, -1.0F);
        MapRenderState video = new MapRenderState();
        video.texture = surface.id();
        mapRenderer.render(video, pose, collector, true, 0xF000F0);
        pose.popPose();
        ci.cancel();
    }
}

package com.chukl.addon.mixin;

import com.chukl.addon.cs16.Cs16Hooks;
import com.chukl.addon.modules.PlayerModelCs16;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {
    /** CS16 Player Model: hides the vanilla body and submits the Counter-Strike model instead. */
    @Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V", at = @At("HEAD"))
    private void chukl$csPlayer(LivingEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
        if (state instanceof PlayerEntityRenderState ps) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && ps.id == mc.player.getId()) Cs16Hooks.ownRenderNanos = System.nanoTime();
        }
        PlayerModelCs16.onRenderEntity(state, matrices, queue);
    }
}

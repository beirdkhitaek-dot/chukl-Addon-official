package com.chukl.addon.mixin;

import com.chukl.addon.modules.Cs16Knife;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public class HeldItemHideMixin {
    /** CS16 Knife: do not draw the vanilla sword (and arm) in first person while the knife model replaces it. */
    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
    private void chukl$hideSword(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress,
                                 ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue,
                                 int light, CallbackInfo ci) {
        if (hand == Hand.MAIN_HAND && Cs16Knife.shouldHide(item)) ci.cancel();
    }
}

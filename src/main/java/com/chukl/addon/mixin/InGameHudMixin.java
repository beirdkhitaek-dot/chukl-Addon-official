package com.chukl.addon.mixin;

import com.chukl.addon.modules.Cs16Knife;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    /** CS16 Knife: draw the first-person knife before the HUD, so the hotbar, crosshair and chat are on top of it. */
    @Inject(method = "render", at = @At("HEAD"))
    private void chukl$knifeBehindHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        Cs16Knife.drawBehindHud(context);
    }
}

package com.chukl.addon.mixin;

import com.chukl.addon.modules.Cs16Knife;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ArmedEntityRenderState.class)
public class ArmedEntityStateMixin {
    /**
     * CS16 Knife: the knife is drawn in the world in third person and on other players, so the vanilla sword in that
     * hand is cleared. Only the main hand of a player holding a sword or axe is touched; every other item stays vanilla.
     */
    @Inject(method = "updateRenderState", at = @At("TAIL"))
    private static void chukl$hideSword(LivingEntity entity, ArmedEntityRenderState state, ItemModelManager itemModelManager, float tickProgress, CallbackInfo ci) {
        if (!Cs16Knife.shouldHideWorld(entity)) return;
        if (entity.getMainArm() == Arm.RIGHT) state.rightHandItemState.clear();
        else state.leftHandItemState.clear();
    }
}

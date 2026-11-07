package com.chukl.addon.modules;

import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Keeps a module's settings screen simple for customers. Only the settings named in {@code easy} are always visible.
 * Every other setting is hidden until "Show Advanced Settings" is on, and keeps its own visibility rules on top of that.
 * If anything goes wrong, nothing is hidden, so the module never loses a setting.
 */
final class SimpleMode {
    private SimpleMode() {}

    static void apply(Setting<Boolean> advanced, Set<String> easy, Settings settings) {
        try {
            Field f = Setting.class.getDeclaredField("visible");
            f.setAccessible(true);
            for (SettingGroup g : settings) {
                boolean anyEasy = false;
                for (Setting<?> s : g) {
                    if (easy.contains(s.name)) { anyEasy = true; continue; }
                    IVisible old = (IVisible) f.get(s);
                    f.set(s, (IVisible) () -> advanced.get() && (old == null || old.isVisible()));
                }
                if (!anyEasy) g.sectionExpanded = false;
            }
        } catch (Throwable ignored) {
            // leave every setting visible
        }
    }
}

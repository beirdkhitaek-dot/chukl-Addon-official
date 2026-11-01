package com.chukl.addon.modules;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.Setting;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Buttons shown at the bottom of a sound module: Refresh, Next sound, Test. */
final class SoundWidget {
    private SoundWidget() {}

    static WWidget make(GuiTheme theme, Setting<String> file, Supplier<Float> volume, Consumer<String> say) {
        WVerticalList list = theme.verticalList();
        WLabel label = list.add(theme.label("Sounds: press Refresh after importing a .wav")).expandX().widget();
        WHorizontalList row = list.add(theme.horizontalList()).expandX().widget();

        WButton refresh = row.add(theme.button("Refresh")).widget();
        refresh.action = () -> {
            List<String> names = ChuklSound.list();
            label.set("Found " + names.size() + ": " + String.join(", ", names));
            say.accept("Sounds folder: " + ChuklSound.folders().get(0).getAbsolutePath());
            say.accept("Found: " + String.join(", ", names));
        };

        WButton next = row.add(theme.button("Next sound")).widget();
        next.action = () -> {
            List<String> names = ChuklSound.list();
            if (names.isEmpty()) return;
            String cur = file.get().trim().toLowerCase();
            int idx = -1;
            for (int i = 0; i < names.size(); i++) {
                String n = names.get(i).replace("  (built in)", "").toLowerCase();
                if (n.equals(cur) || n.equals(cur + ".wav")) { idx = i; break; }
            }
            String pick = names.get((idx + 1) % names.size()).replace("  (built in)", "");
            file.set(pick);
            label.set("Selected: " + pick);
            say.accept("Sound set to " + pick);
        };

        WButton test = row.add(theme.button("Test")).widget();
        test.action = () -> {
            String err = ChuklSound.play(file.get(), volume.get());
            if (!err.isEmpty()) say.accept(err);
        };
        return list;
    }
}

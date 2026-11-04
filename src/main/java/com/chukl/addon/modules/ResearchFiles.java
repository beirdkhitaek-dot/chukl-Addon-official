package com.chukl.addon.modules;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Where the research modules write their data: meteor-client/chukl-research/ (one file per server). */
final class ResearchFiles {
    private ResearchFiles() {}

    static File folder() {
        File f = new File(MeteorClient.FOLDER, "chukl-research");
        f.mkdirs();
        return f;
    }

    /** A file-name-safe name for the server or world you are in. */
    static String serverName() {
        try {
            var entry = MinecraftClient.getInstance().getCurrentServerEntry();
            if (entry != null && entry.address != null && !entry.address.isBlank()) return safe(entry.address);
            return safe(Utils.getFileWorldName());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    static String safe(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        return r.isEmpty() ? "unknown" : r;
    }

    /** Appends one line (UTF-8) to a file in the research folder. Returns an error text, or "" when it worked. */
    static String appendLine(String fileName, String line) {
        return appendLines(fileName, java.util.List.of(line));
    }

    static String appendLines(String fileName, java.util.List<String> lines) {
        if (lines.isEmpty()) return "";
        try {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            Files.write(new File(folder(), fileName).toPath(), sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "";
        } catch (IOException e) {
            return "Could not write " + fileName + ": " + e.getMessage();
        }
    }

    static String json(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}

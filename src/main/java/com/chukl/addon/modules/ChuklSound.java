package com.chukl.addon.modules;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Plays .wav files for the Chukl Visual modules.
 * Sounds are found in several folders (the file name is matched without caring about upper/lower case and a missing .wav),
 * and playback tries Minecraft's own OpenAL first (works on Android), then Java Sound.
 */
public final class ChuklSound {
    private ChuklSound() {}

    public static final String BUNDLED_BELL = "bell1.wav";

    /** Every folder we look in, best first. */
    public static List<File> folders() {
        Set<File> set = new LinkedHashSet<>();
        File a = new File(MeteorClient.FOLDER, "chukl-visual");
        set.add(a);
        File run = MinecraftClient.getInstance().runDirectory;
        if (run != null) {
            set.add(new File(run, "chukl-visual"));
            set.add(new File(run, "meteor-client/chukl-visual"));
            set.add(new File(run, "config/chukl-visual"));
        }
        List<File> out = new ArrayList<>(set);
        a.mkdirs();
        return out;
    }

    /** Names of all .wav files in the folders (plus the bundled sound). */
    public static List<String> list() {
        Set<String> names = new LinkedHashSet<>();
        names.add(BUNDLED_BELL + "  (built in)");
        for (File f : folders()) {
            File[] files = f.listFiles();
            if (files == null) continue;
            for (File x : files) {
                if (x.isFile() && x.getName().toLowerCase(Locale.ROOT).endsWith(".wav")) names.add(x.getName());
            }
        }
        return new ArrayList<>(names);
    }

    /** Finds the file for a name: absolute path, exact name, name + .wav, or case-insensitive match. */
    public static File find(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        File abs = new File(n);
        if (abs.isAbsolute() && abs.isFile()) return abs;
        String lower = n.toLowerCase(Locale.ROOT);
        for (File dir : folders()) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File x : files) {
                if (!x.isFile()) continue;
                String xn = x.getName().toLowerCase(Locale.ROOT);
                if (xn.equals(lower) || xn.equals(lower + ".wav")) return x;
            }
        }
        return null;
    }

    /** Returns the raw bytes of the sound for a name; the bundled bell when the name is empty or "bell1". null if not found. */
    public static byte[] load(String name) {
        try {
            String n = name == null ? "" : name.trim();
            String l = n.toLowerCase(Locale.ROOT);
            File f = find(n);
            if (f != null) return Files.readAllBytes(f.toPath());
            if (l.isEmpty() || l.startsWith("bell1")) {
                try (InputStream in = ChuklSound.class.getResourceAsStream("/assets/chukl/sounds/bell1.wav")) {
                    if (in != null) return in.readAllBytes();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Describes why a name could not be played, for chat. */
    public static String diagnose(String name) {
        if (load(name) != null) return "ok";
        StringBuilder sb = new StringBuilder("File '" + name + "' not found. Looked in: ");
        for (File f : folders()) sb.append(f.getAbsolutePath()).append("  ");
        List<String> have = list();
        sb.append("| .wav files seen: ").append(have.size() <= 1 ? "none" : String.join(", ", have));
        return sb.toString();
    }

    /** Plays the sound. Returns an empty string on success, otherwise the reason. Safe to call from any thread. */
    public static String play(String name, float volume) {
        byte[] data = load(name);
        if (data == null) return diagnose(name);

        Wav wav = Wav.parse(data);
        String openAlError = "";
        if (wav != null) {
            try {
                playOpenAl(wav, volume);
                return "";
            } catch (Throwable t) {
                openAlError = "OpenAL: " + t;
            }
        } else {
            openAlError = "not a 8/16-bit PCM wav";
        }
        try {
            playJavaSound(data, volume);
            return "";
        } catch (Throwable t) {
            return "Could not play '" + name + "' (" + openAlError + "; Java Sound: " + t.getClass().getSimpleName() + "). Use a 16-bit PCM wav.";
        }
    }

    // ---------- OpenAL ----------

    private static void playOpenAl(Wav w, float volume) {
        int format;
        if (w.channels == 1) format = w.bits == 8 ? AL10.AL_FORMAT_MONO8 : AL10.AL_FORMAT_MONO16;
        else format = w.bits == 8 ? AL10.AL_FORMAT_STEREO8 : AL10.AL_FORMAT_STEREO16;

        ByteBuffer buf = BufferUtils.createByteBuffer(w.pcm.length);
        buf.put(w.pcm).flip();
        final int buffer = AL10.alGenBuffers();
        AL10.alBufferData(buffer, format, buf, w.rate);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) throw new IllegalStateException("alBufferData failed");
        final int source = AL10.alGenSources();
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE); // plays in your head, not at a position
        AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
        AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0f, Math.min(1f, volume)));
        AL10.alSourcePlay(source);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            AL10.alDeleteSources(source);
            AL10.alDeleteBuffers(buffer);
            throw new IllegalStateException("alSourcePlay failed");
        }

        long ms = (long) (w.pcm.length * 1000.0 / ((double) w.rate * w.channels * (w.bits / 8))) + 300;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(ms);
                AL10.alDeleteSources(source);
                AL10.alDeleteBuffers(buffer);
            } catch (Throwable ignored) {
            }
        }, "ChuklSound-cleanup");
        t.setDaemon(true);
        t.start();
    }

    // ---------- Java Sound (fallback) ----------

    private static void playJavaSound(byte[] data, float volume) throws Exception {
        javax.sound.sampled.AudioInputStream in = javax.sound.sampled.AudioSystem.getAudioInputStream(new ByteArrayInputStream(data));
        javax.sound.sampled.Clip c = javax.sound.sampled.AudioSystem.getClip();
        c.open(in);
        if (c.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
            javax.sound.sampled.FloatControl g = (javax.sound.sampled.FloatControl) c.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
            float db = (float) (20.0 * Math.log10(Math.max(0.0001, volume)));
            g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), db)));
        }
        c.addLineListener(ev -> {
            if (ev.getType() == javax.sound.sampled.LineEvent.Type.STOP) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        });
        c.start();
    }

    // ---------- minimal wav parser ----------

    private static final class Wav {
        int channels, rate, bits;
        byte[] pcm;

        static Wav parse(byte[] d) {
            try {
                ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
                if (d.length < 44 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) return null; // RIFF....WAVE
                int pos = 12;
                Wav w = new Wav();
                boolean haveFmt = false;
                while (pos + 8 <= d.length) {
                    int id = b.getInt(pos);
                    int size = b.getInt(pos + 4);
                    int body = pos + 8;
                    if (size < 0) return null;
                    if (id == 0x20746d66) { // "fmt "
                        int fmt = b.getShort(body) & 0xFFFF;
                        if (fmt != 1 && fmt != 0xFFFE) return null; // PCM only
                        w.channels = b.getShort(body + 2);
                        w.rate = b.getInt(body + 4);
                        w.bits = b.getShort(body + 14);
                        haveFmt = true;
                    } else if (id == 0x61746164) { // "data"
                        int len = Math.min(size, d.length - body);
                        if (!haveFmt || (w.channels != 1 && w.channels != 2) || (w.bits != 8 && w.bits != 16)) return null;
                        w.pcm = new byte[len];
                        System.arraycopy(d, body, w.pcm, 0, len);
                        return w;
                    }
                    pos = body + size + (size & 1);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}

/*
 * Copyright (C) 2026 john garner
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.pikatimer.pikadownloader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audible alert played for each new chip read.
 *
 * Each alert is a short (~250ms) three note chime, a quick major arpeggio:
 * the reader's tone, then a major third and a fifth above it, each with soft
 * overtones and a smooth fade in and out. Only one alert plays at a time: an
 * alert that arrives while another
 * is playing is dropped (the first one wins), so a burst of reads doesn't
 * pile up.
 *
 * The volume and mute apply to every reader and are saved in the prefs.
 * Each reader has its own tone (see LocalReader).
 *
 * NOTE: As an enum singleton, the INSTANCE is constructed before the static
 * fields are initialized, so anything the constructor needs must be an
 * instance field (or a compile time constant).
 */
public enum ReadAlert {
    INSTANCE;

    public static final int MIN_HZ = 250;
    public static final int MAX_HZ = 1500;
    public static final int DEFAULT_HZ = 600;
    public static final int DEFAULT_VOLUME = 50;

    private static final Logger logger = LoggerFactory.getLogger(ReadAlert.class);

    // 44.1kHz, 16 bit signed, mono, little endian
    private static final int SAMPLE_RATE = 44100;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    // The chime: a major arpeggio of the reader's tone, a major third (5:4)
    // and a fifth (3:2) above it. Each note starts NOTE_STEP_MS after the one
    // before and lasts NOTE_MS, so they overlap and the last one ends at TONE_MS.
    private static final int TONE_MS = 250;
    private static final int NOTE_STEP_MS = 60;
    private static final int NOTE_MS = 130;
    private static final double[] NOTE_RATIOS = {1.0, 1.25, 1.5};

    // Overtones (relative to the fundamental) soften the pure sine
    private static final double[] PARTIALS = {1.0, 0.3, 0.1};

    // Envelope: a quick fade in, a natural decay, and a fade out that ends
    // exactly at zero so there are no clicks
    private static final double ATTACK_MS = 5;
    private static final double DECAY_MS = 70;
    private static final double RELEASE_MS = 20;

    // Peak level at full volume (fraction of full scale)
    private static final double PEAK_LEVEL = 0.8;

    private volatile int volume = clampVolume(
            PikaDownloaderPrefs.INSTANCE.getPreferences().getInt("AlertVolume", DEFAULT_VOLUME));
    private volatile boolean muted =
            PikaDownloaderPrefs.INSTANCE.getPreferences().getBoolean("AlertMuted", false);

    // True from the time an alert is accepted until it has finished playing
    private final AtomicBoolean playing = new AtomicBoolean(false);

    private final ExecutorService player = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("read-alert");
        return thread;
    });

    // Only used on the player thread
    private SourceDataLine line = null;
    private boolean lineWarned = false;

    public int getVolume() {
        return volume;
    }

    /**
     * @param v the volume, 0 (silent) to 100. Saved in the prefs.
     */
    public void setVolume(int v) {
        int clamped = clampVolume(v);
        if (clamped == volume) return;
        volume = clamped;
        PikaDownloaderPrefs.INSTANCE.getPreferences().putInt("AlertVolume", clamped);
    }

    public boolean isMuted() {
        return muted;
    }

    /**
     * @param m true to silence every alert. Saved in the prefs.
     */
    public void setMuted(boolean m) {
        muted = m;
        PikaDownloaderPrefs.INSTANCE.getPreferences().putBoolean("AlertMuted", m);
    }

    /**
     * @return the tone, limited to MIN_HZ - MAX_HZ
     */
    public static int clampHz(int hz) {
        return Math.max(MIN_HZ, Math.min(MAX_HZ, hz));
    }

    private static int clampVolume(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /**
     * Play the alert chime, unless muted or another alert is still playing
     * (then this one is dropped). Safe to call from any thread, and returns
     * right away.
     *
     * @param hz the tone of the first note (MIN_HZ - MAX_HZ)
     */
    public void play(int hz) {
        int vol = volume;
        if (muted || vol <= 0) return;

        // The first alert wins, anything that would overlap it is dropped
        if (!playing.compareAndSet(false, true)) {
            logger.trace("Read alert already playing, skipping {} Hz", hz);
            return;
        }

        try {
            player.execute(() -> playNow(clampHz(hz), vol));
        } catch (RejectedExecutionException ex) {
            playing.set(false); // shutting down
        }
    }

    // Runs on the player thread
    private void playNow(int hz, int vol) {
        try {
            SourceDataLine l = openLine();
            if (l == null) return;

            // A perceptual volume curve: 50% is about half as loud
            double gain = (vol / 100.0) * (vol / 100.0);
            byte[] pcm = render(hz, gain);

            logger.trace("Playing a {} Hz read alert at {}%", hz, vol);
            l.start();
            l.write(pcm, 0, pcm.length);
            l.drain(); // wait for it to finish so alerts never overlap
            l.stop();
        } catch (RuntimeException ex) {
            logger.debug("Unable to play the read alert: {}", ex.toString());
            closeLine(); // reopen it on the next alert
        } finally {
            playing.set(false);
        }
    }

    // Opens the audio line the first time it is needed (or after an error).
    // Returns null if there is no usable audio device. Runs on the player thread.
    private SourceDataLine openLine() {
        if (line != null && line.isOpen()) return line;
        try {
            SourceDataLine l = AudioSystem.getSourceDataLine(FORMAT);
            l.open(FORMAT);
            line = l;
            logger.debug("Opened the read alert audio line: {}", l.getFormat());
            return l;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException ex) {
            // e.g. no audio device. Try again on the next alert, but only warn once.
            if (!lineWarned) {
                logger.warn("Unable to open an audio line for the read alerts: {}", ex.toString());
                lineWarned = true;
            } else {
                logger.debug("Unable to open an audio line for the read alerts: {}", ex.toString());
            }
            return null;
        }
    }

    // Runs on the player thread
    private void closeLine() {
        if (line != null) {
            try {
                line.close();
            } catch (RuntimeException ex) {
                logger.debug("Error closing the read alert audio line: {}", ex.toString());
            }
            line = null;
        }
    }

    /**
     * Close the audio line (after any alert that is playing). Called when the
     * app exits.
     */
    public void shutdown() {
        try {
            player.execute(this::closeLine);
        } catch (RejectedExecutionException ex) {
            return; // already shut down
        }
        player.shutdown();
        try {
            player.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Render the chime as 16 bit signed little endian mono PCM at 44.1kHz.
     *
     * @param hz the tone of the first note
     * @param gain 0 (silent) to 1 (full volume)
     */
    static byte[] render(int hz, double gain) {
        double[] mix = new double[samples(TONE_MS)];
        for (int k = 0; k < NOTE_RATIOS.length; k++) {
            int startMs = k * NOTE_STEP_MS;
            addNote(mix, hz * NOTE_RATIOS[k], startMs, startMs + NOTE_MS);
        }

        double peak = 0;
        for (double v : mix) peak = Math.max(peak, Math.abs(v));
        double scale = peak > 0 ? PEAK_LEVEL * gain * Short.MAX_VALUE / peak : 0;

        byte[] pcm = new byte[mix.length * 2];
        for (int i = 0; i < mix.length; i++) {
            int s = (int) Math.round(mix[i] * scale);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    // Add one note (with its overtones and envelope) to the mix
    private static void addNote(double[] mix, double hz, int startMs, int endMs) {
        int start = samples(startMs);
        int length = Math.min(mix.length, samples(endMs)) - start;
        double attack = ATTACK_MS * SAMPLE_RATE / 1000.0;
        double release = RELEASE_MS * SAMPLE_RATE / 1000.0;
        double decay = DECAY_MS * SAMPLE_RATE / 1000.0;

        for (int n = 0; n < length; n++) {
            // Raised cosine fade in and fade out around an exponential decay
            double envelope = Math.exp(-n / decay);
            if (n < attack) envelope *= 0.5 - 0.5 * Math.cos(Math.PI * n / attack);
            int fromEnd = length - 1 - n;
            if (fromEnd < release) envelope *= 0.5 - 0.5 * Math.cos(Math.PI * fromEnd / release);

            double phase = 2 * Math.PI * hz * n / SAMPLE_RATE;
            double v = 0;
            for (int p = 0; p < PARTIALS.length; p++) {
                v += PARTIALS[p] * Math.sin((p + 1) * phase);
            }
            mix[start + n] += envelope * v;
        }
    }

    private static int samples(int ms) {
        return SAMPLE_RATE * ms / 1000;
    }
}

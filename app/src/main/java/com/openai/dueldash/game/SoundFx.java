package com.openai.dueldash.game;

import android.media.AudioManager;
import android.media.ToneGenerator;

public final class SoundFx {
    private ToneGenerator tone;

    public SoundFx() {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 72);
        } catch (Throwable ignored) {
            tone = null;
        }
    }

    public void click() { play(ToneGenerator.TONE_PROP_BEEP, 45); }
    public void hit() { play(ToneGenerator.TONE_PROP_NACK, 70); }
    public void score() { play(ToneGenerator.TONE_PROP_ACK, 90); }
    public void go() { play(ToneGenerator.TONE_PROP_ACK, 120); }
    public void win() { play(ToneGenerator.TONE_PROP_ACK, 240); }

    private void play(int type, int durationMs) {
        try {
            if (tone != null) tone.startTone(type, durationMs);
        } catch (Throwable ignored) {}
    }

    public void release() {
        try {
            if (tone != null) tone.release();
        } catch (Throwable ignored) {}
        tone = null;
    }
}

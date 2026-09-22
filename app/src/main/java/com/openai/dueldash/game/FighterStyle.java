package com.openai.dueldash.game;

import android.graphics.Color;

public final class FighterStyle {
    public final String name;
    public final String emoji;
    public final int body;
    public final int accent;
    public final int hair;
    public final int skin;

    private FighterStyle(String name, String emoji, int body, int accent, int hair, int skin) {
        this.name = name;
        this.emoji = emoji;
        this.body = body;
        this.accent = accent;
        this.hair = hair;
        this.skin = skin;
    }

    public static final FighterStyle[] ALL = new FighterStyle[]{
            new FighterStyle("Рэй", "⚡", Color.rgb(57, 155, 255), Color.rgb(255, 211, 61), Color.rgb(34, 38, 54), Color.rgb(246, 198, 157)),
            new FighterStyle("Фокс", "🔥", Color.rgb(255, 92, 92), Color.rgb(255, 170, 38), Color.rgb(126, 55, 35), Color.rgb(232, 174, 128)),
            new FighterStyle("Нова", "🌟", Color.rgb(155, 93, 229), Color.rgb(82, 226, 190), Color.rgb(62, 31, 92), Color.rgb(246, 202, 164)),
            new FighterStyle("Турбо", "🚀", Color.rgb(52, 199, 89), Color.rgb(74, 144, 226), Color.rgb(25, 62, 52), Color.rgb(205, 148, 101)),
            new FighterStyle("Кики", "💥", Color.rgb(255, 94, 163), Color.rgb(255, 214, 10), Color.rgb(74, 38, 60), Color.rgb(247, 185, 151)),
            new FighterStyle("Фрост", "❄️", Color.rgb(55, 199, 225), Color.rgb(194, 235, 255), Color.rgb(220, 241, 250), Color.rgb(226, 178, 139))
    };

    public static FighterStyle get(int index) {
        int safe = Math.floorMod(index, ALL.length);
        return ALL[safe];
    }

    public static int count() {
        return ALL.length;
    }
}

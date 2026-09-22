package com.openai.dueldash.game;

public enum GameMode {
    FIGHT("🥊", "ДРАКА", "Подойди к сопернику и бей. Побеждает тот, у кого останется здоровье."),
    RACE("🏁", "ГОНКА", "Чередуй ЛЕВО и ПРАВО, чтобы быстрее разогнаться до финиша."),
    TAP_DUEL("⚡", "ТАП-БИТВА", "За 10 секунд нажми большую кнопку больше раз, чем соперник."),
    PONG("🏓", "ПОНГ", "Двигай ракетку вверх и вниз. Первый до 5 очков выигрывает."),
    PENALTY("⚽", "ПЕНАЛЬТИ", "Выбирай левый, центр или правый угол. Игроки по очереди бьют и защищают ворота."),
    REACTION("🚦", "РЕАКЦИЯ", "Жди зелёного сигнала и нажми первым. Фальстарт отдаёт очко сопернику."),
    TUG("🪢", "ПЕРЕТЯГИВАНИЕ", "Быстро нажимай ТЯНИ, чтобы перетащить канат на свою сторону.");

    public final String icon;
    public final String title;
    public final String description;

    GameMode(String icon, String title, String description) {
        this.icon = icon;
        this.title = title;
        this.description = description;
    }
}

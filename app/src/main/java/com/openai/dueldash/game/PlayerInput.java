package com.openai.dueldash.game;

public final class PlayerInput {
    public boolean left;
    public boolean right;
    public boolean attack;

    public void set(boolean left, boolean right, boolean attack) {
        this.left = left;
        this.right = right;
        this.attack = attack;
    }
}

package com.openai.dueldash.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.openai.dueldash.net.PeerConnection;

import java.util.Locale;
import java.util.Random;

public final class DuelGameView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thinStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GameMode mode;
    private final boolean isHost;
    private final boolean solo;
    private final int localPlayer;
    private final PeerConnection connection;
    private final PlayerInput localInput = new PlayerInput();
    private final PlayerInput remoteInput = new PlayerInput();
    private final SoundFx sound = new SoundFx();
    private final Random random = new Random();

    private int p1Fighter;
    private int p2Fighter;
    private boolean disconnected = false;
    private long lastFrameNs = System.nanoTime();
    private long lastSnapshotNs = 0L;
    private float animTime = 0f;
    private float countdown = 3f;
    private int winner = 0; // 0 none, 1/2 player, 3 draw
    private int lastCountdownTick = 99;
    private int lastObservedWinner = 0;

    // Fight.
    private float p1x = 0.28f;
    private float p2x = 0.72f;
    private int p1hp = 100;
    private int p2hp = 100;
    private float p1Cooldown = 0f;
    private float p2Cooldown = 0f;
    private float p1HitFlash = 0f;
    private float p2HitFlash = 0f;

    // Race.
    private float p1Race = 0f;
    private float p2Race = 0f;
    private float p1Speed = 0f;
    private float p2Speed = 0f;
    private char p1LastTap = '-';
    private char p2LastTap = '-';

    // Tap battle.
    private float tapTime = 10f;
    private int p1Taps = 0;
    private int p2Taps = 0;

    // Pong.
    private float p1Paddle = 0.5f;
    private float p2Paddle = 0.5f;
    private float ballX = 0.5f;
    private float ballY = 0.5f;
    private float ballVx = 0.42f;
    private float ballVy = 0.22f;
    private int p1Pong = 0;
    private int p2Pong = 0;
    private float pongServeDelay = 0.8f;

    // Penalty.
    private int penaltyTurn = 0;
    private int p1Goals = 0;
    private int p2Goals = 0;
    private int p1Choice = -1;
    private int p2Choice = -1;
    private int penaltyResult = 0; // 0 waiting, 1 goal, 2 save
    private float penaltyDelay = 0f;

    // Reaction.
    private int reactionState = 0; // 0 wait, 1 go, 2 result
    private float reactionTimer = 2.2f;
    private int p1Rounds = 0;
    private int p2Rounds = 0;
    private int reactionRoundWinner = 0;

    // Tug.
    private float tugPos = 0f;

    // Solo AI.
    private float aiTimer = 0f;
    private char aiRaceSide = 'L';
    private float aiReactionDelay = -1f;

    public DuelGameView(Context context, GameMode mode, boolean isHost, PeerConnection connection,
                        boolean solo, int localFighter, int remoteFighter) {
        super(context);
        this.mode = mode;
        this.isHost = isHost;
        this.connection = connection;
        this.solo = solo;
        this.localPlayer = isHost ? 1 : 2;
        if (isHost) {
            p1Fighter = localFighter;
            p2Fighter = remoteFighter;
        } else {
            p1Fighter = remoteFighter;
            p2Fighter = localFighter;
        }

        setFocusable(true);
        setKeepScreenOn(true);
        setBackgroundColor(Color.rgb(9, 14, 27));

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(3));
        stroke.setColor(Color.WHITE);
        thinStroke.setStyle(Paint.Style.STROKE);
        thinStroke.setStrokeWidth(dp(1.5f));
        thinStroke.setColor(Color.argb(130, 255, 255, 255));

        if (connection != null) {
            connection.setListener(new PeerConnection.Listener() {
                @Override
                public void onMessage(String line) {
                    post(() -> handleNetworkMessage(line));
                }

                @Override
                public void onClosed(Throwable error) {
                    post(() -> {
                        disconnected = true;
                        invalidate();
                    });
                }
            });
            connection.start();
        }
        resetAi();
    }

    public void shutdown() {
        sound.release();
        if (connection != null) connection.close();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime();
        float dt = Math.min(0.05f, Math.max(0f, (now - lastFrameNs) / 1_000_000_000f));
        lastFrameNs = now;
        animTime += dt;

        updateCountdownSound();

        if (isHost && !disconnected) {
            if (solo) updateAi(dt);
            updateGame(dt);
            if (connection != null && now - lastSnapshotNs >= 50_000_000L) {
                connection.send(snapshot());
                lastSnapshotNs = now;
            }
        }

        switch (mode) {
            case FIGHT: drawFight(canvas); break;
            case RACE: drawRace(canvas); break;
            case TAP_DUEL: drawTapDuel(canvas); break;
            case PONG: drawPong(canvas); break;
            case PENALTY: drawPenalty(canvas); break;
            case REACTION: drawReaction(canvas); break;
            case TUG: drawTug(canvas); break;
        }

        drawStatus(canvas);
        postInvalidateOnAnimation();
    }

    private void updateCountdownSound() {
        if (winner != 0 || countdown < 0f) return;
        int tick = countdown > 0f ? (int) Math.ceil(countdown) : 0;
        if (tick != lastCountdownTick) {
            if (tick > 0 && tick <= 3) sound.click();
            if (tick == 0) sound.go();
            lastCountdownTick = tick;
        }
    }

    private void updateGame(float dt) {
        if (winner != 0) return;
        if (countdown > 0f) {
            countdown = Math.max(0f, countdown - dt);
            return;
        }
        switch (mode) {
            case FIGHT: updateFight(dt); break;
            case RACE: updateRace(dt); break;
            case TAP_DUEL: updateTapDuel(dt); break;
            case PONG: updatePong(dt); break;
            case PENALTY: updatePenalty(dt); break;
            case REACTION: updateReaction(dt); break;
            case TUG: updateTug(dt); break;
        }
    }

    private void updateFight(float dt) {
        p1Cooldown = Math.max(0f, p1Cooldown - dt);
        p2Cooldown = Math.max(0f, p2Cooldown - dt);
        p1HitFlash = Math.max(0f, p1HitFlash - dt);
        p2HitFlash = Math.max(0f, p2HitFlash - dt);

        float speed = 0.48f;
        if (localInput.left) p1x -= speed * dt;
        if (localInput.right) p1x += speed * dt;
        if (remoteInput.left) p2x -= speed * dt;
        if (remoteInput.right) p2x += speed * dt;
        p1x = clamp(p1x, 0.08f, 0.92f);
        p2x = clamp(p2x, 0.08f, 0.92f);

        if (Math.abs(p1x - p2x) < 0.095f) {
            float mid = (p1x + p2x) * 0.5f;
            if (p1x < p2x) { p1x = mid - 0.0475f; p2x = mid + 0.0475f; }
            else { p2x = mid - 0.0475f; p1x = mid + 0.0475f; }
        }

        if (localInput.attack && p1Cooldown <= 0f) {
            p1Cooldown = 0.46f;
            sound.click();
            if (Math.abs(p1x - p2x) < 0.19f) {
                p2hp = Math.max(0, p2hp - 10);
                p2HitFlash = 0.22f;
                p2x = clamp(p2x + (p2x > p1x ? 0.05f : -0.05f), 0.08f, 0.92f);
                sound.hit();
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        if (remoteInput.attack && p2Cooldown <= 0f) {
            p2Cooldown = 0.46f;
            if (Math.abs(p1x - p2x) < 0.19f) {
                p1hp = Math.max(0, p1hp - 10);
                p1HitFlash = 0.22f;
                p1x = clamp(p1x + (p1x > p2x ? 0.05f : -0.05f), 0.08f, 0.92f);
                sound.hit();
            }
        }
        if (p1hp <= 0) setWinner(2);
        if (p2hp <= 0) setWinner(1);
    }

    private void updateRace(float dt) {
        p1Race += p1Speed * dt;
        p2Race += p2Speed * dt;
        p1Speed = Math.max(0f, p1Speed - 0.13f * dt);
        p2Speed = Math.max(0f, p2Speed - 0.13f * dt);
        if (p1Race >= 1f || p2Race >= 1f) {
            p1Race = Math.min(1f, p1Race);
            p2Race = Math.min(1f, p2Race);
            if (p1Race >= 1f && p2Race >= 1f) setWinner(p1Race > p2Race ? 1 : p2Race > p1Race ? 2 : 3);
            else setWinner(p1Race >= 1f ? 1 : 2);
        }
    }

    private void raceTap(int player, char side) {
        if (!isHost || mode != GameMode.RACE || winner != 0 || countdown > 0f) return;
        if (player == 1) {
            boolean alternating = p1LastTap != '-' && p1LastTap != side;
            p1Speed = Math.min(0.52f, p1Speed + (alternating ? 0.098f : 0.026f));
            p1LastTap = side;
        } else {
            boolean alternating = p2LastTap != '-' && p2LastTap != side;
            p2Speed = Math.min(0.52f, p2Speed + (alternating ? 0.098f : 0.026f));
            p2LastTap = side;
        }
        sound.click();
    }

    private void updateTapDuel(float dt) {
        tapTime = Math.max(0f, tapTime - dt);
        if (tapTime <= 0f) {
            setWinner(p1Taps > p2Taps ? 1 : p2Taps > p1Taps ? 2 : 3);
        }
    }

    private void tapBattle(int player) {
        if (!isHost || mode != GameMode.TAP_DUEL || winner != 0 || countdown > 0f || tapTime <= 0f) return;
        if (player == 1) p1Taps++; else p2Taps++;
        if (player == localPlayer || solo) sound.click();
    }

    private void updatePong(float dt) {
        float paddleSpeed = 0.85f;
        if (localInput.left) p1Paddle -= paddleSpeed * dt;
        if (localInput.right) p1Paddle += paddleSpeed * dt;
        if (remoteInput.left) p2Paddle -= paddleSpeed * dt;
        if (remoteInput.right) p2Paddle += paddleSpeed * dt;
        p1Paddle = clamp(p1Paddle, 0.23f, 0.77f);
        p2Paddle = clamp(p2Paddle, 0.23f, 0.77f);

        if (pongServeDelay > 0f) {
            pongServeDelay = Math.max(0f, pongServeDelay - dt);
            return;
        }
        ballX += ballVx * dt;
        ballY += ballVy * dt;
        if (ballY < 0.17f) { ballY = 0.17f; ballVy = Math.abs(ballVy); sound.click(); }
        if (ballY > 0.66f) { ballY = 0.66f; ballVy = -Math.abs(ballVy); sound.click(); }

        float paddleHalf = 0.105f;
        if (ballVx < 0f && ballX < 0.12f && ballX > 0.075f && Math.abs(ballY - p1Paddle) < paddleHalf) {
            ballX = 0.12f;
            ballVx = Math.abs(ballVx) * 1.035f;
            ballVy += (ballY - p1Paddle) * 1.5f;
            sound.click();
        }
        if (ballVx > 0f && ballX > 0.88f && ballX < 0.925f && Math.abs(ballY - p2Paddle) < paddleHalf) {
            ballX = 0.88f;
            ballVx = -Math.abs(ballVx) * 1.035f;
            ballVy += (ballY - p2Paddle) * 1.5f;
            sound.click();
        }
        ballVy = clamp(ballVy, -0.56f, 0.56f);
        ballVx = clamp(ballVx, -0.68f, 0.68f);

        if (ballX < 0f) { p2Pong++; sound.score(); resetPongBall(-1); }
        else if (ballX > 1f) { p1Pong++; sound.score(); resetPongBall(1); }
        if (p1Pong >= 5 || p2Pong >= 5) setWinner(p1Pong > p2Pong ? 1 : 2);
    }

    private void resetPongBall(int scorer) {
        ballX = 0.5f;
        ballY = 0.42f + (random.nextFloat() - 0.5f) * 0.18f;
        ballVx = scorer == 1 ? -0.42f : 0.42f;
        ballVy = (random.nextFloat() - 0.5f) * 0.38f;
        pongServeDelay = 0.75f;
    }

    private void updatePenalty(float dt) {
        if (penaltyDelay > 0f) {
            penaltyDelay = Math.max(0f, penaltyDelay - dt);
            if (penaltyDelay <= 0f) {
                penaltyTurn++;
                p1Choice = -1;
                p2Choice = -1;
                penaltyResult = 0;
                if (penaltyTurn >= 10) {
                    setWinner(p1Goals > p2Goals ? 1 : p2Goals > p1Goals ? 2 : 3);
                }
                resetAi();
            }
            return;
        }
        if (p1Choice >= 0 && p2Choice >= 0) {
            boolean goal = p1Choice != p2Choice;
            penaltyResult = goal ? 1 : 2;
            if (goal) {
                if ((penaltyTurn & 1) == 0) p1Goals++; else p2Goals++;
                sound.score();
            } else {
                sound.hit();
            }
            penaltyDelay = 1.35f;
        }
    }

    private void penaltyChoose(int player, int choice) {
        if (!isHost || mode != GameMode.PENALTY || winner != 0 || countdown > 0f || penaltyDelay > 0f) return;
        if (choice < 0 || choice > 2) return;
        if (player == 1 && p1Choice < 0) p1Choice = choice;
        if (player == 2 && p2Choice < 0) p2Choice = choice;
        sound.click();
    }

    private void updateReaction(float dt) {
        if (reactionState == 0) {
            reactionTimer -= dt;
            if (reactionTimer <= 0f) {
                reactionState = 1;
                reactionTimer = 0f;
                aiReactionDelay = solo ? 0.22f + random.nextFloat() * 0.34f : -1f;
                sound.go();
            }
        } else if (reactionState == 2) {
            reactionTimer -= dt;
            if (reactionTimer <= 0f) {
                if (p1Rounds >= 3 || p2Rounds >= 3) {
                    setWinner(p1Rounds >= 3 ? 1 : 2);
                } else {
                    reactionState = 0;
                    reactionRoundWinner = 0;
                    reactionTimer = 1.3f + random.nextFloat() * 2.2f;
                    aiReactionDelay = -1f;
                }
            }
        }
    }

    private void reactionTap(int player) {
        if (!isHost || mode != GameMode.REACTION || winner != 0 || countdown > 0f || reactionState == 2) return;
        int roundWinner;
        if (reactionState == 0) roundWinner = player == 1 ? 2 : 1; // false start
        else roundWinner = player;
        if (roundWinner == 1) p1Rounds++; else p2Rounds++;
        reactionRoundWinner = roundWinner;
        reactionState = 2;
        reactionTimer = 1.25f;
        sound.score();
    }

    private void updateTug(float dt) {
        // Tiny elastic pull toward center keeps the duel active.
        if (tugPos > 0f) tugPos = Math.max(0f, tugPos - 0.018f * dt);
        if (tugPos < 0f) tugPos = Math.min(0f, tugPos + 0.018f * dt);
        if (tugPos <= -1f) setWinner(1);
        if (tugPos >= 1f) setWinner(2);
    }

    private void tugTap(int player) {
        if (!isHost || mode != GameMode.TUG || winner != 0 || countdown > 0f) return;
        tugPos += player == 1 ? -0.072f : 0.072f;
        tugPos = clamp(tugPos, -1.05f, 1.05f);
        sound.click();
    }

    private void setWinner(int value) {
        if (winner != 0) return;
        winner = value;
        sound.win();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void updateAi(float dt) {
        if (winner != 0 || countdown > 0f) return;
        switch (mode) {
            case FIGHT:
                aiTimer -= dt;
                float distance = p2x - p1x;
                remoteInput.left = distance > 0.145f;
                remoteInput.right = distance < -0.145f;
                remoteInput.attack = false;
                if (Math.abs(distance) < 0.20f && aiTimer <= 0f) {
                    remoteInput.attack = true;
                    aiTimer = 0.34f + random.nextFloat() * 0.42f;
                }
                break;
            case RACE:
                aiTimer -= dt;
                if (aiTimer <= 0f) {
                    aiRaceSide = aiRaceSide == 'L' ? 'R' : 'L';
                    raceTap(2, aiRaceSide);
                    aiTimer = 0.105f + random.nextFloat() * 0.095f;
                }
                break;
            case TAP_DUEL:
                aiTimer -= dt;
                if (aiTimer <= 0f) {
                    tapBattle(2);
                    aiTimer = 0.105f + random.nextFloat() * 0.12f;
                }
                break;
            case PONG:
                remoteInput.left = ballY < p2Paddle - 0.025f;
                remoteInput.right = ballY > p2Paddle + 0.025f;
                break;
            case PENALTY:
                if (p2Choice < 0) {
                    aiTimer -= dt;
                    if (aiTimer <= 0f) {
                        penaltyChoose(2, random.nextInt(3));
                        aiTimer = 0.75f + random.nextFloat() * 0.5f;
                    }
                }
                break;
            case REACTION:
                if (reactionState == 1 && aiReactionDelay >= 0f) {
                    aiReactionDelay -= dt;
                    if (aiReactionDelay <= 0f) {
                        aiReactionDelay = -1f;
                        reactionTap(2);
                    }
                }
                break;
            case TUG:
                aiTimer -= dt;
                if (aiTimer <= 0f) {
                    tugTap(2);
                    aiTimer = 0.125f + random.nextFloat() * 0.115f;
                }
                break;
        }
    }

    private void resetAi() {
        aiTimer = 0.4f + random.nextFloat() * 0.35f;
        aiReactionDelay = -1f;
        remoteInput.set(false, false, false);
    }

    private String snapshot() {
        switch (mode) {
            case FIGHT:
                return String.format(Locale.US, "S|F|%.3f|%.5f|%.5f|%d|%d|%d|%.3f|%.3f|%d|%d",
                        countdown, p1x, p2x, p1hp, p2hp, winner, p1Cooldown, p2Cooldown, p1Fighter, p2Fighter);
            case RACE:
                return String.format(Locale.US, "S|R|%.3f|%.5f|%.5f|%.5f|%.5f|%d|%d|%d",
                        countdown, p1Race, p2Race, p1Speed, p2Speed, winner, p1Fighter, p2Fighter);
            case TAP_DUEL:
                return String.format(Locale.US, "S|T|%.3f|%.3f|%d|%d|%d|%d|%d",
                        countdown, tapTime, p1Taps, p2Taps, winner, p1Fighter, p2Fighter);
            case PONG:
                return String.format(Locale.US, "S|P|%.3f|%.4f|%.4f|%.4f|%.4f|%.4f|%.4f|%d|%d|%d|%d|%d",
                        countdown, p1Paddle, p2Paddle, ballX, ballY, ballVx, ballVy, p1Pong, p2Pong, winner, p1Fighter, p2Fighter);
            case PENALTY:
                return String.format(Locale.US, "S|K|%.3f|%d|%d|%d|%d|%d|%d|%.3f|%d|%d|%d",
                        countdown, penaltyTurn, p1Goals, p2Goals, p1Choice, p2Choice, penaltyResult, penaltyDelay, winner, p1Fighter, p2Fighter);
            case REACTION:
                return String.format(Locale.US, "S|X|%.3f|%d|%.3f|%d|%d|%d|%d|%d|%d",
                        countdown, reactionState, reactionTimer, p1Rounds, p2Rounds, reactionRoundWinner, winner, p1Fighter, p2Fighter);
            case TUG:
                return String.format(Locale.US, "S|G|%.3f|%.4f|%d|%d|%d",
                        countdown, tugPos, winner, p1Fighter, p2Fighter);
            default:
                return "S|?";
        }
    }

    private void handleNetworkMessage(String line) {
        try {
            String[] p = line.split("\\|");
            if (isHost) {
                if (p.length >= 2 && "CHAR".equals(p[0])) {
                    p2Fighter = Integer.parseInt(p[1]);
                } else if (p.length >= 4 && "I".equals(p[0])) {
                    remoteInput.set("1".equals(p[1]), "1".equals(p[2]), "1".equals(p[3]));
                } else if (p.length >= 2 && "T".equals(p[0]) && p[1].length() > 0) {
                    raceTap(2, p[1].charAt(0));
                } else if ("TAP".equals(line)) {
                    tapBattle(2);
                } else if (p.length >= 2 && "CHOICE".equals(p[0])) {
                    penaltyChoose(2, Integer.parseInt(p[1]));
                } else if ("REACT".equals(line)) {
                    reactionTap(2);
                } else if ("TUG".equals(line)) {
                    tugTap(2);
                } else if ("RESTART".equals(line)) {
                    resetGame();
                }
                return;
            }
            if (p.length >= 2 && "S".equals(p[0])) applySnapshot(p);
        } catch (Exception ignored) {}
    }

    private void applySnapshot(String[] p) {
        int oldWinner = winner;
        int oldHp1 = p1hp, oldHp2 = p2hp;
        int oldScore = scorePulseValue();

        if ("F".equals(p[1]) && p.length >= 12) {
            countdown = f(p[2]); p1x = f(p[3]); p2x = f(p[4]);
            p1hp = i(p[5]); p2hp = i(p[6]); winner = i(p[7]);
            p1Cooldown = f(p[8]); p2Cooldown = f(p[9]); p1Fighter = i(p[10]); p2Fighter = i(p[11]);
        } else if ("R".equals(p[1]) && p.length >= 10) {
            countdown = f(p[2]); p1Race = f(p[3]); p2Race = f(p[4]); p1Speed = f(p[5]); p2Speed = f(p[6]);
            winner = i(p[7]); p1Fighter = i(p[8]); p2Fighter = i(p[9]);
        } else if ("T".equals(p[1]) && p.length >= 9) {
            countdown = f(p[2]); tapTime = f(p[3]); p1Taps = i(p[4]); p2Taps = i(p[5]); winner = i(p[6]);
            p1Fighter = i(p[7]); p2Fighter = i(p[8]);
        } else if ("P".equals(p[1]) && p.length >= 14) {
            countdown = f(p[2]); p1Paddle = f(p[3]); p2Paddle = f(p[4]); ballX = f(p[5]); ballY = f(p[6]);
            ballVx = f(p[7]); ballVy = f(p[8]); p1Pong = i(p[9]); p2Pong = i(p[10]); winner = i(p[11]);
            p1Fighter = i(p[12]); p2Fighter = i(p[13]);
        } else if ("K".equals(p[1]) && p.length >= 13) {
            countdown = f(p[2]); penaltyTurn = i(p[3]); p1Goals = i(p[4]); p2Goals = i(p[5]); p1Choice = i(p[6]); p2Choice = i(p[7]);
            penaltyResult = i(p[8]); penaltyDelay = f(p[9]); winner = i(p[10]); p1Fighter = i(p[11]); p2Fighter = i(p[12]);
        } else if ("X".equals(p[1]) && p.length >= 11) {
            countdown = f(p[2]); reactionState = i(p[3]); reactionTimer = f(p[4]); p1Rounds = i(p[5]); p2Rounds = i(p[6]);
            reactionRoundWinner = i(p[7]); winner = i(p[8]); p1Fighter = i(p[9]); p2Fighter = i(p[10]);
        } else if ("G".equals(p[1]) && p.length >= 7) {
            countdown = f(p[2]); tugPos = f(p[3]); winner = i(p[4]); p1Fighter = i(p[5]); p2Fighter = i(p[6]);
        }

        if (p1hp < oldHp1 || p2hp < oldHp2) sound.hit();
        if (scorePulseValue() > oldScore && mode != GameMode.FIGHT) sound.score();
        if (oldWinner == 0 && winner != 0) sound.win();
    }

    private int scorePulseValue() {
        switch (mode) {
            case TAP_DUEL: return p1Taps + p2Taps;
            case PONG: return (p1Pong + p2Pong) * 20;
            case PENALTY: return (p1Goals + p2Goals) * 20;
            case REACTION: return (p1Rounds + p2Rounds) * 20;
            default: return 0;
        }
    }

    private float f(String s) { return Float.parseFloat(s); }
    private int i(String s) { return Integer.parseInt(s); }

    private void resetGame() {
        countdown = 3f;
        winner = 0;
        lastObservedWinner = 0;
        lastCountdownTick = 99;
        p1x = 0.28f; p2x = 0.72f; p1hp = 100; p2hp = 100; p1Cooldown = p2Cooldown = 0f; p1HitFlash = p2HitFlash = 0f;
        p1Race = p2Race = p1Speed = p2Speed = 0f; p1LastTap = p2LastTap = '-';
        tapTime = 10f; p1Taps = p2Taps = 0;
        p1Paddle = p2Paddle = 0.5f; p1Pong = p2Pong = 0; resetPongBall(random.nextBoolean() ? 1 : -1);
        penaltyTurn = 0; p1Goals = p2Goals = 0; p1Choice = p2Choice = -1; penaltyResult = 0; penaltyDelay = 0f;
        reactionState = 0; reactionTimer = 1.5f + random.nextFloat() * 2.2f; p1Rounds = p2Rounds = 0; reactionRoundWinner = 0;
        tugPos = 0f;
        localInput.set(false, false, false);
        remoteInput.set(false, false, false);
        resetAi();
        sound.go();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (disconnected) return true;
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;

        if (winner != 0 && down && event.getY(index) < getHeight() * 0.56f) {
            if (isHost) resetGame();
            else if (connection != null) connection.send("RESTART");
            return true;
        }

        if (countdown > 0f) return true;

        switch (mode) {
            case FIGHT:
                handleContinuousControls(event, false);
                break;
            case PONG:
                handleContinuousControls(event, true);
                break;
            case RACE:
                if (down && event.getY(index) > getHeight() * 0.69f) {
                    char side = event.getX(index) < getWidth() * 0.5f ? 'L' : 'R';
                    if (isHost) raceTap(1, side); else if (connection != null) connection.send("T|" + side);
                }
                break;
            case TAP_DUEL:
                if (down && event.getY(index) > getHeight() * 0.55f) {
                    if (isHost) tapBattle(1); else if (connection != null) connection.send("TAP");
                }
                break;
            case PENALTY:
                if (down && event.getY(index) > getHeight() * 0.69f && penaltyDelay <= 0f) {
                    float x = event.getX(index) / getWidth();
                    int choice = x < 0.333f ? 0 : x < 0.666f ? 1 : 2;
                    if (isHost) penaltyChoose(1, choice); else if (connection != null) connection.send("CHOICE|" + choice);
                }
                break;
            case REACTION:
                if (down && event.getY(index) > getHeight() * 0.48f) {
                    if (isHost) reactionTap(1); else if (connection != null) connection.send("REACT");
                }
                break;
            case TUG:
                if (down && event.getY(index) > getHeight() * 0.58f) {
                    if (isHost) tugTap(1); else if (connection != null) connection.send("TUG");
                }
                break;
        }
        return true;
    }

    private void handleContinuousControls(MotionEvent event, boolean verticalLabels) {
        int action = event.getActionMasked();
        int liftedIndex = event.getActionIndex();
        boolean a = false, b = false, attack = false;
        for (int j = 0; j < event.getPointerCount(); j++) {
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && j == liftedIndex) continue;
            float x = event.getX(j);
            float y = event.getY(j);
            if (y < getHeight() * 0.69f) continue;
            if (verticalLabels) {
                if (x < getWidth() * 0.48f) a = true; else b = true;
            } else {
                if (x < getWidth() * 0.26f) a = true;
                else if (x < getWidth() * 0.52f) b = true;
                else if (x > getWidth() * 0.66f) attack = true;
            }
        }
        if (action == MotionEvent.ACTION_CANCEL) a = b = attack = false;
        localInput.set(a, b, attack);
        if (!isHost && connection != null) {
            connection.send("I|" + (a ? "1" : "0") + "|" + (b ? "1" : "0") + "|" + (attack ? "1" : "0"));
        }
    }

    // ------------------------- Drawing -------------------------

    private void background(Canvas c, int top, int bottom) {
        paint.setShader(new LinearGradient(0, 0, 0, getHeight(), top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
        paint.setColor(Color.argb(28, 255, 255, 255));
        for (int k = 0; k < 10; k++) {
            float x = (k * 0.127f % 1f) * getWidth();
            float y = (0.07f + (k * 0.173f % 0.55f)) * getHeight();
            c.drawCircle(x, y, dp(2 + (k % 3)), paint);
        }
    }

    private void drawFight(Canvas c) {
        int w = getWidth(), h = getHeight();
        background(c, Color.rgb(28, 38, 73), Color.rgb(10, 16, 31));
        paint.setColor(Color.rgb(59, 73, 91));
        c.drawRect(0, h * 0.56f, w, h * 0.69f, paint);
        paint.setColor(Color.rgb(29, 36, 48));
        c.drawRect(0, h * 0.69f, w, h, paint);

        paint.setColor(Color.argb(80, 255, 207, 74));
        c.drawCircle(w * 0.5f, h * 0.23f, h * 0.16f, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(18));
        paint.setColor(Color.rgb(255, 224, 107));
        c.drawText("NEON ROOFTOP", w * 0.5f, h * 0.18f, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        drawHealthBar(c, w * 0.045f, h * 0.06f, w * 0.39f, h * 0.052f, p1hp, FighterStyle.get(p1Fighter).name, FighterStyle.get(p1Fighter).body);
        drawHealthBar(c, w * 0.565f, h * 0.06f, w * 0.39f, h * 0.052f, p2hp, FighterStyle.get(p2Fighter).name, FighterStyle.get(p2Fighter).body);

        boolean p1Moving = localInput.left || localInput.right;
        boolean p2Moving = remoteInput.left || remoteInput.right || (solo && Math.abs(p2x - p1x) > 0.15f);
        drawCartoonFighter(c, p1x * w, h * 0.565f, Math.min(w, h) * 0.105f, p1Fighter,
                p1x < p2x, p1Cooldown > 0.27f, p1Moving ? animTime * 11f : 0f, p1HitFlash > 0f);
        drawCartoonFighter(c, p2x * w, h * 0.565f, Math.min(w, h) * 0.105f, p2Fighter,
                p2x < p1x, p2Cooldown > 0.27f, p2Moving ? animTime * 11f : 0f, p2HitFlash > 0f);

        drawControl(c, new RectF(w * 0.025f, h * 0.73f, w * 0.23f, h * 0.96f), "◀", localInput.left, Color.rgb(53, 95, 207));
        drawControl(c, new RectF(w * 0.275f, h * 0.73f, w * 0.48f, h * 0.96f), "▶", localInput.right, Color.rgb(53, 95, 207));
        drawControl(c, new RectF(w * 0.69f, h * 0.73f, w * 0.975f, h * 0.96f), "УДАР", localInput.attack, Color.rgb(220, 70, 79));
    }

    private void drawHealthBar(Canvas c, float x, float y, float width, float height, int hp, String label, int color) {
        paint.setColor(Color.argb(180, 20, 26, 40));
        c.drawRoundRect(new RectF(x, y, x + width, y + height), dp(10), dp(10), paint);
        paint.setColor(hp > 30 ? color : Color.rgb(245, 80, 76));
        c.drawRoundRect(new RectF(x, y, x + width * Math.max(0f, hp / 100f), y + height), dp(10), dp(10), paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(14));
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.drawText(label + "  " + hp, x, y - dp(6), paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private void drawRace(Canvas c) {
        int w = getWidth(), h = getHeight();
        background(c, Color.rgb(19, 92, 121), Color.rgb(8, 34, 54));
        float startX = w * 0.10f, finishX = w * 0.90f;
        float lane1 = h * 0.31f, lane2 = h * 0.54f;
        paint.setColor(Color.rgb(53, 57, 66));
        c.drawRoundRect(new RectF(w * 0.045f, h * 0.16f, w * 0.955f, h * 0.65f), dp(20), dp(20), paint);
        paint.setColor(Color.rgb(236, 210, 70));
        c.drawRect(w * 0.05f, h * 0.398f, w * 0.95f, h * 0.408f, paint);
        for (int k = 0; k < 9; k++) {
            paint.setColor(Color.argb(90, 255, 255, 255));
            float x = startX + (finishX - startX) * k / 8f;
            c.drawRect(x, h * 0.18f, x + dp(2), h * 0.63f, paint);
        }
        drawFinishLine(c, finishX, h * 0.17f, h * 0.47f);

        drawRunner(c, startX + (finishX - startX) * p1Race, lane1, p1Fighter, p1Speed, true);
        drawRunner(c, startX + (finishX - startX) * p2Race, lane2, p2Fighter, p2Speed, true);
        drawControl(c, new RectF(w * 0.035f, h * 0.72f, w * 0.465f, h * 0.96f), "ЛЕВО", false, Color.rgb(39, 121, 209));
        drawControl(c, new RectF(w * 0.535f, h * 0.72f, w * 0.965f, h * 0.96f), "ПРАВО", false, Color.rgb(39, 121, 209));
        hint(c, "Чередуйте ЛЕВО / ПРАВО для максимальной скорости", h * 0.69f);
    }

    private void drawFinishLine(Canvas c, float x, float y, float height) {
        float size = Math.max(dp(6), height / 10f);
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 2; col++) {
                paint.setColor(((row + col) & 1) == 0 ? Color.WHITE : Color.BLACK);
                c.drawRect(x + col * size, y + row * size, x + (col + 1) * size, y + (row + 1) * size, paint);
            }
        }
    }

    private void drawTapDuel(Canvas c) {
        int w = getWidth(), h = getHeight();
        background(c, Color.rgb(99, 55, 125), Color.rgb(34, 23, 60));
        drawScoreCard(c, w * 0.10f, h * 0.20f, w * 0.31f, h * 0.28f, p1Fighter, p1Taps, "P1");
        drawScoreCard(c, w * 0.59f, h * 0.20f, w * 0.31f, h * 0.28f, p2Fighter, p2Taps, "P2");
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.rgb(255, 231, 104));
        paint.setTextSize(dp(34));
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.drawText(String.format(Locale.US, "%.1f", tapTime), w * 0.5f, h * 0.38f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        drawControl(c, new RectF(w * 0.16f, h * 0.58f, w * 0.84f, h * 0.94f), "ТАП!", false, Color.rgb(235, 137, 38));
        hint(c, "Нажимайте как можно быстрее", h * 0.55f);
    }

    private void drawScoreCard(Canvas c, float x, float y, float width, float height, int fighter, int score, String tag) {
        FighterStyle s = FighterStyle.get(fighter);
        paint.setColor(Color.argb(190, 15, 20, 35));
        c.drawRoundRect(new RectF(x, y, x + width, y + height), dp(22), dp(22), paint);
        paint.setColor(s.body);
        c.drawCircle(x + width * 0.25f, y + height * 0.5f, height * 0.26f, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(15));
        c.drawText(tag + " · " + s.name, x + width * 0.62f, y + height * 0.30f, paint);
        paint.setTextSize(dp(42));
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.drawText(String.valueOf(score), x + width * 0.62f, y + height * 0.72f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawPong(Canvas c) {
        int w = getWidth(), h = getHeight();
        background(c, Color.rgb(14, 80, 74), Color.rgb(7, 32, 40));
        RectF field = new RectF(w * 0.06f, h * 0.14f, w * 0.94f, h * 0.68f);
        paint.setColor(Color.argb(145, 6, 22, 31));
        c.drawRoundRect(field, dp(20), dp(20), paint);
        thinStroke.setColor(Color.argb(100, 255, 255, 255));
        c.drawRoundRect(field, dp(20), dp(20), thinStroke);
        for (int k = 0; k < 9; k++) {
            float y = field.top + field.height() * k / 8f;
            c.drawLine(w * 0.5f, y, w * 0.5f, Math.min(field.bottom, y + field.height() / 18f), thinStroke);
        }
        float paddleH = field.height() * 0.20f;
        float p1y = h * p1Paddle, p2y = h * p2Paddle;
        drawPaddle(c, w * 0.09f, p1y, paddleH, FighterStyle.get(p1Fighter).body);
        drawPaddle(c, w * 0.91f, p2y, paddleH, FighterStyle.get(p2Fighter).body);
        paint.setColor(Color.WHITE);
        c.drawCircle(w * ballX, h * ballY, Math.min(w, h) * 0.018f, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(30));
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.drawText(p1Pong + "  :  " + p2Pong, w * 0.5f, h * 0.115f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        drawControl(c, new RectF(w * 0.035f, h * 0.73f, w * 0.465f, h * 0.96f), "▲ ВВЕРХ", localInput.left, Color.rgb(42, 151, 124));
        drawControl(c, new RectF(w * 0.535f, h * 0.73f, w * 0.965f, h * 0.96f), "▼ ВНИЗ", localInput.right, Color.rgb(42, 151, 124));
    }

    private void drawPaddle(Canvas c, float x, float y, float height, int color) {
        paint.setColor(color);
        c.drawRoundRect(new RectF(x - dp(7), y - height * 0.5f, x + dp(7), y + height * 0.5f), dp(8), dp(8), paint);
    }

    private void drawPenalty(Canvas c) {
        int w = getWidth(), h = getHeight();
        background(c, Color.rgb(31, 118, 65), Color.rgb(9, 55, 37));
        paint.setColor(Color.rgb(30, 134, 70));
        c.drawRect(0, h * 0.12f, w, h * 0.69f, paint);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        RectF goal = new RectF(w * 0.27f, h * 0.20f, w * 0.73f, h * 0.56f);
        c.drawRect(goal, paint);
        c.drawLine(goal.left + goal.width() / 3f, goal.top, goal.left + goal.width() / 3f, goal.bottom, paint);
        c.drawLine(goal.left + goal.width() * 2f / 3f, goal.top, goal.left + goal.width() * 2f / 3f, goal.bottom, paint);
        paint.setStyle(Paint.Style.FILL);

        boolean p1Shoots = (penaltyTurn & 1) == 0;
        int shooter = p1Shoots ? p1Fighter : p2Fighter;
        int keeper = p1Shoots ? p2Fighter : p1Fighter;
        drawCartoonFighter(c, w * 0.50f, h * 0.64f, Math.min(w, h) * 0.08f, shooter, false, penaltyDelay > 0.8f, animTime * 6f, false);
        drawCartoonFighter(c, w * 0.50f, h * 0.49f, Math.min(w, h) * 0.055f, keeper, true, false, 0f, false);

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(22));
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.drawText("P1 " + p1Goals + "  ·  " + p2Goals + " P2", w * 0.5f, h * 0.115f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(dp(14));
        String role = ((localPlayer == 1) == p1Shoots) ? "Вы бьёте: выберите угол" : "Вы в воротах: выберите угол";
        c.drawText("Удар " + Math.min(10, penaltyTurn + 1) + "/10 · " + role, w * 0.5f, h * 0.68f, paint);

        if (penaltyResult != 0) {
            paint.setTextSize(dp(34));
            paint.setColor(penaltyResult == 1 ? Color.rgb(255, 230, 80) : Color.rgb(126, 221, 255));
            c.drawText(penaltyResult == 1 ? "ГОООЛ!" : "СЕЙВ!", w * 0.5f, h * 0.39f, paint);
        }
        drawControl(c, new RectF(w * 0.025f, h * 0.73f, w * 0.315f, h * 0.96f), "ЛЕВО", false, Color.rgb(41, 140, 76));
        drawControl(c, new RectF(w * 0.355f, h * 0.73f, w * 0.645f, h * 0.96f), "ЦЕНТР", false, Color.rgb(41, 140, 76));
        drawControl(c, new RectF(w * 0.685f, h * 0.73f, w * 0.975f, h * 0.96f), "ПРАВО", false, Color.rgb(41, 140, 76));
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawReaction(Canvas c) {
        int w = getWidth(), h = getHeight();
        int top = reactionState == 1 ? Color.rgb(28, 150, 91) : reactionState == 2 ? Color.rgb(67, 79, 112) : Color.rgb(151, 61, 72);
        background(c, top, Color.rgb(17, 24, 42));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(20));
        c.drawText("P1 " + dots(p1Rounds) + "     " + dots(p2Rounds) + " P2", w * 0.5f, h * 0.17f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(54));
        if (reactionState == 0) {
            paint.setColor(Color.rgb(255, 219, 90));
            c.drawText("ЖДИ…", w * 0.5f, h * 0.42f, paint);
        } else if (reactionState == 1) {
            paint.setColor(Color.WHITE);
            c.drawText("ЖМИ!", w * 0.5f, h * 0.42f, paint);
        } else {
            paint.setColor(Color.rgb(255, 228, 91));
            c.drawText(reactionRoundWinner == localPlayer ? "ТВОЁ ОЧКО!" : "ОЧКО СОПЕРНИКА", w * 0.5f, h * 0.42f, paint);
        }
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        drawControl(c, new RectF(w * 0.16f, h * 0.56f, w * 0.84f, h * 0.94f), reactionState == 1 ? "ЖМИ!" : "НЕ ЖМИ", false,
                reactionState == 1 ? Color.rgb(41, 185, 105) : Color.rgb(165, 66, 78));
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private String dots(int n) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < 3; k++) b.append(k < n ? "●" : "○");
        return b.toString();
    }

    private void drawTug(Canvas c) {
        int w = getWidth(), h = getHeight();
        background(c, Color.rgb(132, 73, 45), Color.rgb(48, 31, 35));
        float centerY = h * 0.42f;
        paint.setColor(Color.rgb(103, 61, 37));
        c.drawRoundRect(new RectF(w * 0.11f, centerY - dp(12), w * 0.89f, centerY + dp(12)), dp(12), dp(12), paint);
        float knotX = w * 0.5f + tugPos * w * 0.29f;
        paint.setColor(Color.rgb(241, 191, 83));
        c.drawCircle(knotX, centerY, dp(19), paint);
        paint.setColor(Color.WHITE);
        c.drawRect(w * 0.495f, h * 0.26f, w * 0.505f, h * 0.58f, paint);
        drawCartoonFighter(c, w * 0.16f, h * 0.60f, Math.min(w, h) * 0.09f, p1Fighter, true, false, animTime * 8f, false);
        drawCartoonFighter(c, w * 0.84f, h * 0.60f, Math.min(w, h) * 0.09f, p2Fighter, false, false, animTime * 8f, false);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(16));
        c.drawText("P1", w * 0.16f, h * 0.19f, paint);
        c.drawText("P2", w * 0.84f, h * 0.19f, paint);
        drawControl(c, new RectF(w * 0.18f, h * 0.66f, w * 0.82f, h * 0.95f), "ТЯНИ!", false, Color.rgb(211, 113, 49));
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawRunner(Canvas c, float x, float groundY, int fighter, float speed, boolean faceRight) {
        float run = speed > 0.02f ? animTime * (8f + speed * 20f) : 0f;
        drawCartoonFighter(c, x, groundY, Math.min(getWidth(), getHeight()) * 0.072f, fighter, faceRight, false, run, false);
        if (speed > 0.12f) {
            paint.setColor(Color.argb(80, 255, 255, 255));
            for (int k = 0; k < 3; k++) {
                float dx = dp(10 + k * 8);
                c.drawCircle(x - dx, groundY - dp(5 + k * 2), dp(3 + k), paint);
            }
        }
    }

    private void drawCartoonFighter(Canvas c, float x, float groundY, float s, int fighterId,
                                    boolean faceRight, boolean attacking, float runPhase, boolean hit) {
        FighterStyle f = FighterStyle.get(fighterId);
        float dir = faceRight ? 1f : -1f;
        float run = (float) Math.sin(runPhase);
        float bob = runPhase == 0f ? 0f : Math.abs((float) Math.sin(runPhase * 2f)) * s * 0.04f;
        float headY = groundY - s * 1.62f - bob;
        float bodyTop = groundY - s * 1.15f - bob;
        float hipY = groundY - s * 0.57f - bob;

        // Shadow.
        paint.setColor(Color.argb(80, 0, 0, 0));
        c.drawOval(new RectF(x - s * 0.52f, groundY - s * 0.08f, x + s * 0.52f, groundY + s * 0.08f), paint);

        // Legs and shoes.
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(s * 0.19f);
        paint.setColor(darken(f.body, 0.72f));
        float legSwing = run * s * 0.25f;
        c.drawLine(x - s * 0.16f, hipY, x - s * 0.26f + legSwing, groundY - s * 0.12f, paint);
        c.drawLine(x + s * 0.16f, hipY, x + s * 0.26f - legSwing, groundY - s * 0.12f, paint);
        paint.setStrokeWidth(s * 0.16f);
        paint.setColor(f.accent);
        c.drawLine(x - s * 0.26f + legSwing, groundY - s * 0.10f, x - s * 0.48f + legSwing, groundY - s * 0.08f, paint);
        c.drawLine(x + s * 0.26f - legSwing, groundY - s * 0.10f, x + s * 0.48f - legSwing, groundY - s * 0.08f, paint);

        // Body hoodie/shirt.
        paint.setColor(hit ? Color.WHITE : f.body);
        RectF torso = new RectF(x - s * 0.38f, bodyTop, x + s * 0.38f, hipY + s * 0.12f);
        c.drawRoundRect(torso, s * 0.20f, s * 0.20f, paint);
        paint.setColor(f.accent);
        c.drawRect(x - s * 0.09f, bodyTop + s * 0.07f, x + s * 0.09f, hipY + s * 0.08f, paint);

        // Arms and gloves.
        paint.setStrokeWidth(s * 0.18f);
        paint.setColor(f.skin);
        float shoulderY = bodyTop + s * 0.18f;
        float attackLen = attacking ? s * 0.95f : s * 0.46f;
        float attackY = attacking ? shoulderY - s * 0.03f : shoulderY + run * s * 0.13f;
        c.drawLine(x + dir * s * 0.25f, shoulderY, x + dir * attackLen, attackY, paint);
        c.drawLine(x - dir * s * 0.25f, shoulderY, x - dir * s * 0.48f, shoulderY - run * s * 0.13f + s * 0.12f, paint);
        paint.setColor(f.accent);
        c.drawCircle(x + dir * attackLen, attackY, s * 0.16f, paint);
        c.drawCircle(x - dir * s * 0.48f, shoulderY - run * s * 0.13f + s * 0.12f, s * 0.14f, paint);

        // Head.
        paint.setColor(f.skin);
        c.drawCircle(x, headY, s * 0.39f, paint);
        // Hair cap.
        paint.setColor(f.hair);
        Path hair = new Path();
        hair.moveTo(x - s * 0.36f, headY - s * 0.04f);
        hair.quadTo(x - s * 0.22f, headY - s * 0.49f, x + s * 0.34f, headY - s * 0.24f);
        hair.lineTo(x + s * 0.22f, headY - s * 0.04f);
        hair.close();
        c.drawPath(hair, paint);
        // Eye and eyebrow.
        float eyeX = x + dir * s * 0.14f;
        paint.setColor(Color.WHITE);
        c.drawCircle(eyeX, headY, s * 0.085f, paint);
        paint.setColor(Color.rgb(30, 34, 45));
        c.drawCircle(eyeX + dir * s * 0.018f, headY, s * 0.038f, paint);
        paint.setStrokeWidth(s * 0.035f);
        c.drawLine(eyeX - dir * s * 0.10f, headY - s * 0.12f, eyeX + dir * s * 0.08f, headY - s * 0.15f, paint);
        // Smile / determined mouth.
        paint.setStrokeWidth(s * 0.035f);
        c.drawLine(x + dir * s * 0.04f, headY + s * 0.18f, x + dir * s * 0.18f, headY + s * 0.16f, paint);

        if (attacking) drawImpact(c, x + dir * s * 1.05f, attackY, s * 0.28f, f.accent);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawImpact(Canvas c, float x, float y, float r, int color) {
        paint.setColor(color);
        Path p = new Path();
        for (int k = 0; k < 12; k++) {
            double a = Math.PI * 2.0 * k / 12.0;
            float rr = (k & 1) == 0 ? r : r * 0.38f;
            float px = x + (float) Math.cos(a) * rr;
            float py = y + (float) Math.sin(a) * rr;
            if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.close();
        c.drawPath(p, paint);
    }

    private int darken(int color, float factor) {
        return Color.rgb(
                Math.max(0, Math.min(255, Math.round(Color.red(color) * factor))),
                Math.max(0, Math.min(255, Math.round(Color.green(color) * factor))),
                Math.max(0, Math.min(255, Math.round(Color.blue(color) * factor))));
    }

    private void drawControl(Canvas c, RectF rect, String label, boolean active, int color) {
        paint.setColor(active ? color : darken(color, 0.64f));
        c.drawRoundRect(rect, dp(20), dp(20), paint);
        thinStroke.setColor(active ? Color.WHITE : Color.argb(110, 255, 255, 255));
        c.drawRoundRect(rect, dp(20), dp(20), thinStroke);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(Math.min(dp(27), rect.height() * 0.28f));
        float baseline = rect.centerY() - (paint.ascent() + paint.descent()) * 0.5f;
        c.drawText(label, rect.centerX(), baseline, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void hint(Canvas c, String text, float y) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.argb(215, 235, 241, 250));
        paint.setTextSize(dp(13));
        c.drawText(text, getWidth() * 0.5f, y, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawStatus(Canvas c) {
        int w = getWidth(), h = getHeight();
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.argb(225, 235, 241, 250));
        paint.setTextSize(dp(12));
        String connectionText = solo ? "ПРОТИВ БОТА" : (isHost ? "ХОСТ · P1" : "КЛИЕНТ · P2");
        c.drawText(mode.icon + "  " + mode.title + "   ·   " + connectionText, w * 0.5f, dp(18), paint);

        if (countdown > 0f && winner == 0) {
            paint.setColor(Color.argb(190, 7, 12, 24));
            c.drawRoundRect(new RectF(w * 0.41f, h * 0.26f, w * 0.59f, h * 0.52f), dp(22), dp(22), paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dp(58));
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            c.drawText(String.valueOf((int) Math.ceil(countdown)), w * 0.5f, h * 0.44f, paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        if (winner != 0) {
            paint.setColor(Color.argb(238, 8, 13, 25));
            c.drawRoundRect(new RectF(w * 0.20f, h * 0.16f, w * 0.80f, h * 0.53f), dp(24), dp(24), paint);
            paint.setColor(Color.rgb(255, 228, 86));
            paint.setTextSize(dp(34));
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            String text;
            if (winner == 3) text = "НИЧЬЯ!";
            else if (winner == localPlayer) text = "ПОБЕДА!";
            else text = solo ? "БОТ ПОБЕДИЛ" : "ПОБЕДИЛ ИГРОК " + winner;
            c.drawText(text, w * 0.5f, h * 0.31f, paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
            paint.setTextSize(dp(16));
            paint.setColor(Color.WHITE);
            c.drawText("Нажмите на это окно для реванша", w * 0.5f, h * 0.42f, paint);
        }

        if (disconnected) {
            paint.setColor(Color.argb(245, 8, 13, 25));
            c.drawRect(0, 0, w, h, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dp(30));
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            c.drawText("СОЕДИНЕНИЕ ПОТЕРЯНО", w * 0.5f, h * 0.44f, paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
            paint.setTextSize(dp(16));
            c.drawText("Нажмите системную кнопку «Назад»", w * 0.5f, h * 0.54f, paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

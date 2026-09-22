package com.openai.dueldash.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.openai.dueldash.net.PeerConnection;

import java.util.Locale;

public final class DuelGameView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GameMode mode;
    private final boolean isHost;
    private final int localPlayer;
    private final PeerConnection connection;
    private final PlayerInput localInput = new PlayerInput();
    private final PlayerInput remoteInput = new PlayerInput();

    private long lastFrameNs = System.nanoTime();
    private long lastSnapshotNs = 0L;
    private boolean disconnected = false;

    // Shared state, authoritative on host.
    private float countdown = 3f;
    private int winner = 0;

    // Fight state.
    private float p1x = 0.28f;
    private float p2x = 0.72f;
    private int p1hp = 100;
    private int p2hp = 100;
    private float p1Cooldown = 0f;
    private float p2Cooldown = 0f;

    // Race state.
    private float p1Race = 0f;
    private float p2Race = 0f;
    private float p1Speed = 0f;
    private float p2Speed = 0f;
    private char p1LastTap = '-';
    private char p2LastTap = '-';

    public DuelGameView(Context context, GameMode mode, boolean isHost, PeerConnection connection) {
        super(context);
        this.mode = mode;
        this.isHost = isHost;
        this.localPlayer = isHost ? 1 : 2;
        this.connection = connection;
        setFocusable(true);
        setKeepScreenOn(true);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(3));
        stroke.setColor(Color.WHITE);

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

    public void shutdown() {
        connection.close();
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

        if (isHost && !disconnected) {
            if (mode == GameMode.FIGHT) updateFight(dt);
            else updateRace(dt);

            if (now - lastSnapshotNs >= 50_000_000L) {
                connection.send(snapshot());
                lastSnapshotNs = now;
            }
        }

        if (mode == GameMode.FIGHT) drawFight(canvas);
        else drawRace(canvas);

        drawStatus(canvas);
        postInvalidateOnAnimation();
    }

    private void updateFight(float dt) {
        if (winner != 0) return;
        if (countdown > 0f) {
            countdown = Math.max(0f, countdown - dt);
            return;
        }

        p1Cooldown = Math.max(0f, p1Cooldown - dt);
        p2Cooldown = Math.max(0f, p2Cooldown - dt);

        float speed = 0.48f;
        if (localInput.left) p1x -= speed * dt;
        if (localInput.right) p1x += speed * dt;
        if (remoteInput.left) p2x -= speed * dt;
        if (remoteInput.right) p2x += speed * dt;

        p1x = clamp(p1x, 0.08f, 0.92f);
        p2x = clamp(p2x, 0.08f, 0.92f);

        // Prevent the two fighters from fully overlapping.
        if (Math.abs(p1x - p2x) < 0.09f) {
            float mid = (p1x + p2x) * 0.5f;
            if (p1x < p2x) {
                p1x = mid - 0.045f;
                p2x = mid + 0.045f;
            } else {
                p2x = mid - 0.045f;
                p1x = mid + 0.045f;
            }
        }

        if (localInput.attack && p1Cooldown <= 0f) {
            p1Cooldown = 0.42f;
            if (Math.abs(p1x - p2x) < 0.18f) {
                p2hp = Math.max(0, p2hp - 10);
                p2x = clamp(p2x + (p2x > p1x ? 0.045f : -0.045f), 0.08f, 0.92f);
            }
        }
        if (remoteInput.attack && p2Cooldown <= 0f) {
            p2Cooldown = 0.42f;
            if (Math.abs(p1x - p2x) < 0.18f) {
                p1hp = Math.max(0, p1hp - 10);
                p1x = clamp(p1x + (p1x > p2x ? 0.045f : -0.045f), 0.08f, 0.92f);
            }
        }

        if (p1hp <= 0) winner = 2;
        if (p2hp <= 0) winner = 1;
    }

    private void updateRace(float dt) {
        if (winner != 0) return;
        if (countdown > 0f) {
            countdown = Math.max(0f, countdown - dt);
            return;
        }

        p1Race += p1Speed * dt;
        p2Race += p2Speed * dt;
        p1Speed = Math.max(0f, p1Speed - 0.13f * dt);
        p2Speed = Math.max(0f, p2Speed - 0.13f * dt);

        if (p1Race >= 1f || p2Race >= 1f) {
            if (p1Race >= 1f && p2Race >= 1f) {
                winner = p1Race >= p2Race ? 1 : 2;
            } else {
                winner = p1Race >= 1f ? 1 : 2;
            }
            p1Race = Math.min(1f, p1Race);
            p2Race = Math.min(1f, p2Race);
        }
    }

    private void raceTap(int player, char side) {
        if (!isHost || mode != GameMode.RACE || winner != 0 || countdown > 0f) return;
        if (player == 1) {
            boolean alternated = p1LastTap != '-' && p1LastTap != side;
            p1Speed = Math.min(0.48f, p1Speed + (alternated ? 0.095f : 0.028f));
            p1LastTap = side;
        } else {
            boolean alternated = p2LastTap != '-' && p2LastTap != side;
            p2Speed = Math.min(0.48f, p2Speed + (alternated ? 0.095f : 0.028f));
            p2LastTap = side;
        }
    }

    private String snapshot() {
        if (mode == GameMode.FIGHT) {
            return String.format(Locale.US, "S|F|%.3f|%.5f|%.5f|%d|%d|%d|%.3f|%.3f",
                    countdown, p1x, p2x, p1hp, p2hp, winner, p1Cooldown, p2Cooldown);
        }
        return String.format(Locale.US, "S|R|%.3f|%.5f|%.5f|%.5f|%.5f|%d",
                countdown, p1Race, p2Race, p1Speed, p2Speed, winner);
    }

    private void handleNetworkMessage(String line) {
        try {
            String[] p = line.split("\\|");
            if (isHost) {
                if (p.length >= 4 && "I".equals(p[0])) {
                    remoteInput.set("1".equals(p[1]), "1".equals(p[2]), "1".equals(p[3]));
                } else if (p.length >= 2 && "T".equals(p[0]) && p[1].length() > 0) {
                    raceTap(2, p[1].charAt(0));
                } else if ("RESTART".equals(line)) {
                    resetGame();
                }
                return;
            }

            if (p.length >= 2 && "S".equals(p[0])) {
                if ("F".equals(p[1]) && p.length >= 10) {
                    countdown = Float.parseFloat(p[2]);
                    p1x = Float.parseFloat(p[3]);
                    p2x = Float.parseFloat(p[4]);
                    p1hp = Integer.parseInt(p[5]);
                    p2hp = Integer.parseInt(p[6]);
                    winner = Integer.parseInt(p[7]);
                    p1Cooldown = Float.parseFloat(p[8]);
                    p2Cooldown = Float.parseFloat(p[9]);
                } else if ("R".equals(p[1]) && p.length >= 8) {
                    countdown = Float.parseFloat(p[2]);
                    p1Race = Float.parseFloat(p[3]);
                    p2Race = Float.parseFloat(p[4]);
                    p1Speed = Float.parseFloat(p[5]);
                    p2Speed = Float.parseFloat(p[6]);
                    winner = Integer.parseInt(p[7]);
                }
            }
        } catch (Exception ignored) {
            // A malformed packet is ignored; the next host snapshot repairs the client view.
        }
    }

    private void resetGame() {
        winner = 0;
        countdown = 3f;
        localInput.set(false, false, false);
        remoteInput.set(false, false, false);
        p1x = 0.28f;
        p2x = 0.72f;
        p1hp = 100;
        p2hp = 100;
        p1Cooldown = 0f;
        p2Cooldown = 0f;
        p1Race = 0f;
        p2Race = 0f;
        p1Speed = 0f;
        p2Speed = 0f;
        p1LastTap = '-';
        p2LastTap = '-';
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (disconnected) return true;

        int action = event.getActionMasked();
        int index = event.getActionIndex();

        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                && winner != 0 && event.getY(index) < getHeight() * 0.32f) {
            if (isHost) resetGame();
            else connection.send("RESTART");
            return true;
        }

        if (mode == GameMode.RACE) {
            if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                    && winner == 0 && countdown <= 0f && event.getY(index) > getHeight() * 0.68f) {
                char side = event.getX(index) < getWidth() * 0.5f ? 'L' : 'R';
                if (isHost) raceTap(1, side);
                else connection.send("T|" + side);
            }
            return true;
        }

        boolean left = false;
        boolean right = false;
        boolean attack = false;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == index) {
                continue;
            }
            float x = event.getX(i);
            float y = event.getY(i);
            if (y < getHeight() * 0.68f) continue;
            if (x < getWidth() * 0.26f) left = true;
            else if (x < getWidth() * 0.52f) right = true;
            else if (x > getWidth() * 0.66f) attack = true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            left = right = attack = false;
        }
        localInput.set(left, right, attack);
        if (!isHost) {
            connection.send("I|" + (left ? "1" : "0") + "|" + (right ? "1" : "0") + "|" + (attack ? "1" : "0"));
        }
        return true;
    }

    private void drawFight(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        c.drawColor(Color.rgb(12, 20, 35));

        paint.setColor(Color.rgb(35, 52, 75));
        c.drawRect(0, h * 0.58f, w, h * 0.68f, paint);
        paint.setColor(Color.rgb(21, 31, 47));
        c.drawRect(0, h * 0.68f, w, h, paint);

        drawHealthBar(c, w * 0.05f, h * 0.06f, w * 0.38f, h * 0.055f, p1hp, "P1");
        drawHealthBar(c, w * 0.57f, h * 0.06f, w * 0.38f, h * 0.055f, p2hp, "P2");

        drawFighter(c, p1x * w, h * 0.56f, Color.rgb(61, 169, 252), p1x < p2x, p1Cooldown > 0.28f);
        drawFighter(c, p2x * w, h * 0.56f, Color.rgb(255, 100, 103), p2x < p1x, p2Cooldown > 0.28f);

        drawFightControls(c);
    }

    private void drawHealthBar(Canvas c, float x, float y, float width, float height, int hp, String label) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(58, 67, 82));
        c.drawRoundRect(new RectF(x, y, x + width, y + height), dp(8), dp(8), paint);
        float inner = Math.max(0f, hp / 100f);
        paint.setColor(hp > 35 ? Color.rgb(51, 205, 129) : Color.rgb(246, 80, 80));
        c.drawRoundRect(new RectF(x, y, x + width * inner, y + height), dp(8), dp(8), paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(16));
        c.drawText(label + "  " + hp, x, y - dp(7), paint);
    }

    private void drawFighter(Canvas c, float x, float groundY, int color, boolean faceRight, boolean attacking) {
        float s = Math.min(getWidth(), getHeight()) * 0.075f;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(s * 0.18f);
        paint.setColor(color);
        float headY = groundY - s * 1.75f;
        c.drawCircle(x, headY, s * 0.38f, paint);
        c.drawLine(x, headY + s * 0.42f, x, groundY - s * 0.55f, paint);
        c.drawLine(x, groundY - s * 0.7f, x - s * 0.42f, groundY, paint);
        c.drawLine(x, groundY - s * 0.7f, x + s * 0.42f, groundY, paint);

        float dir = faceRight ? 1f : -1f;
        float armLength = attacking ? s * 0.95f : s * 0.55f;
        c.drawLine(x, headY + s * 0.72f, x + dir * armLength, headY + s * 0.68f, paint);
        c.drawLine(x, headY + s * 0.72f, x - dir * s * 0.42f, headY + s * 1.05f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawFightControls(Canvas c) {
        float top = getHeight() * 0.72f;
        float bottom = getHeight() * 0.96f;
        drawControl(c, new RectF(getWidth() * 0.03f, top, getWidth() * 0.23f, bottom), "◀", localInput.left);
        drawControl(c, new RectF(getWidth() * 0.28f, top, getWidth() * 0.48f, bottom), "▶", localInput.right);
        drawControl(c, new RectF(getWidth() * 0.70f, top, getWidth() * 0.97f, bottom), "УДАР", localInput.attack);
    }

    private void drawRace(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        c.drawColor(Color.rgb(10, 31, 36));

        float startX = w * 0.10f;
        float finishX = w * 0.90f;
        float lane1 = h * 0.29f;
        float lane2 = h * 0.53f;

        paint.setColor(Color.rgb(43, 61, 67));
        c.drawRoundRect(new RectF(w * 0.05f, h * 0.17f, w * 0.95f, h * 0.64f), dp(14), dp(14), paint);
        paint.setColor(Color.rgb(210, 218, 221));
        c.drawRect(w * 0.05f, h * 0.405f, w * 0.95f, h * 0.415f, paint);

        // Finish line.
        float square = Math.max(dp(7), w * 0.008f);
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 2; col++) {
                paint.setColor(((row + col) & 1) == 0 ? Color.WHITE : Color.BLACK);
                c.drawRect(finishX + col * square, h * 0.17f + row * (h * 0.047f),
                        finishX + (col + 1) * square, h * 0.17f + (row + 1) * (h * 0.047f), paint);
            }
        }

        float p1px = startX + (finishX - startX) * p1Race;
        float p2px = startX + (finishX - startX) * p2Race;
        drawRunner(c, p1px, lane1, Color.rgb(61, 169, 252), p1Speed);
        drawRunner(c, p2px, lane2, Color.rgb(255, 100, 103), p2Speed);

        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(15));
        c.drawText("P1", w * 0.055f, lane1 + dp(5), paint);
        c.drawText("P2", w * 0.055f, lane2 + dp(5), paint);

        drawControl(c, new RectF(w * 0.04f, h * 0.72f, w * 0.46f, h * 0.96f), "ЛЕВО", false);
        drawControl(c, new RectF(w * 0.54f, h * 0.72f, w * 0.96f, h * 0.96f), "ПРАВО", false);

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(15));
        paint.setColor(Color.rgb(190, 205, 210));
        c.drawText("Нажимайте ЛЕВО / ПРАВО по очереди, чтобы ускоряться", w * 0.5f, h * 0.69f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawRunner(Canvas c, float x, float y, int color, float speed) {
        float s = Math.min(getWidth(), getHeight()) * 0.055f;
        float bob = (float) Math.sin(System.nanoTime() / 55_000_000.0) * s * Math.min(0.18f, speed * 0.5f);
        paint.setColor(color);
        paint.setStrokeWidth(s * 0.17f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawCircle(x, y - s * 0.82f + bob, s * 0.27f, paint);
        c.drawLine(x, y - s * 0.52f + bob, x + s * 0.18f, y - s * 0.05f + bob, paint);
        c.drawLine(x + s * 0.1f, y - s * 0.28f + bob, x + s * 0.55f, y - s * 0.55f + bob, paint);
        c.drawLine(x + s * 0.15f, y - s * 0.05f + bob, x + s * 0.55f, y + s * 0.25f, paint);
        c.drawLine(x + s * 0.15f, y - s * 0.05f + bob, x - s * 0.30f, y + s * 0.25f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawControl(Canvas c, RectF rect, String label, boolean active) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? Color.rgb(76, 110, 245) : Color.rgb(50, 67, 91));
        c.drawRoundRect(rect, dp(18), dp(18), paint);
        stroke.setColor(Color.rgb(120, 139, 168));
        c.drawRoundRect(rect, dp(18), dp(18), stroke);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.min(dp(28), rect.height() * 0.35f));
        float baseline = rect.centerY() - (paint.ascent() + paint.descent()) * 0.5f;
        c.drawText(label, rect.centerX(), baseline, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawStatus(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);

        if (countdown > 0f && winner == 0) {
            String text = countdown > 0.05f ? String.valueOf((int) Math.ceil(countdown)) : "GO!";
            paint.setTextSize(dp(54));
            c.drawText(text, w * 0.5f, h * 0.34f, paint);
        }

        paint.setTextSize(dp(14));
        paint.setColor(Color.rgb(180, 194, 214));
        c.drawText("Вы: игрок " + localPlayer + (isHost ? " · ХОСТ" : " · КЛИЕНТ"), w * 0.5f, dp(22), paint);

        if (winner != 0) {
            paint.setColor(Color.argb(220, 8, 15, 27));
            c.drawRoundRect(new RectF(w * 0.25f, h * 0.18f, w * 0.75f, h * 0.47f), dp(18), dp(18), paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dp(34));
            c.drawText("ПОБЕДИЛ ИГРОК " + winner, w * 0.5f, h * 0.31f, paint);
            paint.setTextSize(dp(16));
            c.drawText("Нажмите сюда для реванша", w * 0.5f, h * 0.40f, paint);
        }

        if (disconnected) {
            paint.setColor(Color.argb(235, 8, 15, 27));
            c.drawRect(0, 0, w, h, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dp(30));
            c.drawText("Соединение потеряно", w * 0.5f, h * 0.46f, paint);
            paint.setTextSize(dp(17));
            c.drawText("Нажмите системную кнопку «Назад»", w * 0.5f, h * 0.54f, paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

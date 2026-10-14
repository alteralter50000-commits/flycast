package com.flycast.emulator.dashboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dark-blue console style home screen: a row of function icons on top, a big row of game tiles
 * (or a list with a description for the other sections), an animated wavy background and
 * keyboard / gamepad / touch navigation. Everything is drawn in code.
 */
public class DashboardView extends View {
    public static final int ICON_GAMES = 0;
    public static final int ICON_ADD = 1;
    public static final int ICON_FILES = 2;
    public static final int ICON_SETTINGS = 3;
    public static final int ICON_EMULATOR = 4;

    public static class Item {
        public final String title;
        public final String desc;
        public final boolean game;
        public final Runnable action;
        public String cover;
        StaticLayout titleLayout;
        int layoutWidth;

        public Item(String title, String desc, boolean game, Runnable action) {
            this.title = title;
            this.desc = desc;
            this.game = game;
            this.action = action;
        }
    }

    public static class Tab {
        public final String title;
        public final int icon;
        public final List<Item> items = new ArrayList<>();
        public String emptyText = "";

        public Tab(String title, int icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    public interface Listener {
        void onMove();

        void onSelect();

        void onBack();

        void onExit();
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> covers = new LruCache<>(30);
    private final Set<String> pending = new HashSet<>();
    private final Set<String> failed = new HashSet<>();

    private Listener listener;
    private String hintSelect = "Select", hintBack = "Back", hintTabs = "Tabs";
    private Bitmap background;

    private List<Tab> tabs = new ArrayList<>();
    private int tab = 0;
    private int[] sel = new int[0];
    private boolean focusTop = false;

    // smoothed animation state
    private float tabPos = 0;
    private float scrollPos = 0;
    private float listScroll = 0;
    private float focusAnim = 0; // 1 when the top bar has focus
    private long lastFrame = 0;
    private float time = 0;

    // paints
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private Shader skyShader;
    private int shaderH = -1;

    // particles
    private static final int PARTICLES = 40;
    private final float[] px = new float[PARTICLES];
    private final float[] py = new float[PARTICLES];
    private final float[] pv = new float[PARTICLES];
    private final float[] ps = new float[PARTICLES];

    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private String clock = "";
    private long clockStamp = 0;

    // input state
    private float downX, downY, lastDragX, lastDragY;
    private boolean dragging;
    private float dragAccum;
    private long lastAxisMove = 0;

    private float u = 1; // one design unit (screen height / 1080)

    public DashboardView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        Random r = new Random(5);
        for (int i = 0; i < PARTICLES; i++) {
            px[i] = r.nextFloat();
            py[i] = r.nextFloat();
            pv[i] = 0.008f + r.nextFloat() * 0.02f;
            ps[i] = 1.5f + r.nextFloat() * 3.5f;
        }
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setHints(String select, String back, String tabsHint) {
        hintSelect = select;
        hintBack = back;
        hintTabs = tabsHint;
        invalidate();
    }

    public void setBackgroundBitmap(Bitmap bmp) {
        background = bmp;
        invalidate();
    }

    public void setTabs(List<Tab> newTabs) {
        int[] old = sel;
        tabs = newTabs;
        sel = new int[newTabs.size()];
        for (int i = 0; i < sel.length; i++) {
            if (i < old.length)
                sel[i] = old[i];
            int n = newTabs.get(i).items.size();
            if (sel[i] >= n)
                sel[i] = Math.max(0, n - 1);
        }
        if (tab >= tabs.size())
            tab = 0;
        invalidate();
    }

    public int getTabIndex() {
        return tab;
    }

    // ---------------------------------------------------------------- navigation

    private Tab cur() {
        return tabs.isEmpty() ? null : tabs.get(tab);
    }

    private int count() {
        Tab t = cur();
        return t == null ? 0 : t.items.size();
    }

    private void moved() {
        if (listener != null)
            listener.onMove();
        invalidate();
    }

    private void changeTab(int delta) {
        if (tabs.isEmpty())
            return;
        int n = Math.max(0, Math.min(tabs.size() - 1, tab + delta));
        if (n != tab) {
            tab = n;
            moved();
        }
    }

    private void gotoTab(int index) {
        if (index >= 0 && index < tabs.size() && index != tab) {
            tab = index;
            moved();
        }
    }

    private void moveSel(int delta) {
        int n = count();
        if (n == 0)
            return;
        int s = Math.max(0, Math.min(n - 1, sel[tab] + delta));
        if (s != sel[tab]) {
            sel[tab] = s;
            moved();
        }
    }

    private void activate() {
        Tab t = cur();
        if (t == null || t.items.isEmpty())
            return;
        Item it = t.items.get(sel[tab]);
        if (listener != null)
            listener.onSelect();
        if (it.action != null)
            it.action.run();
        invalidate();
    }

    private boolean isGames() {
        Tab t = cur();
        return t != null && t.icon == ICON_GAMES;
    }

    private void up() {
        if (focusTop)
            return;
        if (isGames() || sel[tab] == 0) {
            focusTop = true;
            moved();
        } else {
            moveSel(-1);
        }
    }

    private void down() {
        if (focusTop) {
            if (count() > 0) {
                focusTop = false;
                moved();
            }
        } else if (!isGames()) {
            moveSel(1);
        }
    }

    private void left() {
        if (focusTop)
            changeTab(-1);
        else if (isGames())
            moveSel(-1);
    }

    private void right() {
        if (focusTop)
            changeTab(1);
        else if (isGames())
            moveSel(1);
    }

    /** Back button: content -> top bar -> first tab -> exit. */
    public boolean handleBack() {
        if (!focusTop && count() > 0) {
            focusTop = true;
            if (listener != null)
                listener.onBack();
            invalidate();
        } else if (tab != 0) {
            tab = 0;
            if (listener != null)
                listener.onBack();
            invalidate();
        } else if (listener != null) {
            listener.onExit();
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                left();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                right();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                up();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                down();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_PAGE_UP:
                changeTab(-1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                changeTab(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (event.getRepeatCount() == 0) {
                    if (focusTop) {
                        if (count() > 0) {
                            focusTop = false;
                            if (listener != null)
                                listener.onSelect();
                            invalidate();
                        }
                    } else {
                        activate();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BACK:
                if (event.getRepeatCount() == 0)
                    handleBack();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && event.getAction() == MotionEvent.ACTION_MOVE) {
            float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (x == 0 && y == 0) {
                x = event.getAxisValue(MotionEvent.AXIS_X);
                y = event.getAxisValue(MotionEvent.AXIS_Y);
            }
            long now = event.getEventTime();
            if (Math.abs(x) > 0.6f || Math.abs(y) > 0.6f) {
                if (now - lastAxisMove > 190) {
                    lastAxisMove = now;
                    if (Math.abs(x) > Math.abs(y)) {
                        if (x > 0)
                            right();
                        else
                            left();
                    } else if (y > 0) {
                        down();
                    } else {
                        up();
                    }
                }
            } else {
                lastAxisMove = 0;
            }
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    // ---------------------------------------------------------------- touch

    private float iconX(int i) {
        return 110 * u + i * 150 * u;
    }

    private float iconY() {
        return 120 * u;
    }

    private float tilePitch() {
        return 276 * u;
    }

    private float tileCenterX(int i) {
        float d = i - scrollPos;
        return getWidth() * 0.24f + d * tilePitch() + 45 * u * Math.max(-1f, Math.min(1f, d));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastDragX = e.getX();
                downY = lastDragY = e.getY();
                dragging = false;
                dragAccum = 0;
                return true;
            case MotionEvent.ACTION_MOVE: {
                boolean games = isGames() && !focusTop;
                float d = games ? e.getX() - lastDragX : e.getY() - lastDragY;
                lastDragX = e.getX();
                lastDragY = e.getY();
                if (!dragging && Math.hypot(e.getX() - downX, e.getY() - downY) > 28 * u)
                    dragging = true;
                if (dragging && e.getY() > iconY() + 80 * u && count() > 0) {
                    dragAccum += d;
                    float step = games ? tilePitch() * 0.6f : 90 * u;
                    while (dragAccum <= -step) {
                        dragAccum += step;
                        focusTop = false;
                        moveSel(1);
                    }
                    while (dragAccum >= step) {
                        dragAccum -= step;
                        moveSel(-1);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!dragging)
                    tap(e.getX(), e.getY());
                return true;
            default:
                return true;
        }
    }

    private void tap(float x, float y) {
        // top bar
        if (y < iconY() + 70 * u) {
            for (int i = 0; i < tabs.size(); i++) {
                if (Math.abs(x - iconX(i)) < 70 * u) {
                    focusTop = true;
                    if (i != tab)
                        gotoTab(i);
                    else
                        invalidate();
                    return;
                }
            }
            return;
        }
        Tab t = cur();
        if (t == null)
            return;
        if (isGames()) {
            for (int i = 0; i < t.items.size(); i++) {
                float cx = tileCenterX(i);
                float half = (i == sel[tab] ? 165 : 120) * u;
                if (Math.abs(x - cx) < half && Math.abs(y - getHeight() * 0.5f) < half + 30 * u) {
                    focusTop = false;
                    if (i == sel[tab]) {
                        activate();
                    } else {
                        sel[tab] = i;
                        moved();
                    }
                    return;
                }
            }
        } else {
            float top = 300 * u;
            float row = 96 * u;
            if (x < getWidth() * 0.52f && y > top) {
                int i = (int) ((y - top) / row + listScroll);
                if (i >= 0 && i < t.items.size()) {
                    focusTop = false;
                    if (i == sel[tab]) {
                        activate();
                    } else {
                        sel[tab] = i;
                        moved();
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    private void stepAnimation(float dt) {
        time += dt;
        float k = 1f - (float) Math.exp(-dt * 12f);
        tabPos += (tab - tabPos) * k;
        float target = sel.length > 0 && tab < sel.length ? sel[tab] : 0;
        scrollPos += (target - scrollPos) * k;
        // keep the selected row inside the visible window of the list
        float visible = (getHeight() * 0.88f - 300 * u) / (96 * u);
        float wantTop = listScroll;
        if (target < wantTop)
            wantTop = target;
        else if (target > wantTop + visible - 1)
            wantTop = target - visible + 1;
        if (wantTop < 0)
            wantTop = 0;
        if (!isGames())
            listScroll += (wantTop - listScroll) * k;
        else
            listScroll = 0;
        focusAnim += ((focusTop ? 1f : 0f) - focusAnim) * k;
        for (int i = 0; i < PARTICLES; i++) {
            py[i] -= pv[i] * dt;
            if (py[i] < -0.02f) {
                py[i] = 1.02f;
            }
            px[i] += (float) Math.sin(time * 0.4f + i) * 0.0004f;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        shaderH = -1;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0)
            return;
        u = h / 1080f;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        stepAnimation(dt);

        drawBackground(c, w, h);
        drawTopBar(c, w);
        if (isGames())
            drawGames(c, w, h);
        else
            drawList(c, w, h);
        drawFooter(c, w, h);

        postInvalidateOnAnimation();
    }

    private void drawBackground(Canvas c, int w, int h) {
        if (background != null) {
            float sx = w / (float) background.getWidth();
            float sy = h / (float) background.getHeight();
            float s = Math.max(sx, sy);
            float bw = background.getWidth() * s;
            float bh = background.getHeight() * s;
            rect.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
            fill.setShader(null);
            fill.setColor(Color.WHITE);
            c.drawBitmap(background, null, rect, fill);
            fill.setColor(Color.argb(120, 0, 8, 28));
            c.drawRect(0, 0, w, h, fill);
        } else {
            if (skyShader == null || shaderH != h) {
                skyShader = new LinearGradient(0, 0, 0, h,
                        new int[]{0xFF0B2A6B, 0xFF0A1B48, 0xFF050D26},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                shaderH = h;
            }
            fill.setShader(skyShader);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            // slow waves
            for (int k = 0; k < 3; k++) {
                float base = h * (0.55f + 0.12f * k);
                float amp = h * (0.06f - 0.012f * k);
                float freq = (0.0016f + 0.0005f * k) * (1080f / h);
                float speed = 0.35f + 0.18f * k;
                path.reset();
                path.moveTo(0, h);
                for (int x = 0; x <= w + 24; x += 24) {
                    float y = base + amp * (float) Math.sin(x * freq + time * speed + k * 2.1f)
                            + amp * 0.5f * (float) Math.sin(x * freq * 1.9f - time * speed * 0.7f);
                    path.lineTo(x, y);
                }
                path.lineTo(w + 24, h);
                path.close();
                fill.setColor(Color.argb(34 - k * 7, 110, 170, 255));
                c.drawPath(path, fill);
            }
        }
        // drifting dust
        for (int i = 0; i < PARTICLES; i++) {
            int a = (int) (50 + 60 * (0.5f + 0.5f * Math.sin(time + i * 1.7f)));
            fill.setColor(Color.argb(a, 200, 225, 255));
            c.drawCircle(px[i] * w, py[i] * h, ps[i] * u, fill);
        }
    }

    private void drawTopBar(Canvas c, int w) {
        float y = iconY();
        for (int i = 0; i < tabs.size(); i++) {
            float d = Math.abs(i - tabPos);
            float grow = Math.max(0f, 1f - d);
            float r = (34 + 16 * grow) * u;
            float x = iconX(i);
            int alpha = (int) (110 + 145 * grow);
            // disc
            fill.setColor(Color.argb((int) (40 + 60 * grow), 255, 255, 255));
            c.drawCircle(x, y, r, fill);
            if (grow > 0.5f) {
                float ringAlpha = 0.35f + 0.65f * focusAnim;
                stroke.setStrokeWidth(3.5f * u);
                stroke.setColor(Color.argb((int) (255 * ringAlpha * grow), 255, 255, 255));
                c.drawCircle(x, y, r + 7 * u, stroke);
                if (focusAnim > 0.3f) {
                    stroke.setStrokeWidth(2f * u);
                    stroke.setColor(Color.argb((int) (70 * focusAnim * grow), 255, 255, 255));
                    c.drawCircle(x, y, r + 16 * u, stroke);
                }
            }
            drawGlyph(c, tabs.get(i).icon, x, y, r * 0.55f, Color.argb(alpha, 255, 255, 255));
        }
        // title of the selected section
        Tab t = cur();
        if (t != null) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(34 * u);
            text.setColor(Color.argb((int) (170 + 85 * focusAnim), 255, 255, 255));
            c.drawText(t.title, 110 * u - 34 * u, y + 110 * u, text);
        }
        // clock
        long now = System.currentTimeMillis();
        if (now - clockStamp > 1000) {
            clock = clockFormat.format(new Date(now));
            clockStamp = now;
        }
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(40 * u);
        text.setColor(Color.argb(230, 255, 255, 255));
        c.drawText(clock, w - 70 * u, y + 14 * u, text);
    }

    private void drawGlyph(Canvas c, int icon, float cx, float cy, float s, int color) {
        fill.setColor(color);
        stroke.setColor(color);
        stroke.setStrokeWidth(Math.max(2f, s * 0.16f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        switch (icon) {
            case ICON_GAMES: {
                rect.set(cx - s * 1.15f, cy - s * 0.6f, cx + s * 1.15f, cy + s * 0.6f);
                c.drawRoundRect(rect, s * 0.5f, s * 0.5f, stroke);
                c.drawLine(cx - s * 0.62f, cy - s * 0.25f, cx - s * 0.62f, cy + s * 0.25f, stroke);
                c.drawLine(cx - s * 0.87f, cy, cx - s * 0.37f, cy, stroke);
                c.drawCircle(cx + s * 0.45f, cy + s * 0.12f, s * 0.12f, fill);
                c.drawCircle(cx + s * 0.75f, cy - s * 0.12f, s * 0.12f, fill);
                break;
            }
            case ICON_ADD: {
                c.drawLine(cx - s * 0.75f, cy, cx + s * 0.75f, cy, stroke);
                c.drawLine(cx, cy - s * 0.75f, cx, cy + s * 0.75f, stroke);
                break;
            }
            case ICON_FILES: {
                path.reset();
                path.moveTo(cx - s, cy - s * 0.55f);
                path.lineTo(cx - s * 0.25f, cy - s * 0.55f);
                path.lineTo(cx + s * 0.05f, cy - s * 0.25f);
                path.lineTo(cx + s, cy - s * 0.25f);
                path.lineTo(cx + s, cy + s * 0.6f);
                path.lineTo(cx - s, cy + s * 0.6f);
                path.close();
                stroke.setStrokeJoin(Paint.Join.ROUND);
                c.drawPath(path, stroke);
                break;
            }
            case ICON_SETTINGS: {
                c.drawCircle(cx, cy, s * 0.5f, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    float x1 = cx + (float) Math.cos(a) * s * 0.72f;
                    float y1 = cy + (float) Math.sin(a) * s * 0.72f;
                    float x2 = cx + (float) Math.cos(a) * s * 1.0f;
                    float y2 = cy + (float) Math.sin(a) * s * 1.0f;
                    c.drawLine(x1, y1, x2, y2, stroke);
                }
                break;
            }
            default: {
                rect.set(cx - s * 1.05f, cy - s * 0.7f, cx + s * 1.05f, cy + s * 0.55f);
                c.drawRoundRect(rect, s * 0.18f, s * 0.18f, stroke);
                c.drawLine(cx - s * 0.5f, cy + s * 0.85f, cx + s * 0.5f, cy + s * 0.85f, stroke);
                path.reset();
                path.moveTo(cx - s * 0.22f, cy - s * 0.38f);
                path.lineTo(cx + s * 0.38f, cy - s * 0.07f);
                path.lineTo(cx - s * 0.22f, cy + s * 0.24f);
                path.close();
                c.drawPath(path, fill);
                break;
            }
        }
    }

    // ---- games row

    private void drawGames(Canvas c, int w, int h) {
        Tab t = cur();
        if (t == null)
            return;
        if (t.items.isEmpty()) {
            drawCentered(c, t.emptyText, w, h);
            return;
        }
        float cy = h * 0.52f;
        float base = 240 * u;
        int n = t.items.size();
        int first = Math.max(0, (int) Math.floor(scrollPos) - 6);
        int last = Math.min(n - 1, (int) Math.ceil(scrollPos) + 8);
        // draw far items first, selected item last
        for (int pass = 0; pass < 2; pass++) {
            for (int i = first; i <= last; i++) {
                float d = i - scrollPos;
                float grow = Math.max(0f, 1f - Math.abs(d));
                boolean top = grow > 0.5f;
                if ((pass == 1) != top)
                    continue;
                float size = base * (1f + 0.38f * grow);
                float cx = tileCenterX(i);
                if (cx + size < 0 || cx - size > w)
                    continue;
                drawTile(c, t.items.get(i), cx, cy, size, grow);
            }
        }
        // title of the selected game
        Item it = t.items.get(Math.min(sel[tab], n - 1));
        float size = base * 1.38f;
        float tx = tileCenterX(sel[tab]) - size / 2;
        float ty = cy + size / 2 + 30 * u;
        int tw = (int) (900 * u);
        if (it.titleLayout == null || it.layoutWidth != tw) {
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            tp.setTextSize(40 * u);
            tp.setColor(Color.WHITE);
            it.titleLayout = new StaticLayout(TextUtils.ellipsize(it.title, tp, tw * 1.8f, TextUtils.TruncateAt.END),
                    tp, tw, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
            it.layoutWidth = tw;
        }
        int a = (int) (255 * (1f - focusAnim * 0.45f));
        it.titleLayout.getPaint().setAlpha(a);
        c.save();
        c.translate(tx, ty);
        it.titleLayout.draw(c);
        c.restore();
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(28 * u);
        text.setColor(Color.argb(170, 190, 215, 255));
        c.drawText(it.desc, tx, ty + it.titleLayout.getHeight() + 38 * u, text);
        // counter
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(Color.argb(150, 200, 220, 255));
        c.drawText((sel[tab] + 1) + " / " + n, w - 70 * u, h * 0.2f + 30 * u, text);
    }

    private void drawTile(Canvas c, Item it, float cx, float cy, float size, float grow) {
        float half = size / 2;
        float rad = 14 * u;
        rect.set(cx - half, cy - half, cx + half, cy + half);
        if (grow > 0.02f) {
            // soft glow made of a few translucent rounded rects
            for (int k = 3; k >= 1; k--) {
                float e = k * 9 * u * grow;
                fill.setColor(Color.argb((int) (28 * grow), 170, 210, 255));
                RectF g = new RectF(rect.left - e, rect.top - e, rect.right + e, rect.bottom + e);
                c.drawRoundRect(g, rad + e, rad + e, fill);
            }
        }
        // cover
        Bitmap bmp = coverFor(it);
        c.save();
        path.reset();
        path.addRoundRect(rect, rad, rad, Path.Direction.CW);
        c.clipPath(path);
        if (bmp != null) {
            float sw = bmp.getWidth(), sh = bmp.getHeight();
            float s = Math.max(size / sw, size / sh);
            float cw = size / s, ch = size / s;
            android.graphics.Rect src = new android.graphics.Rect(
                    (int) ((sw - cw) / 2), (int) ((sh - ch) / 2), (int) ((sw + cw) / 2), (int) ((sh + ch) / 2));
            fill.setColor(Color.WHITE);
            fill.setShader(null);
            c.drawBitmap(bmp, src, rect, fill);
        } else {
            drawGeneratedCover(c, it, cx, cy, size);
        }
        // dim the tiles that are not selected
        float dim = 1f - grow;
        fill.setColor(Color.argb((int) (95 * dim), 0, 5, 20));
        c.drawRect(rect, fill);
        c.restore();
        if (grow > 0.02f) {
            stroke.setStrokeWidth(4 * u);
            stroke.setColor(Color.argb((int) (255 * grow * (1f - 0.4f * focusAnim)), 255, 255, 255));
            c.drawRoundRect(rect, rad, rad, stroke);
        }
    }

    private void drawGeneratedCover(Canvas c, Item it, float cx, float cy, float size) {
        float half = size / 2;
        int hue = (it.title.hashCode() & 0x7fffffff) % 360;
        float[] hsv = {hue, 0.55f, 0.55f};
        int top = Color.HSVToColor(hsv);
        hsv[1] = 0.65f;
        hsv[2] = 0.28f;
        int bottom = Color.HSVToColor(hsv);
        fill.setShader(new LinearGradient(0, cy - half, 0, cy + half, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(cx - half, cy - half, cx + half, cy + half, fill);
        fill.setShader(null);
        // disc motif
        fill.setColor(Color.argb(40, 255, 255, 255));
        c.drawCircle(cx, cy - size * 0.06f, size * 0.36f, fill);
        fill.setColor(Color.argb(50, 0, 0, 20));
        c.drawCircle(cx, cy - size * 0.06f, size * 0.09f, fill);
        stroke.setStrokeWidth(2f * u);
        stroke.setColor(Color.argb(70, 255, 255, 255));
        c.drawCircle(cx, cy - size * 0.06f, size * 0.36f, stroke);
        // initials
        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size * 0.26f);
        text.setColor(Color.argb(235, 255, 255, 255));
        c.drawText(initials(it.title), cx, cy - size * 0.06f + size * 0.09f, text);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        // short name at the bottom
        text.setTextSize(size * 0.085f);
        text.setColor(Color.argb(210, 255, 255, 255));
        CharSequence label = TextUtils.ellipsize(it.title, text, size * 0.86f, TextUtils.TruncateAt.END);
        c.drawText(label, 0, label.length(), cx, cy + half - size * 0.07f, text);
    }

    private static String initials(String title) {
        StringBuilder sb = new StringBuilder();
        for (String part : title.split("[\\s_\\-.]+")) {
            if (part.isEmpty())
                continue;
            int cp = part.codePointAt(0);
            if (Character.isLetterOrDigit(cp))
                sb.appendCodePoint(Character.toUpperCase(cp));
            if (sb.length() >= 2)
                break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private Bitmap coverFor(final Item it) {
        final String path = it.cover;
        if (path == null || failed.contains(path))
            return null;
        Bitmap b = covers.get(path);
        if (b != null)
            return b;
        if (pending.add(path)) {
            decoder.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap bmp = null;
                    try {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(path, o);
                        int sample = 1;
                        while (o.outWidth / (sample * 2) >= 512 && o.outHeight / (sample * 2) >= 512)
                            sample *= 2;
                        BitmapFactory.Options o2 = new BitmapFactory.Options();
                        o2.inSampleSize = sample;
                        bmp = BitmapFactory.decodeFile(path, o2);
                    } catch (Throwable ignored) {
                    }
                    final Bitmap result = bmp;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pending.remove(path);
                            if (result != null)
                                covers.put(path, result);
                            else
                                failed.add(path);
                        }
                    });
                }
            });
        }
        return null;
    }

    // ---- list sections

    private void drawList(Canvas c, int w, int h) {
        Tab t = cur();
        if (t == null)
            return;
        if (t.items.isEmpty()) {
            drawCentered(c, t.emptyText, w, h);
            return;
        }
        float left = 110 * u - 34 * u;
        float top = 300 * u;
        float row = 96 * u;
        float width = w * 0.46f;
        float bottom = h * 0.88f;
        c.save();
        c.clipRect(0, top - 10 * u, w, bottom);
        for (int i = 0; i < t.items.size(); i++) {
            float y = top + (i - listScroll) * row;
            if (y + row < top - row || y > bottom)
                continue;
            boolean on = i == sel[tab];
            if (on) {
                rect.set(left, y, left + width, y + row - 10 * u);
                fill.setColor(Color.argb((int) (70 - 30 * focusAnim), 255, 255, 255));
                c.drawRoundRect(rect, 12 * u, 12 * u, fill);
                stroke.setStrokeWidth(3 * u);
                stroke.setColor(Color.argb((int) (255 - 120 * focusAnim), 255, 255, 255));
                c.drawRoundRect(rect, 12 * u, 12 * u, stroke);
            }
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(36 * u);
            text.setColor(Color.argb(on ? 255 : 170, 255, 255, 255));
            CharSequence label = TextUtils.ellipsize(t.items.get(i).title, text, width - 50 * u, TextUtils.TruncateAt.END);
            c.drawText(label, 0, label.length(), left + 26 * u, y + row / 2 + 8 * u, text);
        }
        c.restore();

        // description panel
        Item it = t.items.get(Math.min(sel[tab], t.items.size() - 1));
        float px0 = w * 0.57f;
        rect.set(px0 - 30 * u, top, w - 70 * u, top + 460 * u);
        fill.setColor(Color.argb(40, 255, 255, 255));
        c.drawRoundRect(rect, 18 * u, 18 * u, fill);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(44 * u);
        text.setColor(Color.WHITE);
        CharSequence title = TextUtils.ellipsize(it.title, text, w - 70 * u - px0 - 20 * u, TextUtils.TruncateAt.END);
        c.drawText(title, 0, title.length(), px0, top + 70 * u, text);
        int dw = (int) (w - 70 * u - px0 - 20 * u);
        if (dw > 50) {
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            tp.setTextSize(31 * u);
            tp.setColor(Color.argb(215, 215, 230, 255));
            StaticLayout sl = new StaticLayout(it.desc == null ? "" : it.desc, tp, dw,
                    Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false);
            c.save();
            c.translate(px0, top + 110 * u);
            sl.draw(c);
            c.restore();
        }
    }

    private void drawCentered(Canvas c, String msg, int w, int h) {
        if (msg == null || msg.isEmpty())
            return;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tp.setTextSize(38 * u);
        tp.setColor(Color.argb(220, 255, 255, 255));
        int width = (int) (Math.min(w * 0.8f, 1100 * u));
        StaticLayout sl = new StaticLayout(msg, tp, width, Layout.Alignment.ALIGN_CENTER, 1.2f, 0f, false);
        c.save();
        c.translate((w - width) / 2f, h * 0.5f - sl.getHeight() / 2f);
        sl.draw(c);
        c.restore();
    }

    private void drawFooter(Canvas c, int w, int h) {
        float y = h - 60 * u;
        float x = w - 70 * u;
        x = hint(c, x, y, "B", hintBack);
        x = hint(c, x - 44 * u, y, "A", hintSelect);
        hint(c, x - 44 * u, y, "L1/R1", hintTabs);
    }

    /** Draws "[key] label" right-aligned at x and returns the left edge. */
    private float hint(Canvas c, float xRight, float y, String key, String label) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(28 * u);
        float lw = text.measureText(label);
        text.setTextSize(24 * u);
        float kw = Math.max(40 * u, text.measureText(key) + 24 * u);
        float x0 = xRight - lw - 14 * u - kw;
        rect.set(x0, y - 30 * u, x0 + kw, y + 10 * u);
        stroke.setStrokeWidth(2.5f * u);
        stroke.setColor(Color.argb(200, 255, 255, 255));
        c.drawRoundRect(rect, 20 * u, 20 * u, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.argb(230, 255, 255, 255));
        c.drawText(key, x0 + kw / 2, y - 1 * u, text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(28 * u);
        text.setColor(Color.argb(200, 220, 232, 255));
        c.drawText(label, x0 + kw + 14 * u, y, text);
        return x0;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        decoder.shutdownNow();
    }
}

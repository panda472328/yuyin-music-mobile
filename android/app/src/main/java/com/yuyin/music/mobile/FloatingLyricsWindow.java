package com.yuyin.music.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import com.getcapacitor.JSObject;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Two small transparent native windows: locked text is touch-through, unlock stays clickable. */
final class FloatingLyricsWindow {
    private final Context context;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Supplier<JSObject> status;
    private final Consumer<String> transport;
    private JSONObject settings;
    private LyricsView text;
    private LinearLayout toolbar;
    private WindowManager.LayoutParams textParams, toolbarParams;
    private JSONArray lines = new JSONArray();
    private String bvid = "", fallback = "";
    private double offset;
    private String currentLine = "", nextLine = "";
    private boolean destroyed;
    private View toolbarHost;
    private final Runnable hideTools = () -> { if (toolbarHost != null && !settings.optBoolean("locked")) toolbarHost.setVisibility(View.GONE); };

    FloatingLyricsWindow(Context context, Supplier<JSObject> status, Consumer<String> transport) {
        this.context = context; this.status = status; this.transport = transport;
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE); refresh();
    }
    static boolean permission(Context context) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context); }
    static JSONObject settings(Context context) {
        try { return normalize(new JSONObject(PlaybackStore.get(context).getString("floating_lyrics", "{}"))); }
        catch (Exception ignored) { return normalize(new JSONObject()); }
    }
    static JSONObject normalize(JSONObject raw) {
        JSONObject result = new JSONObject();
        try {
            result.put("enabled", raw.opt("enabled") instanceof Boolean && raw.optBoolean("enabled"));
            result.put("locked", raw.opt("locked") instanceof Boolean && raw.optBoolean("locked"));
            String font = raw.optString("font", "sans"), color = raw.optString("color", "white");
            result.put("font", java.util.Arrays.asList("sans", "serif", "kai", "rounded").contains(font) ? font : "sans");
            result.put("color", java.util.Arrays.asList("white", "mint", "gold").contains(color) ? color : "white");
            result.put("fontSize", numeric(raw.opt("fontSize"), 24, 16, 40));
            result.put("opacity", numeric(raw.opt("opacity"), 1, .3, 1));
            result.put("x", numeric(raw.opt("x"), .5, 0, 1)); result.put("y", numeric(raw.opt("y"), .2, 0, 1));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return result;
    }
    private static double numeric(Object value, double fallback, double min, double max) { if (!(value instanceof Number)) return fallback; double number = ((Number) value).doubleValue(); return Double.isFinite(number) ? Math.max(min, Math.min(max, number)) : fallback; }
    static JSObject state(Context context) { try { JSObject value = new JSObject(settings(context).toString()); value.put("permission", permission(context)); return value; } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    static boolean save(Context context, JSONObject value) { return PlaybackStore.get(context).edit().putString("floating_lyrics", normalize(value).toString()).commit(); }

    void refresh() { settings = settings(context); removeWindows(); update(status.get()); }
    void setLines(String songBvid, JSONArray value, double shift, String plain) throws Exception {
        if (!NativePolicy.validBvid(songBvid) || value.length() > 10000 || !Double.isFinite(shift) || shift < -120 || shift > 120 || plain.length() > 100000) throw new IllegalArgumentException("INVALID_FLOATING_LYRICS");
        JSONArray bounded = new JSONArray(); double previous = -86400;
        for (int i = 0; i < value.length(); i++) {
            JSONObject line = value.getJSONObject(i); Object time = line.opt("time");
            if (!(time instanceof Number) || !Double.isFinite(((Number) time).doubleValue()) || ((Number) time).doubleValue() < previous || ((Number) time).doubleValue() > 86400 || !(line.opt("text") instanceof String) || line.getString("text").length() > 2000) throw new IllegalArgumentException("INVALID_FLOATING_LYRICS");
            previous = ((Number) time).doubleValue(); bounded.put(new JSONObject().put("time", previous).put("text", line.getString("text")));
        }
        bvid = songBvid; lines = bounded; offset = shift; fallback = plain; update(status.get());
    }
    void update(JSObject playback) {
        if (destroyed) return;
        JSONObject song = playback.optJSONObject("song");
        if (!settings.optBoolean("enabled") || !permission(context) || song == null) { removeWindows(); return; }
        String title = song.optString("title", "open音乐"); currentLine = title; nextLine = "";
        if (song.optString("bvid").equals(bvid)) {
            int active = -1; double clock = playback.optDouble("currentTime") - offset;
            for (int i = 0; i < lines.length(); i++) { if (lines.optJSONObject(i).optDouble("time") <= clock) active = i; else break; }
            if (active >= 0) { currentLine = lines.optJSONObject(active).optString("text"); if (active + 1 < lines.length()) nextLine = lines.optJSONObject(active + 1).optString("text"); }
            else if (lines.length() > 0) nextLine = lines.optJSONObject(0).optString("text");
            else if (!fallback.trim().isEmpty()) { String[] plain = fallback.split("\n"); currentLine = plain[0]; nextLine = "纯文本歌词，无时间轴"; }
            else nextLine = "暂无对应歌词";
        } else nextLine = "正在读取歌词…";
        try { if (text == null) createWindows(); text.setContentDescription(currentLine + " " + nextLine); text.invalidate(); layout(); }
        catch (RuntimeException denied) { removeWindows(); }
    }
    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private int textWidth(Point screen) {
        Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG); measure.setTypeface(Typeface.DEFAULT_BOLD);
        measure.setTextSize((float) settings.optDouble("fontSize", 24) * context.getResources().getDisplayMetrics().scaledDensity);
        return Math.min(screen.x - dp(16), Math.max(dp(180), Math.min(dp(640), (int) Math.ceil(Math.max(measure.measureText(currentLine), measure.measureText(nextLine) * .76f)) + dp(32))));
    }
    private Point screen() { Point size = new Point(); windows.getDefaultDisplay().getSize(size); return size; }
    private WindowManager.LayoutParams params(int width, int height, boolean through) {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams value = new WindowManager.LayoutParams(width, height, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | (through ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0), PixelFormat.TRANSLUCENT);
        value.gravity = Gravity.TOP | Gravity.LEFT; value.alpha = (float) settings.optDouble("opacity", 1);
        // Android 12+ blocks touches through an untrusted overlay above the obscuring limit.
        if (through && Build.VERSION.SDK_INT >= 31) value.alpha = Math.min(value.alpha, ((android.hardware.input.InputManager) context.getSystemService(Context.INPUT_SERVICE)).getMaximumObscuringOpacityForTouch());
        return value;
    }
    private void createWindows() {
        Point size = screen();
        text = new LyricsView(); textParams = params(textWidth(size), Math.round((float) settings.optDouble("fontSize", 24) * context.getResources().getDisplayMetrics().scaledDensity * 2.8f) + dp(12), settings.optBoolean("locked"));
        windows.addView(text, textParams);
        toolbar = new LinearLayout(context); toolbar.setOrientation(LinearLayout.HORIZONTAL); toolbar.setGravity(Gravity.CENTER); toolbar.setBackgroundColor(Color.TRANSPARENT);
        if (!settings.optBoolean("locked")) {
            button("previous", "上一首", () -> transport.accept("previous")); button("pause", "播放或暂停", () -> transport.accept("pause")); button("next", "下一首", () -> transport.accept("next"));
            button("font", "切换歌词字体", () -> cycle("font", new String[] {"sans", "serif", "kai", "rounded"}));
            button("size", "切换歌词字号", () -> change("fontSize", settings.optDouble("fontSize") >= 40 ? 16 : settings.optDouble("fontSize") + 2));
            button("color", "切换歌词颜色", () -> cycle("color", new String[] {"white", "mint", "gold"}));
        }
        button("lock", settings.optBoolean("locked") ? "解锁悬浮歌词" : "锁定悬浮歌词", () -> change("locked", !settings.optBoolean("locked")));
        if (!settings.optBoolean("locked")) {
            button("opacity", "切换歌词透明度", () -> change("opacity", settings.optDouble("opacity") <= .31 ? 1 : settings.optDouble("opacity") - .1));
            button("close", "关闭悬浮歌词", () -> change("enabled", false));
        }
        // On narrow phones the unlocked bar scrolls; the locked window is only the central lock.
        int toolbarWidth = settings.optBoolean("locked") ? dp(44) : Math.min(size.x - dp(8), dp(396));
        if (toolbarWidth < dp(396) && !settings.optBoolean("locked")) {
            // Two rows would shift lyrics; use compact icons with 44dp touch regions in a horizontal scroller.
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(context); scroll.setHorizontalScrollBarEnabled(false); scroll.setFillViewport(false); scroll.addView(toolbar); toolbarHost = scroll;
        } else toolbarHost = toolbar;
        toolbarParams = params(toolbarWidth, dp(44), false); toolbarParams.alpha = 1;
        windows.addView(toolbarHost, toolbarParams); revealTools();
    }
    private void layout() {
        if (text == null) return;
        Point size = screen(); textParams.width = textWidth(size);
        textParams.x = (int) ((size.x - textParams.width) * settings.optDouble("x", .5));
        int maximumY = Math.max(0, size.y - textParams.height - dp(44)); textParams.y = (int) (maximumY * settings.optDouble("y", .2)) + dp(44);
        toolbarParams.x = Math.max(0, Math.min(size.x - toolbarParams.width, textParams.x + (textParams.width - toolbarParams.width) / 2));
        toolbarParams.y = Math.max(0, textParams.y - dp(44));
        windows.updateViewLayout(text, textParams); windows.updateViewLayout(toolbarHost, toolbarParams);
    }
    private void button(String icon, String label, Runnable click) {
        IconView button = new IconView(icon); button.setContentDescription(label); button.setFocusable(true); button.setOnClickListener(view -> { click.run(); revealTools(); });
        toolbar.addView(button, new LinearLayout.LayoutParams(dp(44), dp(44)));
    }
    private void revealTools() { main.removeCallbacks(hideTools); if (toolbarHost != null) { toolbarHost.setVisibility(View.VISIBLE); toolbar.setVisibility(View.VISIBLE); } if (!settings.optBoolean("locked")) main.postDelayed(hideTools, 3000); }
    private void cycle(String key, String[] values) { int index = java.util.Arrays.asList(values).indexOf(settings.optString(key)); change(key, values[(index + 1) % values.length]); }
    private void change(String key, Object value) {
        try { JSONObject next = new JSONObject(settings.toString()).put(key, value); if (save(context, next)) refresh(); }
        catch (Exception ignored) { /* A failed preference write keeps the displayed setting. */ }
    }
    private void removeWindows() {
        main.removeCallbacks(hideTools);
        if (text != null) { try { windows.removeView(text); } catch (RuntimeException ignored) {} text = null; }
        if (toolbarHost != null) { try { windows.removeView(toolbarHost); } catch (RuntimeException ignored) {} toolbarHost = null; toolbar = null; }
    }
    void destroy() { destroyed = true; removeWindows(); }

    private final class LyricsView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); float startX, startY; int originalX, originalY; boolean moved;
        LyricsView() { super(context); setBackgroundColor(Color.TRANSPARENT); setFocusable(true); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float scaled = context.getResources().getDisplayMetrics().scaledDensity;
            String font = settings.optString("font"); paint.setTypeface("serif".equals(font) ? Typeface.create("serif", Typeface.BOLD) : "kai".equals(font) ? Typeface.create("serif", Typeface.ITALIC) : "rounded".equals(font) ? Typeface.create("sans-serif-rounded", Typeface.BOLD) : Typeface.create("sans-serif", Typeface.BOLD));
            draw(canvas, currentLine, (float) settings.optDouble("fontSize", 24) * scaled, getHeight() * .40f, true);
            draw(canvas, nextLine, (float) settings.optDouble("fontSize", 24) * scaled * .76f, getHeight() * .82f, false);
        }
        private void draw(Canvas canvas, String value, float size, float y, boolean active) {
            paint.setTextSize(size); if (paint.measureText(value) > getWidth() - dp(16)) paint.setTextSize(size * (getWidth() - dp(16)) / paint.measureText(value));
            paint.setTextAlign(Paint.Align.CENTER); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(Color.argb(220, 10, 15, 18)); canvas.drawText(value, getWidth() / 2f, y, paint);
            paint.setStyle(Paint.Style.FILL); paint.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
            String color = settings.optString("color"); paint.setColor(active ? "mint".equals(color) ? Color.rgb(191, 255, 222) : "gold".equals(color) ? Color.rgb(255, 226, 156) : Color.WHITE : Color.rgb(220, 225, 227)); canvas.drawText(value, getWidth() / 2f, y, paint); paint.clearShadowLayer();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (settings.optBoolean("locked")) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) { startX = event.getRawX(); startY = event.getRawY(); originalX = textParams.x; originalY = textParams.y; moved = false; revealTools(); return true; }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                if (Math.abs(dx) + Math.abs(dy) > dp(5)) moved = true;
                Point size = screen(); int maxX = Math.max(0, size.x - textParams.width), maxY = Math.max(1, size.y - textParams.height - dp(44));
                try { settings.put("x", maxX == 0 ? .5 : Math.max(0, Math.min(maxX, originalX + dx)) / maxX); settings.put("y", Math.max(0, Math.min(maxY, originalY + dy - dp(44))) / maxY); FloatingLyricsWindow.this.layout(); } catch (Exception ignored) {}
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) { if (moved) save(context, settings); else performClick(); revealTools(); return true; }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); revealTools(); return true; }
    }
    private final class IconView extends View {
        final String icon; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        IconView(String icon) { super(context); this.icon = icon; setBackgroundColor(Color.TRANSPARENT); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); canvas.translate(getWidth() / 2f, getHeight() / 2f); float s = dp(1); canvas.scale(s, s);
            paint.setColor(isPressed() || isFocused() ? Color.WHITE : Color.rgb(229, 233, 235)); paint.setStrokeWidth(2); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setShadowLayer(2, 0, 1, Color.BLACK);
            if ("lock".equals(icon)) { canvas.drawRect(-7, -2, 7, 9, paint); canvas.drawArc(-5, -10, 5, 4, 180, 180, false, paint); }
            else if ("close".equals(icon)) { canvas.drawLine(-7, -7, 7, 7, paint); canvas.drawLine(7, -7, -7, 7, paint); }
            else if ("pause".equals(icon)) { if ("playing".equals(status.get().optString("state"))) { canvas.drawLine(-4, -7, -4, 7, paint); canvas.drawLine(4, -7, 4, 7, paint); } else { android.graphics.Path p = new android.graphics.Path(); p.moveTo(-5, -8); p.lineTo(8, 0); p.lineTo(-5, 8); p.close(); canvas.drawPath(p, paint); } }
            else if ("next".equals(icon) || "previous".equals(icon)) { if ("previous".equals(icon)) canvas.scale(-1, 1); android.graphics.Path p = new android.graphics.Path(); p.moveTo(-7, -7); p.lineTo(5, 0); p.lineTo(-7, 7); p.close(); canvas.drawPath(p, paint); canvas.drawLine(8, -7, 8, 7, paint); }
            else if ("color".equals(icon)) { canvas.drawCircle(0, 0, 9, paint); canvas.drawCircle(-3, -3, 1, paint); canvas.drawCircle(4, -1, 1, paint); }
            else if ("opacity".equals(icon)) { canvas.drawCircle(0, 0, 9, paint); paint.setStyle(Paint.Style.FILL); canvas.drawArc(-9, -9, 9, 9, 90, 180, true, paint); }
            else { paint.setStyle(Paint.Style.FILL); paint.setTextSize("size".equals(icon) ? 19 : 16); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextAlign(Paint.Align.CENTER); canvas.drawText("size".equals(icon) ? "A" : "Aa", 0, 6, paint); }
            canvas.restore();
        }
    }
}

package com.yuyin.music.mobile;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Android window flags with inert WebView assets; no store, profile or network access. */
@RunWith(AndroidJUnit4.class)
public class ControlledAppearanceTest {
    private static final class Result extends PluginCall {
        final CompletableFuture<JSObject> result = new CompletableFuture<>();
        Result(String theme) { super(null, "YuyinMobile", "controlled-appearance", "setAppearance", data(theme)); }
        static JSObject data(String theme) { JSObject data = new JSObject(); data.put("theme", theme); return data; }
        @Override public void resolve(JSObject value) { result.complete(value); }
        @Override public void reject(String message, String code) { result.completeExceptionally(new IllegalArgumentException(code)); }
    }
    @Test public void themeColoursAndSystemIconFlagsMatchActualAndroidScheme() throws Exception {
        Assume.assumeTrue("Explicit isolated emulator opt-in required", "true".equals(InstrumentationRegistry.getArguments().getString("yuyinControlledEmulator")));
        try (ActivityScenario<QaPlaybackActivity> scenario = ActivityScenario.launch(QaPlaybackActivity.class)) {
            AtomicReference<YuyinMobilePlugin> selected = new AtomicReference<>();
            scenario.onActivity(activity -> { YuyinMobilePlugin plugin = new YuyinMobilePlugin(); plugin.setBridge(activity.getBridge()); selected.set(plugin); });
            for (String theme : new String[]{"classic", "bilibili", "pixel", "system"}) {
                Result call = new Result(theme);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> selected.get().setAppearance(call));
                boolean dark = call.result.get(5, TimeUnit.SECONDS).optBoolean("dark");
                scenario.onActivity(activity -> {
                    boolean systemDark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                    assertEquals(theme.equals("system") && systemDark, dark);
                    String expected = theme.equals("system") ? dark ? "#17191d" : "#f7f7f7" : theme.equals("bilibili") ? "#f7f8fa" : theme.equals("pixel") ? "#c6c6c6" : "#f7f8f3";
                    assertEquals(Color.parseColor(expected), activity.getWindow().getStatusBarColor());
                    int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
                    assertEquals(!dark, (flags & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0);
                    if (Build.VERSION.SDK_INT >= 26) {
                        assertEquals(Color.parseColor(expected), activity.getWindow().getNavigationBarColor());
                        assertEquals(!dark, (flags & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0);
                    }
                });
            }
        }
    }
}

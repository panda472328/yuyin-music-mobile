package com.yuyin.music.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Debug-only instrumentation host; never included in the release APK. No account or library UI. */
public final class QaPlaybackActivity extends Activity {
    FrameLayout root;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        setContentView(root);
    }
    @Override protected void onDestroy() {
        PlaybackService service = PlaybackService.current();
        if (service != null) service.detachActivity(this);
        super.onDestroy();
    }
}

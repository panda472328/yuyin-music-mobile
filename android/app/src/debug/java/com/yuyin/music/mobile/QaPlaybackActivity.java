package com.yuyin.music.mobile;

import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import com.getcapacitor.ServerPath;
import java.io.ByteArrayInputStream;

/** Production Capacitor lifecycle with an inert UI, only in debug. No account or library React code. */
public final class QaPlaybackActivity extends MainActivity {
    FrameLayout root;
    @Override protected void load() {
        // Select debug-only inert assets BEFORE Bridge creates or navigates any WebView. This avoids
        // relying on stopLoading to win a race against React executing in Chromium's renderer process.
        bridgeBuilder.setServerPath(new ServerPath(ServerPath.PathType.ASSET_PATH, "qa-playback"));
        super.load();
        // Keep the real Bridge and plugin lifecycle, while blocking every outgoing UI request as well.
        WebView ui = bridge.getWebView();
        ui.stopLoading();
        ui.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        ui.loadData("<!doctype html><html><body>Controlled native playback test</body></html>", "text/html", "UTF-8");
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        setContentView(root);
    }
}

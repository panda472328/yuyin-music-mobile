package com.yuyin.music.mobile;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override public void onCreate(Bundle savedInstanceState) {
        registerPlugin(YuyinMobilePlugin.class);
        super.onCreate(savedInstanceState);
    }
}

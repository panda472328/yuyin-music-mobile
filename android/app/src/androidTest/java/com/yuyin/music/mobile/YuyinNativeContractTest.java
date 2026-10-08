package com.yuyin.music.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Package and bridge contract checks. Real Bilibili playback requires separate device acceptance. */
@RunWith(AndroidJUnit4.class)
public class YuyinNativeContractTest {
    @Test public void pluginMethodsRemainAvailableToTheTrustedUi() throws Exception {
        CapacitorPlugin plugin = YuyinMobilePlugin.class.getAnnotation(CapacitorPlugin.class);
        assertNotNull(plugin);
        assertEquals("YuyinMobile", plugin.name());
        String[] methods = {"request", "openLogin", "openSource", "play", "pause", "resume", "seek", "setVolume", "getStatus", "readStore", "writeStore"};
        for (String method : methods) assertTrue(method, YuyinMobilePlugin.class.getDeclaredMethod(method, PluginCall.class).isAnnotationPresent(PluginMethod.class));
    }
    @Test public void foregroundPlayerIsPrivateAndMobileDataHasItsOwnApplicationId() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.yuyin.music.mobile", context.getPackageName());
        ServiceInfo service = context.getPackageManager().getServiceInfo(new ComponentName(context, PlaybackService.class), 0);
        assertFalse(service.exported);
        assertEquals(0, service.flags & ServiceInfo.FLAG_STOP_WITH_TASK);
        if (Build.VERSION.SDK_INT >= 29) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.getForegroundServiceType());
    }
}

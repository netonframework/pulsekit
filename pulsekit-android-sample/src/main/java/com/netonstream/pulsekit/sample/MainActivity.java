package com.netonstream.pulsekit.sample;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import com.netonstream.pulsekit.AppUpdate;
import com.netonstream.pulsekit.PulseSDK;

import java.util.Collections;

/**
 * One screen; what it does comes from the "action" extra, so an end-to-end run is driven by adb:
 *
 *   track         a business event, an error, a performance sample and a behaviour, then flush
 *   update        wait for the update check and record what came back
 *   hang          block the main thread for three seconds (a main_thread_hang)
 *   java_crash    throw from the main thread (recorded, reported on the next launch)
 */
public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("PulseKit sample");
        setContentView(text);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        final String action = intent != null ? intent.getStringExtra("action") : null;
        if (action == null) return;
        // After the activity is on screen, so lifecycle events come first.
        main.postDelayed(new Runnable() {
            @Override public void run() { perform(action); }
        }, 500);
    }

    private void perform(String action) {
        PulseSDK sdk = PulseSDK.shared;
        switch (action) {
            case "track":
                sdk.identify("android-tester");
                sdk.track("purchase", Collections.singletonMap("amount", "199 😀"));
                sdk.recordError("SampleError", "synthetic from the sample", "at MainActivity.perform");
                sdk.recordPerformance("sample_startup", 640);
                sdk.recordBehavior("clipboard_read", "DemoSDK");
                sdk.flush();
                break;
            case "update":
                new Thread(new Runnable() {
                    @Override public void run() {
                        AppUpdate update = PulseSDK.shared.awaitUpdateInfo(5_000);
                        PulseSDK.shared.track("update_seen", Collections.singletonMap("update", update.toString()));
                        PulseSDK.shared.flush();
                    }
                }).start();
                break;
            case "hang":
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException ignored) {
                }
                break;
            case "java_crash":
                throw new IllegalStateException("sample java crash");
            default:
                break;
        }
    }
}

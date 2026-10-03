package com.netonstream.pulsekit.sample;

import android.app.Application;

import com.netonstream.pulsekit.PulseSDK;

/** Start PulseKit where a host should: first thing in Application.onCreate. */
public final class SampleApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PulseSDK.shared.startWithAppId("pulse_android_sample", BuildConfig.PULSE_ENDPOINT);
    }
}

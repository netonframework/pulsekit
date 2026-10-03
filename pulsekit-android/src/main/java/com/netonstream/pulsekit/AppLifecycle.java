package com.netonstream.pulsekit;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import pulse.host.PulseHost;

/**
 * Turns activity callbacks into the two transitions iOS gets from UIApplication:
 * didBecomeActive and didEnterBackground.
 *
 * The app enters the foreground when its first activity starts and becomes active at the next
 * resume — the moment the user can actually use it, which is what the launch time is measured to.
 * It enters the background when its last started activity stops. Moving between the app's own
 * activities, or a configuration change recreating one, is neither: counting started activities
 * rather than resumed ones keeps those from reading as a background-and-return.
 *
 * Callbacks arrive on the main thread, so the counters need no synchronisation.
 */
final class AppLifecycle implements Application.ActivityLifecycleCallbacks {
    private int started;
    private boolean pendingActive;
    /** The last activity stopped only to be recreated; its replacement is not a return. */
    private boolean recreating;

    @Override
    public void onActivityStarted(Activity activity) {
        if (started++ == 0) {
            if (recreating) recreating = false;
            else pendingActive = true;
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (!pendingActive) return;
        pendingActive = false;
        PulseHost.onActive();
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (started > 0) started--;
        if (started != 0) return;
        if (activity.isChangingConfigurations()) {
            recreating = true;
        } else {
            pendingActive = false;
            PulseHost.onBackground();
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}

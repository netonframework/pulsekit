package com.netonstream.pulsekit;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.util.Collections;
import java.util.Map;

import pulse.host.PulseHost;

/**
 * The Android entry point: the same SDK and the same API an iOS host gets as {@code PulseSDK.shared}.
 *
 * <pre>
 * PulseSDK.shared.startWithAppId("pulse_xxxxxxxxxxxxxxxxxxxxxxxx", "tcp://collect.example.com:6000");
 * PulseSDK.shared.identify("100086");
 * PulseSDK.shared.track("purchase", Collections.singletonMap("amount", "199"));
 * </pre>
 *
 * Everything here returns immediately except {@link #flushAndWait}, {@link #stop} and
 * {@link #awaitUpdateInfo}, which block for at most their timeout. The SDK runs on its own thread;
 * events recorded before the connection is up are kept, and a failed upload stays in the on-disk
 * outbox until a later attempt or launch delivers it. No method throws.
 *
 * Call {@code start} once, from {@code Application.onCreate}: launch time, foreground/background
 * and main-thread hangs are observed from the moment the SDK starts. No {@link Context} is passed,
 * so the call is the same as on iOS: {@link PulseInitProvider} hands the application context over
 * when the process starts, before {@code Application.onCreate} runs.
 *
 * This class is Java on purpose, as the iOS SDK's surface is Objective-C: a host compiles against
 * it whatever its own Kotlin version.
 */
public final class PulseSDK {

    /** The SDK, as on iOS. */
    public static final PulseSDK shared = new PulseSDK();

    private static final long DEFAULT_TIMEOUT_MILLIS = 3_000L;
    private static final long DEFAULT_UPDATE_TIMEOUT_MILLIS = 5_000L;

    private boolean lifecycleRegistered;

    private PulseSDK() {}

    private static pulse.PulseSDK sdk() { return pulse.PulseSDK.INSTANCE; }

    /** The application context, set by {@link PulseInitProvider} at process start. */
    private static volatile Context appContext;

    static void attach(Context context) {
        Context app = context.getApplicationContext();
        appContext = app != null ? app : context;
    }

    /**
     * Start with the App ID created in the Pulse console and the ingest server's URL,
     * e.g. {@code "tcp://collect.example.com:6000"} (the port defaults to 6000). Runtime observation on,
     * and the package, versionCode and versionName are this app's own.
     */
    public void startWithAppId(String appId, String endpoint) {
        startWithAppId(appId, endpoint, true, null, 0L, null);
    }

    /**
     * The full form, the same as iOS's. The App ID identifies the event stream; it is not a secret.
     * A malformed endpoint leaves the SDK stopped (and logged), it does not throw.
     *
     * @param endpoint    the ingest server, {@code tcp://host[:port]}
     * @param packageName reported for the server's package policy; null means this app's own package
     * @param buildNumber the monotonic build counter the update check compares (versionCode);
     *                    0 means this app's own versionCode
     * @param appVersion  display version; null means this app's own versionName
     */
    public synchronized void startWithAppId(String appId, String endpoint, boolean runtime,
                                            String packageName, long buildNumber, String appVersion) {
        try {
            if (sdk().isRunning()) return;
            Context app = appContext;
            if (app == null) {
                // Only when the host removed the provider from its merged manifest.
                Log.w("PulseKit", "not started: PulseInitProvider did not run, so there is no application context");
                return;
            }
            PulseHost.setEnvironment(new AndroidHostEnvironment(app));
            registerLifecycle(app);
            PackageInfo own = ownPackage(app);
            sdk().startWithAppId(
                appId, endpoint, runtime,
                packageName != null ? packageName : app.getPackageName(),
                buildNumber != 0L ? buildNumber : (own != null ? HostFacts.versionCode(own) : 0L),
                appVersion != null ? appVersion : (own != null ? own.versionName : null));
        } catch (Throwable ignored) {
            // Telemetry must never be the reason an app fails to start.
        }
    }

    /** True once {@code start} has spun up the SDK thread. */
    public boolean isRunning() {
        try {
            return sdk().isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The startup update check's answer, or null while it is still in flight. */
    public AppUpdate getUpdateInfo() {
        try {
            pulse.AppUpdate update = sdk().getUpdateInfo();
            return update != null ? AppUpdate.from(update) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public AppUpdate awaitUpdateInfo() {
        return awaitUpdateInfo(DEFAULT_UPDATE_TIMEOUT_MILLIS);
    }

    /**
     * Wait up to timeoutMillis for the update check. On timeout this returns "no update", never
     * null: "we could not ask" has to let the user in. Do not call it on the main thread for long.
     */
    public AppUpdate awaitUpdateInfo(long timeoutMillis) {
        try {
            return AppUpdate.from(sdk().awaitUpdateInfo(timeoutMillis));
        } catch (Throwable t) {
            return AppUpdate.none();
        }
    }

    public void track(String name) {
        track(name, Collections.<String, String>emptyMap());
    }

    /** A business event. Attributes are strings, as on iOS. */
    public void track(String name, Map<String, String> attributes) {
        try {
            sdk().track(name, attributes != null ? attributes : Collections.<String, String>emptyMap());
        } catch (Throwable ignored) {
        }
    }

    public void identify(String userId) {
        try {
            sdk().identify(userId);
        } catch (Throwable ignored) {
        }
    }

    public void recordError(String name, String message) {
        recordError(name, message, null);
    }

    public void recordError(String name, String message, String stack) {
        try {
            sdk().recordError(name, message != null ? message : "", stack);
        } catch (Throwable ignored) {
        }
    }

    public void recordPerformance(String name, long durationMs) {
        try {
            sdk().recordPerformance(name, durationMs);
        } catch (Throwable ignored) {
        }
    }

    public void recordBehavior(String name) {
        recordBehavior(name, null);
    }

    /** A runtime behaviour the host attributes to a module; ignored unless runtime is enabled. */
    public void recordBehavior(String name, String module) {
        try {
            sdk().recordBehavior(name, module);
        } catch (Throwable ignored) {
        }
    }

    public void flushAndWait() {
        flushAndWait(DEFAULT_TIMEOUT_MILLIS);
    }

    /** Send whatever is buffered and wait for it, at most timeoutMillis. */
    public void flushAndWait(long timeoutMillis) {
        try {
            sdk().flushAndWait(timeoutMillis);
        } catch (Throwable ignored) {
        }
    }

    /** Schedule a flush without waiting. The SDK already does this when the app goes to the background. */
    public void flush() {
        try {
            sdk().flush();
        } catch (Throwable ignored) {
        }
    }

    public void stop() {
        stop(DEFAULT_TIMEOUT_MILLIS);
    }

    /** Flush, close the connection and stop the SDK thread. */
    public synchronized void stop(long timeoutMillis) {
        try {
            sdk().stop(timeoutMillis);
        } catch (Throwable ignored) {
        }
    }

    private void registerLifecycle(Context app) {
        if (lifecycleRegistered || !(app instanceof Application)) return;
        ((Application) app).registerActivityLifecycleCallbacks(new AppLifecycle());
        lifecycleRegistered = true;
    }

    private static PackageInfo ownPackage(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        } catch (Throwable t) {
            return null;
        }
    }
}

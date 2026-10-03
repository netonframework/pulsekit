package com.netonstream.pulsekit;

/**
 * Three states, because "current", "newer exists" and "newer required" are genuinely distinct.
 * Named exactly as on iOS ({@code AppUpdateAction.None / Optional / Forced}).
 */
public enum AppUpdateAction {
    None,
    Optional,
    Forced;

    static AppUpdateAction fromName(String name) {
        for (AppUpdateAction action : values()) {
            if (action.name().equals(name)) return action;
        }
        // Anything unrecognised fails open: a telemetry SDK must never be why an app refuses to start.
        return None;
    }
}

package com.netonstream.pulsekit;

/**
 * The startup update check's answer, the same shape an iOS host sees as {@code AppUpdate}.
 * From Kotlin the getters read as the iOS properties do: {@code update.isBlocking},
 * {@code update.latestVersionName}.
 */
public final class AppUpdate {
    private final AppUpdateAction action;
    private final String latestVersionName;
    private final long latestBuildNumber;
    private final String releaseNotes;
    private final String downloadUrl;

    AppUpdate(AppUpdateAction action, String latestVersionName, long latestBuildNumber,
              String releaseNotes, String downloadUrl) {
        this.action = action;
        this.latestVersionName = latestVersionName;
        this.latestBuildNumber = latestBuildNumber;
        this.releaseNotes = releaseNotes;
        this.downloadUrl = downloadUrl;
    }

    static AppUpdate none() {
        return new AppUpdate(AppUpdateAction.None, null, 0L, "", null);
    }

    /** The SDK's answer in the facade's own type. */
    static AppUpdate from(pulse.AppUpdate update) {
        return new AppUpdate(
            AppUpdateAction.fromName(update.getAction().name()),
            update.getLatestVersionName(),
            update.getLatestBuildNumber(),
            update.getReleaseNotes(),
            update.getDownloadUrl());
    }

    public AppUpdateAction getAction() { return action; }

    public String getLatestVersionName() { return latestVersionName; }

    /** 0 when there is no update, as on iOS. */
    public long getLatestBuildNumber() { return latestBuildNumber; }

    public String getReleaseNotes() { return releaseNotes; }

    public String getDownloadUrl() { return downloadUrl; }

    /** The host must not let the user into business features until they update. */
    public boolean isBlocking() { return action == AppUpdateAction.Forced; }

    /** There is something worth showing the user at all. */
    public boolean getHasUpdate() { return action != AppUpdateAction.None; }

    @Override
    public String toString() {
        return "AppUpdate(action=" + action + ", latestVersionName=" + latestVersionName
            + ", latestBuildNumber=" + latestBuildNumber + ", downloadUrl=" + downloadUrl + ")";
    }
}

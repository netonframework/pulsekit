package com.netonstream.pulsekit;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;

/**
 * What the native SDK cannot read itself, gathered once from Context and PackageManager and handed
 * over as JSON (AndroidHostFacts on the native side). On iOS the SDK reads the equivalents directly:
 * NSHomeDirectory, NSBundle and the embedded code signature.
 *
 * Every read is best effort. A missing fact is reported as missing; it never stops the host.
 */
final class HostFacts {
    private HostFacts() {}

    static JSONObject read(Context context) {
        JSONObject facts = new JSONObject();
        put(facts, "filesDir", context.getFilesDir().getAbsolutePath());
        put(facts, "packageName", context.getPackageName());
        put(facts, "deviceModel", Build.MODEL);

        ApplicationInfo app = context.getApplicationInfo();
        put(facts, "sourceDir", app.sourceDir);
        put(facts, "targetSdk", app.targetSdkVersion);
        if (Build.VERSION.SDK_INT >= 24) put(facts, "minSdk", app.minSdkVersion);
        put(facts, "debuggable", (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);

        PackageManager pm = context.getPackageManager();
        put(facts, "installer", installer(pm, context.getPackageName()));
        try {
            int flags = PackageManager.GET_PERMISSIONS
                | (Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : signaturesFlag());
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), flags);
            put(facts, "versionCode", versionCode(info));
            put(facts, "versionName", info.versionName);
            readPermissions(info, facts);
            readSigners(info, facts);
        } catch (Throwable ignored) {
            put(facts, "signingStatus", "unavailable");
        }
        return facts;
    }

    static long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
        return legacyVersionCode(info);
    }

    @SuppressWarnings("deprecation")
    private static long legacyVersionCode(PackageInfo info) {
        return info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static int signaturesFlag() {
        return PackageManager.GET_SIGNATURES;
    }

    private static void readPermissions(PackageInfo info, JSONObject facts) {
        JSONArray requested = new JSONArray();
        JSONArray granted = new JSONArray();
        if (info.requestedPermissions != null) {
            for (int i = 0; i < info.requestedPermissions.length; i++) {
                String permission = info.requestedPermissions[i];
                requested.put(permission);
                int state = info.requestedPermissionsFlags != null ? info.requestedPermissionsFlags[i] : 0;
                if ((state & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) granted.put(permission);
            }
        }
        put(facts, "requestedPermissions", requested);
        put(facts, "grantedPermissions", granted);
    }

    /**
     * SHA-256 of each current signing certificate. A re-signed or re-packaged build carries a
     * different certificate, which is what makes this worth reporting.
     */
    private static void readSigners(PackageInfo info, JSONObject facts) {
        Signature[] current;
        boolean rotated = false;
        if (Build.VERSION.SDK_INT >= 28) {
            SigningInfo signing = info.signingInfo;
            if (signing == null) {
                current = null;
            } else if (signing.hasMultipleSigners()) {
                current = signing.getApkContentsSigners();
            } else {
                Signature[] history = signing.getSigningCertificateHistory();
                // The lineage runs oldest to newest; the last entry is the key signing this build.
                current = history == null || history.length == 0
                    ? null : new Signature[] { history[history.length - 1] };
                rotated = history != null && history.length > 1;
            }
        } else {
            current = legacySignatures(info);
        }
        JSONArray digests = new JSONArray();
        if (current != null) {
            for (Signature signature : current) {
                String digest = sha256(signature.toByteArray());
                if (digest != null) digests.put(digest);
            }
        }
        put(facts, "signingStatus", digests.length() > 0 ? "present" : "not_signed");
        put(facts, "signers", digests);
        put(facts, "signingKeyRotated", rotated);
    }

    @SuppressWarnings("deprecation")
    private static Signature[] legacySignatures(PackageInfo info) {
        return info.signatures;
    }

    @SuppressWarnings("deprecation")
    private static String installer(PackageManager pm, String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return pm.getInstallSourceInfo(packageName).getInstallingPackageName();
            }
            return pm.getInstallerPackageName(packageName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void put(JSONObject object, String key, Object value) {
        if (value == null) return;
        try {
            object.put(key, value);
        } catch (Exception ignored) {
            // JSONObject.put only throws for non-finite numbers, which none of these are.
        }
    }
}

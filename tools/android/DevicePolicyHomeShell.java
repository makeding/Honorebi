package com.beeregg2001.komorebi.tools;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Runs under Android's shell UID through app_process. Android 14 grants
 * MANAGE_DEVICE_POLICY_LOCK_TASK to the SYSTEM_SHELL role, allowing this tool
 * to install or clear a DevicePolicyManager persistent HOME preference.
 */
public final class DevicePolicyHomeShell {
    private DevicePolicyHomeShell() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: DevicePolicyHomeShell "
                            + "apply-dpm <component> | clear-dpm <package> | "
                            + "apply-preferred <component> | clear-preferred <package>");
        }

        Context context = getSystemContext();
        if ("apply-dpm".equals(args[0])) {
            ComponentName homeActivity = ComponentName.unflattenFromString(args[1]);
            if (homeActivity == null) {
                throw new IllegalArgumentException("invalid component: " + args[1]);
            }

            DevicePolicyManager devicePolicyManager =
                    context.getSystemService(DevicePolicyManager.class);
            devicePolicyManager.addPersistentPreferredActivity(
                    null,
                    createHomeFilter(),
                    homeActivity);
            System.out.println("Persistent HOME applied: " + homeActivity.flattenToShortString());
        } else if ("clear-dpm".equals(args[0])) {
            DevicePolicyManager devicePolicyManager =
                    context.getSystemService(DevicePolicyManager.class);
            devicePolicyManager.clearPackagePersistentPreferredActivities(null, args[1]);
            System.out.println("Persistent HOME cleared: " + args[1]);
        } else if ("apply-preferred".equals(args[0])) {
            applyPreferredHome(context, args[1]);
        } else if ("clear-preferred".equals(args[0])) {
            context.getPackageManager().clearPackagePreferredActivities(args[1]);
            System.out.println("Preferred HOME cleared: " + args[1]);
        } else {
            throw new IllegalArgumentException("unknown action: " + args[0]);
        }
    }

    private static void applyPreferredHome(Context context, String flattenedComponent) {
        ComponentName homeActivity = ComponentName.unflattenFromString(flattenedComponent);
        if (homeActivity == null) {
            throw new IllegalArgumentException("invalid component: " + flattenedComponent);
        }

        PackageManager packageManager = context.getPackageManager();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addCategory(Intent.CATEGORY_DEFAULT);
        List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY);
        ComponentName[] candidates = new ComponentName[resolveInfos.size()];
        int bestMatch = 0;
        for (int index = 0; index < resolveInfos.size(); index++) {
            ResolveInfo resolveInfo = resolveInfos.get(index);
            candidates[index] = new ComponentName(
                    resolveInfo.activityInfo.packageName,
                    resolveInfo.activityInfo.name);
            bestMatch = Math.max(bestMatch, resolveInfo.match);
        }

        packageManager.addPreferredActivity(
                createHomeFilter(),
                bestMatch,
                candidates,
                homeActivity);
        System.out.println("Preferred HOME applied: " + homeActivity.flattenToShortString()
                + " candidates=" + candidates.length);
    }

    private static IntentFilter createHomeFilter() {
        IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
        homeFilter.addCategory(Intent.CATEGORY_HOME);
        homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
        return homeFilter;
    }

    private static Context getSystemContext() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
        Object activityThread = systemMain.invoke(null);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        Context systemContext = (Context) getSystemContext.invoke(activityThread);
        return systemContext.createPackageContext(
                "com.android.shell",
                Context.CONTEXT_IGNORE_SECURITY);
    }
}

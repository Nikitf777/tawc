package com.termux.tawc;

import android.content.ComponentName;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.system.Os;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Termux-side launcher for TAWC's external Wayland clients.
 *
 * Runs through {@code app_process} under Termux's uid, so it is not an
 * Android app and cannot {@code bindService}: AMS refuses a bind whose
 * caller has no application record (ActiveServices.bindServiceLocked
 * throws "Unable to find app for caller"). {@code startService} is the
 * one primitive that tolerates the missing caller
 * (ActiveServices.startServiceLocked treats a null caller as foreground,
 * which is how Termux's own {@code am} works), so one end of a fresh
 * socketpair travels as an Intent extra instead of through a bound
 * interface.
 *
 * TAWC's ExternalWaylandService reads the extra, hands the fd to the
 * compositor, and the client in this process talks Wayland over the
 * other end via WAYLAND_SOCKET.
 *
 * Usage: {@code wayland-run <command> [args...]}
 */
public final class WaylandRun {

    private static final String DEFAULT_PACKAGE = "me.phie.tawc.dev";
    private static final String SERVICE_CLASS =
            "me.phie.tawc.compositor.ExternalWaylandService";
    private static final String EXTRA_FD = "me.phie.tawc.extra.CLIENT_FD";
    private static final String CALLING_PACKAGE = "com.termux";

    // Linux F_SETFD; hardcoded so the helper can compile against the bundled
    // stubs (OsConstants.F_SETFD is not a compile-time constant).
    private static final int F_SETFD = 2;

    private WaylandRun() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: wayland-run <command> [args...]");
            System.exit(2);
        }

        String pkg = System.getenv("TAWC_PACKAGE");
        if (pkg == null || pkg.isEmpty()) {
            pkg = DEFAULT_PACKAGE;
        }

        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        ParcelFileDescriptor appEnd = pair[1];
        ParcelFileDescriptor clientEnd = pair[0];

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(pkg, SERVICE_CLASS));
        intent.putExtra(EXTRA_FD, appEnd);

        ComponentName started;
        try {
            started = startService(intent);
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            System.err.println("wayland-run: cannot start " + pkg + "/" + SERVICE_CLASS
                    + ": " + cause);
            System.exit(1);
            return;
        }
        if (started == null) {
            System.err.println("wayland-run: " + pkg + "/" + SERVICE_CLASS
                    + " not found or not exported");
            System.exit(1);
        }

        // TAWC holds its own dup of this end now.
        appEnd.close();

        // ParcelFileDescriptor.createSocketPair() sets CLOEXEC on Q+; the
        // client must inherit the fd across exec for WAYLAND_SOCKET to work.
        Os.fcntlInt(clientEnd.getFileDescriptor(), F_SETFD, 0);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.environment().put("WAYLAND_SOCKET", Integer.toString(clientEnd.getFd()));
        pb.inheritIO();
        System.exit(pb.start().waitFor());
    }

    /**
     * startService(IApplicationThread caller, Intent service, String resolvedType,
     * boolean requireForeground, String callingPackage, [String callingFeatureId,] int userId),
     * newest signature first. The caller is always null.
     */
    private static ComponentName startService(Intent service) throws Exception {
        Object am = activityManager();
        Class<?> iApplicationThread = Class.forName("android.app.IApplicationThread");

        Class<?>[][] signatures = {
                {iApplicationThread, Intent.class, String.class, boolean.class,
                        String.class, String.class, int.class},
                {iApplicationThread, Intent.class, String.class, boolean.class,
                        String.class, int.class},
                {iApplicationThread, Intent.class, String.class, String.class, int.class},
                {iApplicationThread, Intent.class, String.class, int.class},
        };
        int userId = UserHandle.myUserId();
        Object[][] arguments = {
                {null, service, null, false, CALLING_PACKAGE, null, userId},
                {null, service, null, false, CALLING_PACKAGE, userId},
                {null, service, null, CALLING_PACKAGE, userId},
                {null, service, null, userId},
        };

        for (int i = 0; i < signatures.length; i++) {
            Method method;
            try {
                method = am.getClass().getMethod("startService", signatures[i]);
            } catch (NoSuchMethodException e) {
                continue;
            }
            return (ComponentName) method.invoke(am, arguments[i]);
        }
        throw new NoSuchMethodException("IActivityManager.startService not found");
    }

    private static Object activityManager() throws Exception {
        Class<?> activityManager = Class.forName("android.app.ActivityManager");
        try {
            return activityManager.getMethod("getService").invoke(null);
        } catch (NoSuchMethodException e) {
            return Class.forName("android.app.ActivityManagerNative")
                    .getMethod("getDefault").invoke(null);
        }
    }
}

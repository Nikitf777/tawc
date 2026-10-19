package com.termux.tawc;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.system.Os;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Termux-side launcher for TAWC's external Wayland clients.
 *
 * Runs through {@code app_process} under Termux's uid, so it is not an
 * Android app and cannot {@code bindService}: AMS refuses a bind whose
 * caller has no application record (ActiveServices.bindServiceLocked
 * throws "Unable to find app for caller"). {@code startService} is the
 * one primitive that tolerates the missing caller
 * (ActiveServices.startServiceLocked treats a null caller as foreground,
 * which is how Termux's own {@code am} works).
 *
 * An Intent cannot carry the fd: AMS rejects any {@code startService}
 * Intent with {@link Intent#hasFileDescriptors} set. So the Intent carries
 * a Binder token instead, and the app calls
 * {@code IExternalWayland.openClient()} back on it. That is a normal
 * synchronous Binder call, so the compositor end comes back as a
 * {@link ParcelFileDescriptor}. The client end stays here and is handed to
 * the child through {@code WAYLAND_SOCKET}.
 *
 * Usage: {@code wayland-run <command> [args...]}
 */
public final class WaylandRun {

    private static final String DEFAULT_PACKAGE = "me.phie.tawc.dev";
    private static final String SERVICE_CLASS =
            "me.phie.tawc.compositor.ExternalWaylandService";
    private static final String CALLING_PACKAGE = "com.termux";

    // Intent extra: a Bundle holding HostBinder under KEY_HOST. A Binder is
    // not a file descriptor, so AMS accepts the Intent.
    private static final String EXTRA_HOST = "me.phie.tawc.extra.HOST";
    private static final String KEY_HOST = "host";

    // Must match app/src/main/aidl/.../IExternalWayland.aidl. The generated
    // Stub uses FIRST_CALL_TRANSACTION for its only method.
    private static final String DESCRIPTOR = "me.phie.tawc.compositor.IExternalWayland";
    private static final int TRANSACTION_OPEN_CLIENT = IBinder.FIRST_CALL_TRANSACTION;

    // First start may include asset extraction (ensureActivation).
    private static final long CALLBACK_TIMEOUT_MS = 30_000;

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

        HostBinder host = new HostBinder();
        Bundle extras = new Bundle();
        extras.putBinder(KEY_HOST, host);

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(pkg, SERVICE_CLASS));
        intent.putExtra(EXTRA_HOST, extras);

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

        ParcelFileDescriptor clientEnd = host.await(CALLBACK_TIMEOUT_MS);
        if (clientEnd == null) {
            System.err.println("wayland-run: " + pkg
                    + " did not return a client fd (is the app updated?)");
            System.exit(1);
        }

        // createSocketPair sets CLOEXEC on Q+; the client must inherit the
        // fd across exec for WAYLAND_SOCKET to work.
        Os.fcntlInt(clientEnd.getFileDescriptor(), F_SETFD, 0);
        int fd = clientEnd.getFd();

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.environment().put("WAYLAND_SOCKET", Integer.toString(fd));
        pb.inheritIO();
        Process child = pb.start();

        // The child has its own copy after fork/exec. Drop ours so the
        // compositor sees EOF as soon as the client closes its end.
        clientEnd.close();

        System.exit(child.waitFor());
    }

    /**
     * The app's half of the handshake. It calls the existing
     * IExternalWayland.openClient() on this Binder; we create the
     * socketpair and return the compositor end, keeping the client end.
     *
     * app_process starts its Binder thread pool in AppRuntime.onStarted()
     * before invoking main(), so this runs on a pool thread while main()
     * waits on the latch.
     */
    private static final class HostBinder extends Binder {
        private final Object lock = new Object();
        private final CountDownLatch ready = new CountDownLatch(1);
        private ParcelFileDescriptor clientEnd;

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != TRANSACTION_OPEN_CLIENT) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(DESCRIPTOR);

            ParcelFileDescriptor[] pair;
            try {
                pair = ParcelFileDescriptor.createSocketPair();
            } catch (IOException e) {
                reply.writeException(e);
                return true;
            }

            synchronized (lock) {
                if (clientEnd != null) {
                    closeQuietly(pair[0]);
                    closeQuietly(pair[1]);
                    reply.writeException(
                            new IllegalStateException("client fd already issued"));
                    return true;
                }
                clientEnd = pair[0];
            }

            reply.writeNoException();
            // AIDL marks a non-null Parcelable return with a 1.
            reply.writeInt(1);
            pair[1].writeToParcel(reply, 0);
            // Binder dup'd the fd into the reply; drop our copy.
            closeQuietly(pair[1]);

            ready.countDown();
            return true;
        }

        ParcelFileDescriptor await(long timeoutMs) throws InterruptedException {
            if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return null;
            }
            synchronized (lock) {
                return clientEnd;
            }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (IOException ignored) {
        }
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

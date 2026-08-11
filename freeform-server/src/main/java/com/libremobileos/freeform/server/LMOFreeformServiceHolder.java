package com.libremobileos.freeform.server;

import static com.libremobileos.freeform.server.util.Debug.dlog;

import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import com.libremobileos.freeform.ILMOFreeformDisplayCallback;
import com.libremobileos.freeform.server.ui.AppConfig;
import com.libremobileos.freeform.server.ui.FreeformConfig;

public class LMOFreeformServiceHolder {
    private static final String TAG = "LMOFreeform/LMOFreeformServiceManager";

    @SuppressLint("StaticFieldLeak")
    private static LMOFreeformUIService lmoFreeformUIService = null;
    private static LMOFreeformService lmoFreeformService = null;

    public static void init(LMOFreeformUIService uiService, LMOFreeformService freeformService) {
        lmoFreeformUIService = uiService;
        lmoFreeformService = freeformService;
    }

    public static boolean ping() {
        try {
            return lmoFreeformUIService.ping();
        } catch (Exception e) {
            Slog.e(TAG, e.toString());
            return false;
        }
    }

    public static void createDisplay(FreeformConfig freeformConfig, AppConfig appConfig, Surface surface, ILMOFreeformDisplayCallback callback) {
        String displayName;
        displayName = appConfig.getPackageName() + "," + appConfig.getActivityName() + "," + appConfig.getUserId();
        lmoFreeformService.createFreeform(
                displayName,
                callback,
                freeformConfig.getFreeformWidth(),
                freeformConfig.getFreeformHeight(),
                freeformConfig.getDensityDpi(),
                freeformConfig.getSecure(),
                freeformConfig.getOwnContentOnly(),
                freeformConfig.getShouldShowSystemDecorations(),
                surface,
                freeformConfig.getRefreshRate(),
                freeformConfig.getPresentationDeadlineNanos()
                );
    }

    public static boolean startApp(Context context, AppConfig appConfig, int displayId) {
        dlog(TAG, "startApp $appConfig displayId=$displayId");
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(appConfig.getPackageName(), appConfig.getActivityName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            ActivityOptions activityOptions = ActivityOptions.makeBasic();
            activityOptions.setLaunchDisplayId(displayId);
            activityOptions.setCallerDisplayId(displayId);
            context.startActivityAsUser(intent, activityOptions.toBundle(), new UserHandle(appConfig.getUserId()));
            return true;
        } catch (Exception e) {
            Slog.e(TAG, "startApp failed", e);
            return false;
        }
    }

    public static void startPendingIntent(PendingIntent pendingIntent, int displayId) {
        ActivityOptions activityOptions = ActivityOptions.makeBasic();
        activityOptions.setLaunchDisplayId(displayId);
        activityOptions.setCallerDisplayId(displayId);

        final IApplicationThread app = ActivityThread.currentActivityThread()
                    .getApplicationThread();
        try {
            SystemServiceHolder.activityManager.sendIntentSender(
                    app,
                    pendingIntent.getTarget(),
                    pendingIntent.getWhitelistToken(),
                    0,
                    null,
                    null,
                    null,
                    null,
                    activityOptions.toBundle()
            );
        } catch (RemoteException e) {
            Slog.e(TAG, "startPendingIntent failed!", e);
        }
    }

    public static void touch(MotionEvent event, int displayId) {
        lmoFreeformService.injectInputEvent(event, displayId);
    }

    public static void back(int displayId) {
        KeyEvent down = new KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK,
                0
        );
        down.setSource(InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BACK,
                0
        );
        up.setSource(InputDevice.SOURCE_KEYBOARD);
        try {
            lmoFreeformService.injectInputEvent(down, displayId);
            lmoFreeformService.injectInputEvent(up, displayId);
        } catch (Exception ignored) {

        }
    }

    public static void resizeFreeform(IBinder token, int width, int height, int density) {
        lmoFreeformUIService.resizeFreeform(token, width, height, density);
    }

    public static void releaseFreeform(IBinder token) {
        lmoFreeformUIService.releaseFreeform(token);
    }
}

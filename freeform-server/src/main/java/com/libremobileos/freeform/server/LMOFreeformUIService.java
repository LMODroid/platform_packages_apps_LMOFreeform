package com.libremobileos.freeform.server;

import static android.content.Context.CONTEXT_IGNORE_SECURITY;
import static android.content.Context.CONTEXT_INCLUDE_CODE;
import static android.os.Process.SYSTEM_UID;

import android.app.PendingIntent;
import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Surface;

import java.util.Map;

import com.android.server.ServiceThread;

import com.libremobileos.freeform.ILMOFreeformDisplayCallback;
import com.libremobileos.freeform.ILMOFreeformUIService;
import com.libremobileos.freeform.server.ui.FreeformWindowManager;

public class LMOFreeformUIService extends ILMOFreeformUIService.Stub {

    private static final String TAG = "LMOFreeform/LMOFreeformUIService";
    private static final String SERVICE_NAME = "lmo_freeform";

    private Context systemContext = null;
    private DisplayManagerInternal displayManager = null;
    private LMOFreeformService lmoFreeformService = null;
    // private Handler uiHandler = null;
    // Freeform windows live inside system_server, so hosting them on the main looper means
    // every slow bit of freeform work (layout inflation, icon loading, input injection) freezes
    // the whole system and can even make system_server ANR. Give them their own looper instead.
    private Handler handler = null;
    public LMOFreeformUIService(Context context, DisplayManagerInternal displayManager, LMOFreeformService lmoFreeformService) {
        if (null == context || null == displayManager || null == lmoFreeformService) return;

        this.systemContext = context;
        this.displayManager = displayManager;
        this.lmoFreeformService = lmoFreeformService;
        // this.uiHandler = displayManager.getUiHandler();
        // this.handler = displayManager.getHandler();
        ServiceThread thread = new ServiceThread(
                "LMOFreeformUIService", Process.THREAD_PRIORITY_DISPLAY, true /*allowIo*/);
        thread.start();
        handler = thread.getThreadHandler();

        SystemServiceHolder.init();
        try {
            ServiceManager.addService(SERVICE_NAME, this);
            Map<String, IBinder> cache = new ArrayMap<>();
            cache.put(SERVICE_NAME, this);
            ServiceManager.initServiceCache(cache);
            Slog.i(TAG, "add SystemService: " + ServiceManager.getService(SERVICE_NAME));
        } catch (Exception e) {
            Slog.e(TAG, "add " + SERVICE_NAME + " service failed: " + e);
        }
    }

    @Override
    public void startAppInFreeform(
            String packageName, String activityName, int userId, int taskId,
            PendingIntent pendingIntent, int width, int height, int densityDpi) {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("Caller must be system");
        }
        Slog.d(TAG, "startAppInLMOFreeform: " + packageName + "/" + activityName + ", taskId=" + taskId);
        FreeformWindowManager.addWindow(
                handler, systemContext,
                packageName, activityName, userId, taskId, pendingIntent,
                width, height, densityDpi);
    }

    @Override
    public void removeFreeform(String freeformId) {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("Caller must be system");
        }
        FreeformWindowManager.removeWindow(freeformId, true /*close*/);
    }

    @Override
    public void createFreeformInUser(
            String name, int width, int height, int densityDpi, float refreshRate,
            long presentationDeadlineNanos, boolean secure, boolean ownContentOnly,
            boolean shouldShowSystemDecorations, Surface surface,
            ILMOFreeformDisplayCallback callback
    ) {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("Caller must be system");
        }
        displayManager.createFreeformLocked(
                name, callback,
                width, height, densityDpi,
                secure, ownContentOnly, shouldShowSystemDecorations,
                surface, refreshRate, presentationDeadlineNanos
        );
    }

    @Override
    public void resizeFreeform(IBinder appToken, int width, int height, int densityDpi) {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("Caller must be system");
        }
        displayManager.resizeFreeform(appToken, width, height, densityDpi);
    }

    @Override
    public void releaseFreeform(IBinder appToken) {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("Caller must be system");
        }
        displayManager.releaseFreeform(appToken);
    }

    @Override
    public boolean ping() {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("Caller must be system");
        }
        // need inputManager is not null
        return lmoFreeformService.isRunning();
    }
}

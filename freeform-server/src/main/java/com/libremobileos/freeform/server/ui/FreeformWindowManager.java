package com.libremobileos.freeform.server.ui;

import static com.libremobileos.freeform.server.Debug.dlog;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.Slog;

import java.util.HashMap;

public class FreeformWindowManager {
    private static final HashMap<String, FreeformWindow> freeformWindows = new HashMap<>(1);
    private static final String TAG = "FreeformWindowManager";
    
    public static void addWindow(
            Handler handler, Context context,
            String packageName, String activityName, int userId, int taskId,
            PendingIntent pendingIntent, int width, int height, int densityDpi, int offsetX, int offsetY) {
        AppConfig appConfig = new AppConfig(packageName, activityName, pendingIntent, userId, taskId);
        FreeformConfig freeformConfig = new FreeformConfig(width, height, densityDpi, offsetX, offsetY);
        FreeformWindow window = new FreeformWindow(handler, context, appConfig, freeformConfig);
        dlog(TAG, "addWindow: " + packageName + "/" + activityName + ", freeformId=" + window.getFreeformId()
                + ", existing freeformWindows=" + freeformWindows);

        // if freeform exist, remove old
        freeformWindows.forEach((ignored, oldWindow) -> {
            oldWindow.close();
        });
        freeformWindows.clear();

        freeformWindows.put(window.getFreeformId(), window);
    }

    /**
     * @param freeformId packageName,activityName,userId
     */
    public static void removeWindow(String freeformId, Boolean close) {
        FreeformWindow removedWindow = freeformWindows.remove(freeformId);
        if (close && removedWindow != null)
            removedWindow.close();
    }

    public static void removeWindow(String freeformId) {
        removeWindow(freeformId, false /*close*/);
    }
}

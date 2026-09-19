package com.android.server.display;

import static com.android.server.display.DisplayDeviceInfo.FLAG_TRUSTED;
import static com.android.server.display.DisplayModeFactory.createMode;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayShape;
import android.view.Surface;
import android.view.SurfaceControl;

import java.io.PrintWriter;

import com.android.server.display.feature.DisplayManagerFlags;
import com.libremobileos.freeform.ILMOFreeformDisplayCallback;

public class LMOFreeformDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "LMOFreeform/LMOFreeformDisplayAdapter";
    // Unique id prefix for freeform displays.
    public static final String UNIQUE_ID_PREFIX = "lmo-freeform:";

    private final ArrayMap<IBinder, FreeformDisplayDevice> mFreeformDisplayDevices =
            new ArrayMap<>();
    private final ArrayMap<FreeformDisplayDevice, ILMOFreeformDisplayCallback> lmoFreeformDisplayCallbackArrayMap =
            new ArrayMap<>();

    private final Handler mHandler;
    private final Handler mUiHandler;
    private final LogicalDisplayMapper mLogicalDisplayMapper;

    public LMOFreeformDisplayAdapter(
            DisplayManagerService.SyncRoot syncRoot,
            Context context,
            Handler handler,
            DisplayDeviceRepository listener,
            LogicalDisplayMapper logicalDisplayMapper,
            Handler uiHandler,
            DisplayManagerFlags featureFlags
    ) {
        super(syncRoot, context, handler, listener, TAG, featureFlags);
        mHandler = handler;
        mUiHandler = uiHandler;
        mLogicalDisplayMapper = logicalDisplayMapper;
    }

    @Override
    public void dumpLocked(PrintWriter pw) {
        super.dumpLocked(pw);
    }

    @Override
    public void registerLocked() {
        super.registerLocked();
    }

    /**
     * Create a freeform DisplayDevice
     */
    public void createFreeformLocked(String name, ILMOFreeformDisplayCallback callback,
                                     int width, int height, int densityDpi,
                                     boolean secure, boolean ownContentOnly, boolean shouldShowSystemDecorations,
                                     Surface surface, float refreshRate, long presentationDeadlineNanos) {
        synchronized (getSyncRoot()) {
            IBinder appToken = callback.asBinder();
            FreeformFlags flags = new FreeformFlags(secure, ownContentOnly, shouldShowSystemDecorations);
            IBinder displayToken = DisplayControl.createVirtualDisplay(name, flags.mSecure,
                    true /* optimizeForPower */, UNIQUE_ID_PREFIX + name, refreshRate);
            FreeformDisplayDevice device = new FreeformDisplayDevice(displayToken, UNIQUE_ID_PREFIX + name, width, height, densityDpi,
                    refreshRate, presentationDeadlineNanos,
                    flags, surface, new Callback(callback, mHandler), callback.asBinder());

            sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_ADDED);
            mFreeformDisplayDevices.put(appToken, device);
            lmoFreeformDisplayCallbackArrayMap.put(device, callback);

            mHandler.postDelayed(() -> {
                LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
                Slog.i(TAG, "findLogicalDisplayForDevice " + display);
                try {
                    callback.onDisplayAdd(display.getDisplayIdLocked());
                } catch (Exception ignored) {

                }
            }, 500);

            try {
                appToken.linkToDeath(device, 0);
            } catch (RemoteException ex) {
                mFreeformDisplayDevices.remove(appToken);
                device.destroyLocked(false);
            }
        }
    }

    public void resizeFreeform(IBinder appToken,
                               int width, int height, int densityDpi) {
        synchronized (getSyncRoot()) {
            FreeformDisplayDevice device = mFreeformDisplayDevices.get(appToken);
            if (device != null) {
                device.resizeLocked(width, height, densityDpi);
                Slog.i(TAG, "resize freeform display: " + appToken + " " + width + " " + height + " " + densityDpi);
            }
        }
    }

    public void releaseFreeform(IBinder appToken) {
        synchronized (getSyncRoot()) {
            FreeformDisplayDevice device = mFreeformDisplayDevices.remove(appToken);
            if (device != null) {
                lmoFreeformDisplayCallbackArrayMap.remove(device);

                device.destroyLocked(true);
                appToken.unlinkToDeath(device, 0);
                sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_REMOVED);
                Slog.i(TAG, "release freeform display: " + device.mName);
            }
        }
    }

    public Handler getUiHandler() {
        return mUiHandler;
    }

    private void handleBinderDiedLocked(IBinder appToken) {
        FreeformDisplayDevice device = mFreeformDisplayDevices.remove(appToken);
        if (device != null) {
            lmoFreeformDisplayCallbackArrayMap.remove(device);
        }
    }

    public class FreeformDisplayDevice extends DisplayDevice implements IBinder.DeathRecipient {
        private static final int PENDING_SURFACE_CHANGE = 0x01;
        private static final int PENDING_RESIZE = 0x02;

        private String mName;
        private final float mRefreshRate;
        private final long mDisplayPresentationDeadlineNanos;
        private final FreeformFlags mFlags;
        private int mWidth;
        private int mHeight;
        private int mDensityDpi;
        protected Display.Mode mMode;
        protected Surface mSurface;
        protected DisplayDeviceInfo mInfo;

        protected final Callback mCallback;
        protected final IBinder mAppToken;

        private int mPendingChanges;

        FreeformDisplayDevice(IBinder displayToken, String uniqueId,
                              int width, int height, int density,
                              float refreshRate, long presentationDeadlineNanos,
                              FreeformFlags flags,
                              Surface surface, Callback callback, IBinder appToken) {

            super(LMOFreeformDisplayAdapter.this, displayToken, uniqueId,
                    getContext());
            mName = uniqueId;
            mRefreshRate = refreshRate;
            mDisplayPresentationDeadlineNanos = presentationDeadlineNanos;
            mFlags = flags;
            mSurface = surface;
            mWidth = width;
            mHeight = height;
            mDensityDpi = density;
            mMode = createMode(mWidth, mHeight, refreshRate);
            mCallback = callback;
            mAppToken = appToken;
            mPendingChanges |= PENDING_SURFACE_CHANGE;
        }

        public void resizeLocked(int width, int height, int densityDpi) {
            if (mWidth != width || mHeight != height || mDensityDpi != densityDpi) {
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                sendTraversalRequestLocked();
                mWidth = width;
                mHeight = height;
                mMode = createMode(width, height, mRefreshRate);
                mDensityDpi = densityDpi;
                mInfo = null;
            }
        }

        public void destroyLocked(boolean binderAlive) {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            DisplayControl.destroyVirtualDisplay(getDisplayTokenLocked());
            if (binderAlive) {
                mCallback.dispatchDisplayStopped();
            }
        }

        @Override
        public boolean hasStableUniqueId() {
            return false;
        }

        @Override
        public void configureSurfaceLocked(SurfaceControl.Transaction t) {
            if ((mPendingChanges & PENDING_SURFACE_CHANGE) != 0) {
                setSurfaceLocked(t, mSurface);
            }
            mPendingChanges &= ~PENDING_SURFACE_CHANGE;
        }

        @Override
        public void configureDisplaySizeLocked(SurfaceControl.Transaction t) {
            if ((mPendingChanges & PENDING_RESIZE) != 0) {
                t.setDisplaySize(getDisplayTokenLocked(), mWidth, mHeight);
            }
            mPendingChanges &= ~PENDING_RESIZE;
        }

        @Override
        public void binderDied() {
            synchronized (getSyncRoot()) {
                handleBinderDiedLocked(mAppToken);
                Slog.i(TAG, "Freeform display device released because application token died: "
                        + mAppToken);
                destroyLocked(false);
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_REMOVED);
            }
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.uniqueId = getUniqueId();
                mInfo.width = mMode.getPhysicalWidth();
                mInfo.height = mMode.getPhysicalHeight();
                mInfo.modeId = mMode.getModeId();
                mInfo.defaultModeId = mMode.getModeId();
                mInfo.supportedModes = new Display.Mode[] { mMode };
                mInfo.densityDpi = mDensityDpi;
                mInfo.xDpi = mDensityDpi;
                mInfo.yDpi = mDensityDpi;
                mInfo.presentationDeadlineNanos = mDisplayPresentationDeadlineNanos +
                        1000000000L / (int) mRefreshRate;   // display's deadline + 1 frame
                //mInfo.flags = DisplayDeviceInfo.FLAG_PRESENTATION;
                if (mFlags.mSecure) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SECURE;
                }
                if (mFlags.mOwnContentOnly) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                }
                if (mFlags.mShouldShowSystemDecorations) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
                }
                mInfo.type = Display.TYPE_OVERLAY;
                mInfo.touch = DisplayDeviceInfo.TOUCH_VIRTUAL;
                // The display is trusted since it is created by system.
                mInfo.flags |= FLAG_TRUSTED;
                mInfo.displayShape = DisplayShape.createDefaultDisplayShape(mInfo.width, mInfo.height, false);
            }
            return mInfo;
        }
    }

    /** Represents the flags of the freeform display. */
    protected static final class FreeformFlags {
        final boolean mSecure;

        final boolean mOwnContentOnly;

        final boolean mShouldShowSystemDecorations;

        FreeformFlags(
                boolean secure,
                boolean ownContentOnly,
                boolean shouldShowSystemDecorations) {
            mSecure = secure;
            mOwnContentOnly = ownContentOnly;
            mShouldShowSystemDecorations = shouldShowSystemDecorations;
        }

        @Override
        public String toString() {
            return new StringBuilder("{")
                    .append("secure=").append(mSecure)
                    .append(", ownContentOnly=").append(mOwnContentOnly)
                    .append(", shouldShowSystemDecorations=").append(mShouldShowSystemDecorations)
                    .append("}")
                    .toString();
        }
    }

    protected static class Callback extends Handler{
        private static final int MSG_ON_DISPLAY_PAUSED = 0;
        private static final int MSG_ON_DISPLAY_RESUMED = 1;
        private static final int MSG_ON_DISPLAY_STOPPED = 2;

        private final ILMOFreeformDisplayCallback mCallback;

        public Callback(ILMOFreeformDisplayCallback callback, Handler handler) {
            super(handler.getLooper());
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_ON_DISPLAY_PAUSED:
                        mCallback.onDisplayPaused();
                        break;
                    case MSG_ON_DISPLAY_RESUMED:
                        mCallback.onDisplayResumed();
                        break;
                    case MSG_ON_DISPLAY_STOPPED:
                        mCallback.onDisplayStopped();
                        break;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify listener of freeform display event." + e);
            }
        }

        public void dispatchDisplayPaused() {
            sendEmptyMessage(MSG_ON_DISPLAY_PAUSED);
        }

        public void dispatchDisplayResumed() {
            sendEmptyMessage(MSG_ON_DISPLAY_RESUMED);
        }

        public void dispatchDisplayStopped() {
            sendEmptyMessage(MSG_ON_DISPLAY_STOPPED);
        }
    }
}

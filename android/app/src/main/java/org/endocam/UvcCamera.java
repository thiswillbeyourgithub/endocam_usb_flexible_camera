package org.endocam;

/**
 * Thin Java wrapper over the native libuvc glue (endocam_jni.c).
 *
 * Only one instance should be open at a time: the native side keeps a single
 * global camera state. Callbacks (onFrame, onButton) are invoked from native
 * worker threads, never from the UI thread.
 */
public class UvcCamera {
    static {
        System.loadLibrary("endocam");
    }

    /** Order must match the C enum in endocam_jni.c. */
    public static final String[] CONTROL_NAMES = {
        "Brightness", "Contrast", "Saturation", "Hue", "Gamma", "Sharpness",
        "Backlight comp.", "Gain", "WB temperature", "WB auto", "Power line freq."
    };
    public static final int REQ_CUR = 0, REQ_MIN = 1, REQ_MAX = 2, REQ_DEF = 3;
    /** Returned by getControl when the camera does not support a control. */
    public static final int UNSUPPORTED = Integer.MIN_VALUE;

    public interface Listener {
        /** A new frame is available (already drawn on the preview surface). */
        void onFrame(long timestampNs);

        /** Hardware button: state 1 = pressed, 0 = released. */
        void onButton(int button, int state);
    }

    private final Listener listener;

    public UvcCamera(Listener listener) {
        this.listener = listener;
    }

    // Called from native code.
    @SuppressWarnings("unused")
    private void onFrame(long timestampNs) {
        listener.onFrame(timestampNs);
    }

    // Called from native code.
    @SuppressWarnings("unused")
    private void onButton(int button, int state) {
        listener.onButton(button, state);
    }

    public int open(int fd) { return nativeOpen(fd); }
    public int start(int width, int height, int fps) { return nativeStart(width, height, fps); }
    public void stop() { nativeStop(); }
    public void close() { nativeClose(); }
    public String[] frameSizes() {
        String s = nativeFrameSizes();
        return s.isEmpty() ? new String[0] : s.split(",");
    }
    public void setSurface(android.view.Surface surface, int width, int height) {
        nativeSetSurface(surface, width, height);
    }
    public long frameCount() { return nativeFrameCount(); }
    public boolean grabBitmap(android.graphics.Bitmap bmp) { return nativeGrabBitmap(bmp) == 0; }
    public boolean fillYuv420(android.media.Image img, int width, int height) {
        android.media.Image.Plane[] p = img.getPlanes();
        return nativeFillYuv420(
            p[0].getBuffer(), p[0].getRowStride(), p[0].getPixelStride(),
            p[1].getBuffer(), p[2].getBuffer(), p[1].getRowStride(), p[1].getPixelStride(),
            width, height) == 0;
    }
    public int getControl(int id, int req) { return nativeGetControl(id, req); }
    public int setControl(int id, int value) { return nativeSetControl(id, value); }

    /** Human readable libuvc/libusb error, e.g. -3 gives "Access denied (-3)". */
    public static String errorName(int code) { return nativeErrorName(code) + " (" + code + ")"; }

    private static native String nativeErrorName(int code);
    private native int nativeOpen(int fd);
    private native int nativeStart(int width, int height, int fps);
    private native void nativeStop();
    private native void nativeClose();
    private native String nativeFrameSizes();
    private native void nativeSetSurface(android.view.Surface surface, int width, int height);
    private native long nativeFrameCount();
    private native int nativeGrabBitmap(android.graphics.Bitmap bmp);
    private native int nativeFillYuv420(java.nio.ByteBuffer y, int yRow, int yPix,
                                        java.nio.ByteBuffer u, java.nio.ByteBuffer v,
                                        int uvRow, int uvPix, int width, int height);
    private native int nativeGetControl(int id, int req);
    private native int nativeSetControl(int id, int value);
}

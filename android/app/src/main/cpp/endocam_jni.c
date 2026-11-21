/*
 * JNI glue between org.endocam.UvcCamera (Java) and libuvc/libusb.
 *
 * Design:
 * - Android does not let apps open /dev/bus/usb directly, so Java obtains a
 *   file descriptor through UsbManager and hands it over. libusb is told to
 *   skip device enumeration (LIBUSB_OPTION_NO_DEVICE_DISCOVERY), and libuvc
 *   wraps the fd with uvc_wrap().
 * - libuvc owns its libusb context, so it also runs the libusb event thread.
 *   That thread delivers the status interrupt packets, which is how the
 *   camera reports the hardware snapshot button (button callback below).
 * - Frames arrive on a libuvc worker thread as YUYV. Each frame is:
 *     1. copied into `g.last` (guarded by g.lock) for snapshots / recording,
 *     2. converted to RGBA straight into the preview ANativeWindow,
 *     3. announced to Java via onFrame() so the video recorder can pull it.
 * - Only one camera is handled at a time: state lives in the global `g`.
 *
 * Written with Claude Code.
 */
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "libusb.h"
#include "libuvc/libuvc.h"

#define TAG "endocam"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static struct {
    JavaVM *vm;
    jobject listener;        /* global ref to the UvcCamera Java object */
    jmethodID on_frame;      /* void onFrame(long timestampNs) */
    jmethodID on_button;     /* void onButton(int button, int state) */
    uvc_context_t *ctx;
    uvc_device_handle_t *devh;
    int streaming;
    pthread_mutex_t lock;    /* guards window, last*, width/height */
    ANativeWindow *window;
    uint8_t *last;           /* latest YUYV frame */
    size_t last_size;
    int width, height;
    long frame_count;
} g = {.lock = PTHREAD_MUTEX_INITIALIZER};

/* Per-thread JNIEnv. libuvc and libusb create their own pthreads, so they must
 * be attached to the JVM before calling Java, and detached when they exit (the
 * pthread key destructor does that, otherwise the VM aborts on thread exit). */
static pthread_key_t env_key;

static void detach_thread(void *unused) {
    (void)unused;
    (*g.vm)->DetachCurrentThread(g.vm);
}

static JNIEnv *get_env(void) {
    JNIEnv *env = pthread_getspecific(env_key);
    if (env) return env;
    if ((*g.vm)->AttachCurrentThread(g.vm, &env, NULL) != JNI_OK) return NULL;
    pthread_setspecific(env_key, env);
    return env;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g.vm = vm;
    pthread_key_create(&env_key, detach_thread);
    return JNI_VERSION_1_6;
}

/* ---- pixel conversion -------------------------------------------------- */

static inline uint8_t clamp8(int v) { return v < 0 ? 0 : v > 255 ? 255 : (uint8_t)v; }

/* BT.601 limited range YUYV -> RGBA8888, integer math. `dst_stride` is in
 * pixels (ANativeWindow stride) and lets the same routine fill both window
 * buffers and Bitmaps. Example: Y=235,U=V=128 gives white (255,255,255). */
static void yuyv_to_rgba(const uint8_t *src, int w, int h, uint32_t *dst, int dst_stride) {
    for (int y = 0; y < h; y++) {
        const uint8_t *s = src + (size_t)y * w * 2;
        uint8_t *d = (uint8_t *)(dst + (size_t)y * dst_stride);
        for (int x = 0; x < w; x += 2, s += 4) {
            int u = s[1] - 128, v = s[3] - 128;
            int ruv = 409 * v, guv = -100 * u - 208 * v, buv = 516 * u;
            for (int k = 0; k < 2; k++) {
                int c = 298 * (s[k * 2] - 16) + 128;
                *d++ = clamp8((c + ruv) >> 8);
                *d++ = clamp8((c + guv) >> 8);
                *d++ = clamp8((c + buv) >> 8);
                *d++ = 255;
            }
        }
    }
}

/* ---- callbacks from libuvc --------------------------------------------- */

static void frame_cb(uvc_frame_t *frame, void *user) {
    (void)user;
    if (frame->frame_format != UVC_FRAME_FORMAT_YUYV) return;
    int w = (int)frame->width, h = (int)frame->height;
    size_t need = (size_t)w * h * 2;
    if (frame->data_bytes < need) return; /* truncated frame, drop it */

    pthread_mutex_lock(&g.lock);
    if (g.last_size != need) {
        free(g.last);
        g.last = malloc(need);
        g.last_size = need;
    }
    memcpy(g.last, frame->data, need);
    g.width = w;
    g.height = h;
    g.frame_count++;
    if (g.window) {
        ANativeWindow_Buffer buf;
        if (ANativeWindow_lock(g.window, &buf, NULL) == 0) {
            /* Geometry was set to w x h, but guard against a stale buffer. */
            if (buf.width >= w && buf.height >= h)
                yuyv_to_rgba(g.last, w, h, buf.bits, buf.stride);
            ANativeWindow_unlockAndPost(g.window);
        }
    }
    pthread_mutex_unlock(&g.lock);

    JNIEnv *env = get_env();
    if (env && g.listener) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        (*env)->CallVoidMethod(env, g.listener, g.on_frame,
                               (jlong)ts.tv_sec * 1000000000LL + ts.tv_nsec);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
}

static void button_cb(int button, int state, void *user) {
    (void)user;
    LOGI("button %d state %d", button, state);
    JNIEnv *env = get_env();
    if (env && g.listener) {
        (*env)->CallVoidMethod(env, g.listener, g.on_button, button, state);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
}

/* ---- JNI entry points (org.endocam.UvcCamera) ---------------------------- */

#define JNI(ret, name) JNIEXPORT ret JNICALL Java_org_endocam_UvcCamera_##name

/* Returns 0 on success, or a negative libuvc/libusb error code. */
JNI(jint, nativeOpen)(JNIEnv *env, jobject thiz, jint fd) {
    if (g.devh) return 0;
    /* Must be set before the context exists: on Android the app sandbox
     * cannot scan /dev/bus/usb, only use the fd it was given. */
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    uvc_error_t r = uvc_init(&g.ctx, NULL);
    if (r < 0) { LOGE("uvc_init: %s", uvc_strerror(r)); return r; }
    r = uvc_wrap(fd, g.ctx, &g.devh);
    if (r < 0) {
        LOGE("uvc_wrap: %s", uvc_strerror(r));
        uvc_exit(g.ctx);
        g.ctx = NULL;
        g.devh = NULL;
        return r;
    }
    jclass cls = (*env)->GetObjectClass(env, thiz);
    g.on_frame = (*env)->GetMethodID(env, cls, "onFrame", "(J)V");
    g.on_button = (*env)->GetMethodID(env, cls, "onButton", "(II)V");
    g.listener = (*env)->NewGlobalRef(env, thiz);
    uvc_set_button_callback(g.devh, button_cb, NULL);
    return 0;
}

JNI(jint, nativeStart)(JNIEnv *env, jobject thiz, jint w, jint h, jint fps) {
    (void)env; (void)thiz;
    if (!g.devh) return UVC_ERROR_INVALID_DEVICE;
    if (g.streaming) { uvc_stop_streaming(g.devh); g.streaming = 0; }
    uvc_stream_ctrl_t ctrl;
    uvc_error_t r = uvc_get_stream_ctrl_format_size(g.devh, &ctrl, UVC_FRAME_FORMAT_YUYV, w, h, fps);
    /* The camera may not offer the requested fps at that size: retry with
     * any frame rate (0) before giving up. */
    if (r < 0) r = uvc_get_stream_ctrl_format_size(g.devh, &ctrl, UVC_FRAME_FORMAT_YUYV, w, h, 0);
    if (r < 0) { LOGE("stream ctrl %dx%d@%d: %s", w, h, fps, uvc_strerror(r)); return r; }
    r = uvc_start_streaming(g.devh, &ctrl, frame_cb, NULL, 0);
    if (r < 0) { LOGE("start streaming: %s", uvc_strerror(r)); return r; }
    g.streaming = 1;
    return 0;
}

JNI(void, nativeStop)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (g.devh && g.streaming) uvc_stop_streaming(g.devh);
    g.streaming = 0;
}

JNI(void, nativeClose)(JNIEnv *env, jobject thiz) {
    (void)thiz;
    if (g.devh) {
        if (g.streaming) uvc_stop_streaming(g.devh);
        g.streaming = 0;
        uvc_close(g.devh);
        g.devh = NULL;
    }
    if (g.ctx) { uvc_exit(g.ctx); g.ctx = NULL; }
    if (g.listener) { (*env)->DeleteGlobalRef(env, g.listener); g.listener = NULL; }
}

/* Lists "WxH" frame sizes of all YUYV formats, e.g. "640x480,352x288". */
JNI(jstring, nativeFrameSizes)(JNIEnv *env, jobject thiz) {
    (void)thiz;
    char out[512] = "";
    if (g.devh) {
        for (const uvc_format_desc_t *f = uvc_get_format_descs(g.devh); f; f = f->next) {
            if (f->bDescriptorSubtype != UVC_VS_FORMAT_UNCOMPRESSED) continue;
            for (const uvc_frame_desc_t *fr = f->frame_descs; fr; fr = fr->next) {
                char item[32];
                snprintf(item, sizeof item, "%s%dx%d", out[0] ? "," : "", fr->wWidth, fr->wHeight);
                if (strlen(out) + strlen(item) < sizeof out) strcat(out, item);
            }
        }
    }
    return (*env)->NewStringUTF(env, out);
}

JNI(void, nativeSetSurface)(JNIEnv *env, jobject thiz, jobject surface, jint w, jint h) {
    (void)thiz;
    pthread_mutex_lock(&g.lock);
    if (g.window) { ANativeWindow_release(g.window); g.window = NULL; }
    if (surface) {
        g.window = ANativeWindow_fromSurface(env, surface);
        if (g.window) ANativeWindow_setBuffersGeometry(g.window, w, h, WINDOW_FORMAT_RGBA_8888);
    }
    pthread_mutex_unlock(&g.lock);
}

JNI(jlong, nativeFrameCount)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return g.frame_count;
}

/* Copies the latest frame into an ARGB_8888 Bitmap of the same size.
 * Returns 0 on success, -1 if no frame of that size is available. */
JNI(jint, nativeGrabBitmap)(JNIEnv *env, jobject thiz, jobject bitmap) {
    (void)thiz;
    AndroidBitmapInfo info;
    void *px;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return -1;
    if (AndroidBitmap_lockPixels(env, bitmap, &px) < 0) return -1;
    int ret = -1;
    pthread_mutex_lock(&g.lock);
    if (g.last && (int)info.width == g.width && (int)info.height == g.height) {
        yuyv_to_rgba(g.last, g.width, g.height, px, (int)(info.stride / 4));
        ret = 0;
    }
    pthread_mutex_unlock(&g.lock);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ret;
}

/* Writes the latest frame as YUV 4:2:0 into the three planes of a MediaCodec
 * input Image. Plane strides are passed from Java (Image.Plane), which makes
 * this work for both planar (I420, pixel stride 1) and semi-planar (NV12/NV21,
 * pixel stride 2) encoder layouts. Chroma is taken from even rows only, a
 * plain 2x vertical subsampling. Returns 0 on success. */
JNI(jint, nativeFillYuv420)(JNIEnv *env, jobject thiz, jobject yb, jint yRow, jint yPix,
                            jobject ub, jobject vb, jint uvRow, jint uvPix, jint w, jint h) {
    (void)thiz;
    uint8_t *Y = (*env)->GetDirectBufferAddress(env, yb);
    uint8_t *U = (*env)->GetDirectBufferAddress(env, ub);
    uint8_t *V = (*env)->GetDirectBufferAddress(env, vb);
    if (!Y || !U || !V) return -1;
    int ret = -1;
    pthread_mutex_lock(&g.lock);
    if (g.last && w == g.width && h == g.height) {
        for (int y = 0; y < h; y++) {
            const uint8_t *s = g.last + (size_t)y * w * 2;
            uint8_t *dy = Y + (size_t)y * yRow;
            for (int x = 0; x < w; x++) dy[(size_t)x * yPix] = s[x * 2];
            if (y & 1) continue;
            uint8_t *du = U + (size_t)(y / 2) * uvRow, *dv = V + (size_t)(y / 2) * uvRow;
            for (int x = 0; x < w / 2; x++) {
                du[(size_t)x * uvPix] = s[x * 4 + 1];
                dv[(size_t)x * uvPix] = s[x * 4 + 3];
            }
        }
        ret = 0;
    }
    pthread_mutex_unlock(&g.lock);
    return ret;
}

/* ---- image controls ---------------------------------------------------- */

/* Control ids shared with UvcCamera.CONTROL_NAMES (same order). */
enum { C_BRIGHTNESS, C_CONTRAST, C_SATURATION, C_HUE, C_GAMMA, C_SHARPNESS,
       C_BACKLIGHT, C_GAIN, C_WB_TEMP, C_WB_AUTO, C_POWERLINE };

/* Each control has its own typed libuvc getter; these macros dispatch to them
 * so a single (id, request) pair from Java covers min/max/default/current. */
#define GET(fn, T) { T v; r = fn(g.devh, &v, req); out = v; break; }
#define SET(fn, T) { r = fn(g.devh, (T)value); break; }

/* req: 0=current, 1=min, 2=max, 3=default. Returns INT_MIN if unsupported. */
JNI(jint, nativeGetControl)(JNIEnv *env, jobject thiz, jint id, jint which) {
    (void)env; (void)thiz;
    if (!g.devh) return INT_MIN;
    static const enum uvc_req_code reqs[] = {UVC_GET_CUR, UVC_GET_MIN, UVC_GET_MAX, UVC_GET_DEF};
    enum uvc_req_code req = reqs[which & 3];
    int r = UVC_ERROR_NOT_SUPPORTED, out = 0;
    switch (id) {
        case C_BRIGHTNESS: GET(uvc_get_brightness, int16_t)
        case C_CONTRAST:   GET(uvc_get_contrast, uint16_t)
        case C_SATURATION: GET(uvc_get_saturation, uint16_t)
        case C_HUE:        GET(uvc_get_hue, int16_t)
        case C_GAMMA:      GET(uvc_get_gamma, uint16_t)
        case C_SHARPNESS:  GET(uvc_get_sharpness, uint16_t)
        case C_BACKLIGHT:  GET(uvc_get_backlight_compensation, uint16_t)
        case C_GAIN:       GET(uvc_get_gain, uint16_t)
        case C_WB_TEMP:    GET(uvc_get_white_balance_temperature, uint16_t)
        case C_WB_AUTO:    GET(uvc_get_white_balance_temperature_auto, uint8_t)
        case C_POWERLINE:  GET(uvc_get_power_line_frequency, uint8_t)
    }
    return r < 0 ? INT_MIN : out;
}

JNI(jint, nativeSetControl)(JNIEnv *env, jobject thiz, jint id, jint value) {
    (void)env; (void)thiz;
    if (!g.devh) return UVC_ERROR_INVALID_DEVICE;
    int r = UVC_ERROR_NOT_SUPPORTED;
    switch (id) {
        case C_BRIGHTNESS: SET(uvc_set_brightness, int16_t)
        case C_CONTRAST:   SET(uvc_set_contrast, uint16_t)
        case C_SATURATION: SET(uvc_set_saturation, uint16_t)
        case C_HUE:        SET(uvc_set_hue, int16_t)
        case C_GAMMA:      SET(uvc_set_gamma, uint16_t)
        case C_SHARPNESS:  SET(uvc_set_sharpness, uint16_t)
        case C_BACKLIGHT:  SET(uvc_set_backlight_compensation, uint16_t)
        case C_GAIN:       SET(uvc_set_gain, uint16_t)
        case C_WB_TEMP:    SET(uvc_set_white_balance_temperature, uint16_t)
        case C_WB_AUTO:    SET(uvc_set_white_balance_temperature_auto, uint8_t)
        case C_POWERLINE:  SET(uvc_set_power_line_frequency, uint8_t)
    }
    return r < 0 ? r : 0;
}

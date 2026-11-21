package org.endocam;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-screen UVC endoscope viewer.
 *
 * Flow: USB device attached (or app launched) -> ask USB permission ->
 * open device fd -> hand fd to libuvc (UvcCamera) -> stream YUYV into a
 * TextureView surface. Photos go to Pictures/Endocam and videos to
 * Movies/Endocam through MediaStore, which needs no storage permission on
 * Android 10+.
 *
 * The UI is built in code (no XML layouts) to keep the project minimal.
 */
public class MainActivity extends Activity implements UvcCamera.Listener {
    private static final String ACTION_USB_PERMISSION = "org.endocam.USB_PERMISSION";
    /** GEMBIRD / AppoTech AX2311 endoscope. Any other UVC camera also works. */
    private static final int VID = 0x1908, PID = 0x2311;
    private static final int USB_CLASS_VIDEO = 14;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Photo encoding and MediaStore I/O stay off the UI and frame threads. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private UsbManager usb;
    private UsbDeviceConnection connection;
    private UvcCamera camera;
    private VideoRecorder recorder;
    private ParcelFileDescriptor videoPfd;
    private Uri videoUri;

    private TextureView texture;
    private Surface surface;
    private TextView status;
    private Button recButton, sizeButton, buttonModeButton;
    private LinearLayout controlsList;
    private ScrollView settingsPanel;

    private SharedPreferences prefs;
    private String[] sizes = new String[0];
    private int width = 640, height = 480;
    private int rotation;        // 0, 90, 180, 270 degrees
    private boolean mirror;
    private boolean buttonRecords; // hardware button: false = photo, true = start/stop video
    private String buttonMsg = "";
    private long lastFrames, lastFpsTime;
    private float fps;

    // ---- lifecycle ---------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = getSharedPreferences("endocam", MODE_PRIVATE);
        rotation = prefs.getInt("rotation", 0);
        mirror = prefs.getBoolean("mirror", false);
        buttonRecords = prefs.getBoolean("buttonRecords", false);
        buildUi();

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }
        handleIntent(getIntent());
        ui.post(statusTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        unregisterReceiver(usbReceiver);
        closeCamera();
        io.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Volume keys double as shutter, handy when the phone is held at
        // arm's length while the probe is somewhere awkward.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { takePhoto(); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { toggleRecording(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    // ---- USB handling --------------------------------------------------------

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            UsbDevice dev = usbDeviceExtra(intent);
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                if (dev != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openCamera(dev);
                } else {
                    setStatus("USB permission denied");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                closeCamera();
                setStatus("Camera unplugged");
            }
        }
    };

    private void handleIntent(Intent intent) {
        UsbDevice dev = intent == null ? null
                : usbDeviceExtra(intent);
        if (dev == null) dev = findCamera();
        if (dev == null) {
            setStatus("Plug in the USB camera (USB-C or OTG adapter)");
            return;
        }
        if (usb.hasPermission(dev)) {
            openCamera(dev);
        } else {
            // FLAG_MUTABLE is required: the system adds EXTRA_DEVICE and
            // EXTRA_PERMISSION_GRANTED to this intent. setPackage keeps it explicit.
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_MUTABLE);
            usb.requestPermission(dev, pi);
        }
    }

    /** Prefers the known endoscope, falls back to any device with a video interface. */
    private UsbDevice findCamera() {
        UsbDevice any = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                if (d.getInterface(i).getInterfaceClass() == USB_CLASS_VIDEO) any = d;
            }
        }
        return any;
    }

    private void openCamera(UsbDevice dev) {
        if (camera != null) return;
        connection = usb.openDevice(dev);
        if (connection == null) {
            setStatus("Could not open USB device");
            return;
        }
        // Force-claim every interface: on phones whose kernel ships the
        // uvcvideo driver, this detaches it so libuvc can take over. libuvc's
        // own claims on the same fd then succeed as no-ops.
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface intf = dev.getInterface(i);
            connection.claimInterface(intf, true);
        }
        camera = new UvcCamera(this);
        int r = camera.open(connection.getFileDescriptor());
        if (r != 0) {
            setStatus("libuvc open failed (" + r + ")");
            camera = null;
            connection.close();
            connection = null;
            return;
        }
        sizes = camera.frameSizes();
        String saved = prefs.getString("size", width + "x" + height);
        applySize(saved);
        startStream();
        restoreControls();
        buildControls();
    }

    private void closeCamera() {
        stopRecording();
        if (camera != null) {
            camera.setSurface(null, 0, 0);
            camera.close();
            camera = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
        if (controlsList != null) controlsList.removeAllViews();
        if (sizeButton != null) sizeButton.setText("(no camera)");
    }

    private void applySize(String s) {
        String chosen = sizes.length > 0 ? sizes[0] : width + "x" + height;
        for (String c : sizes) if (c.equals(s)) chosen = c;
        String[] wh = chosen.split("x");
        width = Integer.parseInt(wh[0]);
        height = Integer.parseInt(wh[1]);
        sizeButton.setText(chosen);
    }

    private void startStream() {
        if (camera == null) return;
        int r = camera.start(width, height, 30);
        if (r != 0) setStatus("Stream start failed (" + r + ")");
        attachSurface();
        updateTransform();
    }

    private void attachSurface() {
        if (camera == null || surface == null) return;
        texture.getSurfaceTexture().setDefaultBufferSize(width, height);
        camera.setSurface(surface, width, height);
    }

    // ---- camera callbacks (native threads) -----------------------------------

    @Override
    public void onFrame(long timestampNs) {
        VideoRecorder rec = recorder;
        if (rec != null) rec.onFrame(timestampNs);
    }

    @Override
    public void onButton(int button, int state) {
        if (state != 1) return;
        ui.post(() -> {
            buttonMsg = "button pressed " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            if (buttonRecords) toggleRecording(); else takePhoto();
        });
    }

    // ---- actions -------------------------------------------------------------

    private void takePhoto() {
        if (camera == null) return;
        Bitmap raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (!camera.grabBitmap(raw)) {
            toast("No frame yet");
            return;
        }
        flash();
        final int rot = rotation;
        final boolean mir = mirror;
        io.execute(() -> {
            Matrix m = new Matrix();
            if (mir) m.postScale(-1, 1);
            m.postRotate(rot);
            Bitmap out = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            ContentValues v = mediaValues("IMG", "image/jpeg", Environment.DIRECTORY_PICTURES);
            ContentResolver cr = getContentResolver();
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            try (OutputStream os = cr.openOutputStream(uri)) {
                out.compress(Bitmap.CompressFormat.JPEG, 95, os);
                toast("Photo saved to Pictures/Endocam");
            } catch (Exception e) {
                toast("Photo failed: " + e.getMessage());
            }
        });
    }

    private void toggleRecording() {
        if (recorder != null) stopRecording(); else startRecording();
    }

    private void startRecording() {
        if (camera == null) return;
        try {
            ContentValues v = mediaValues("VID", "video/mp4", Environment.DIRECTORY_MOVIES);
            // IS_PENDING hides the file from galleries until it is finalized.
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            videoPfd = getContentResolver().openFileDescriptor(videoUri, "rw");
            VideoRecorder rec = new VideoRecorder(camera, width, height);
            rec.start(videoPfd.getFileDescriptor(), rotation);
            recorder = rec;
            recButton.setText("■ Stop");
            recButton.setTextColor(Color.RED);
        } catch (Exception e) {
            toast("Recording failed: " + e.getMessage());
        }
    }

    private void stopRecording() {
        VideoRecorder rec = recorder;
        if (rec == null) return;
        recorder = null;
        rec.stop();
        try {
            videoPfd.close();
        } catch (Exception ignored) {
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(videoUri, v, null, null);
        if (recButton != null) {
            recButton.setText("● Rec");
            recButton.setTextColor(Color.WHITE);
        }
        toast("Video saved to Movies/Endocam");
    }

    private ContentValues mediaValues(String prefix, String mime, String dir) {
        String name = prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/Endocam");
        return v;
    }

    // ---- UI ------------------------------------------------------------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        texture = new TextureView(this);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                attachSurface();
                updateTransform();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                updateTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (camera != null) camera.setSurface(null, 0, 0);
                surface.release();
                surface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        root.addView(texture, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.GREEN);
        status.setShadowLayer(3, 1, 1, Color.BLACK);
        status.setPadding(24, 24, 24, 24);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0x88000000);
        bar.setGravity(Gravity.CENTER);
        bar.addView(barButton("📷 Photo", v -> takePhoto()));
        recButton = barButton("● Rec", v -> toggleRecording());
        bar.addView(recButton);
        bar.addView(barButton("⟳ Rotate", v -> {
            rotation = (rotation + 90) % 360;
            prefs.edit().putInt("rotation", rotation).apply();
            updateTransform();
        }));
        bar.addView(barButton("⇋ Mirror", v -> {
            mirror = !mirror;
            prefs.edit().putBoolean("mirror", mirror).apply();
            updateTransform();
        }));
        bar.addView(barButton("⚙", v -> settingsPanel.setVisibility(
                settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        settingsPanel = new ScrollView(this);
        settingsPanel.setBackgroundColor(0xCC000000);
        settingsPanel.setVisibility(View.GONE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 24, 24, 24);
        sizeButton = new Button(this);
        sizeButton.setText("(no camera)");
        sizeButton.setOnClickListener(v -> cycleSize());
        panel.addView(label("Resolution"));
        panel.addView(sizeButton);
        buttonModeButton = new Button(this);
        buttonModeButton.setOnClickListener(v -> {
            buttonRecords = !buttonRecords;
            prefs.edit().putBoolean("buttonRecords", buttonRecords).apply();
            updateButtonModeLabel();
        });
        updateButtonModeLabel();
        panel.addView(label("Camera button action"));
        panel.addView(buttonModeButton);
        Button reset = new Button(this);
        reset.setText("Reset image controls");
        reset.setOnClickListener(v -> resetControls());
        panel.addView(reset);
        controlsList = new LinearLayout(this);
        controlsList.setOrientation(LinearLayout.VERTICAL);
        panel.addView(controlsList);
        settingsPanel.addView(panel);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(300), -1, Gravity.END);
        lp.bottomMargin = dp(56);
        root.addView(settingsPanel, lp);

        setContentView(root);
    }

    private Button barButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(56), 1f));
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    private void updateButtonModeLabel() {
        buttonModeButton.setText(buttonRecords ? "Start/stop video" : "Take photo");
    }

    private void cycleSize() {
        if (sizes.length == 0 || camera == null) return;
        stopRecording();
        int idx = 0;
        for (int i = 0; i < sizes.length; i++) if (sizes[i].equals(width + "x" + height)) idx = i;
        String next = sizes[(idx + 1) % sizes.length];
        prefs.edit().putString("size", next).apply();
        applySize(next);
        startStream();
    }

    /**
     * One SeekBar per control the camera supports. Values are persisted so
     * they are re-applied when the camera is plugged in again (UVC cameras
     * reset to defaults on power loss).
     */
    private void buildControls() {
        controlsList.removeAllViews();
        for (int id = 0; id < UvcCamera.CONTROL_NAMES.length; id++) {
            int min = camera.getControl(id, UvcCamera.REQ_MIN);
            int max = camera.getControl(id, UvcCamera.REQ_MAX);
            int cur = camera.getControl(id, UvcCamera.REQ_CUR);
            if (min == UvcCamera.UNSUPPORTED || max == UvcCamera.UNSUPPORTED
                    || cur == UvcCamera.UNSUPPORTED || max <= min) continue;
            final int cid = id;
            final String name = UvcCamera.CONTROL_NAMES[id];
            TextView t = label(name + ": " + cur);
            SeekBar sb = new SeekBar(this);
            sb.setMax(max - min);
            sb.setProgress(cur - min);
            sb.setTag(cid);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    int value = p + min;
                    t.setText(name + ": " + value);
                    if (!fromUser || camera == null) return;
                    camera.setControl(cid, value);
                    prefs.edit().putInt("ctrl_" + cid, value).apply();
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            controlsList.addView(t);
            controlsList.addView(sb);
        }
    }

    private void restoreControls() {
        for (int id = 0; id < UvcCamera.CONTROL_NAMES.length; id++) {
            String key = "ctrl_" + id;
            if (prefs.contains(key)) camera.setControl(id, prefs.getInt(key, 0));
        }
    }

    private void resetControls() {
        if (camera == null) return;
        SharedPreferences.Editor e = prefs.edit();
        for (int id = 0; id < UvcCamera.CONTROL_NAMES.length; id++) {
            int def = camera.getControl(id, UvcCamera.REQ_DEF);
            if (def != UvcCamera.UNSUPPORTED) camera.setControl(id, def);
            e.remove("ctrl_" + id);
        }
        e.apply();
        buildControls();
    }

    /**
     * Fits the camera image in the view with the chosen rotation and mirror.
     * A TextureView stretches its content to the view size by default, so
     * the matrix first undoes that stretch (back to the native w x h aspect),
     * then mirrors, rotates, and finally scales to fit. Example: a 640x480
     * frame rotated 90 degrees in a 1080x2000 portrait view ends up
     * 1080 wide x 1440 tall, centered.
     */
    private void updateTransform() {
        float vw = texture.getWidth(), vh = texture.getHeight();
        if (vw == 0 || vh == 0) return;
        float cx = vw / 2, cy = vh / 2;
        Matrix m = new Matrix();
        m.postScale(width / vw, height / vh, cx, cy);
        if (mirror) m.postScale(-1, 1, cx, cy);
        m.postRotate(rotation, cx, cy);
        boolean swap = rotation % 180 != 0;
        float rw = swap ? height : width, rh = swap ? width : height;
        float s = Math.min(vw / rw, vh / rh);
        m.postScale(s, s, cx, cy);
        texture.setTransform(m);
    }

    /** Refreshes the status overlay twice per second (fps, size, button). */
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (camera != null) {
                long now = System.nanoTime();
                long frames = camera.frameCount();
                if (lastFpsTime > 0) fps = (frames - lastFrames) * 1e9f / (now - lastFpsTime);
                lastFrames = frames;
                lastFpsTime = now;
                String s = String.format(Locale.US, "%dx%d  %.1f fps", width, height, fps);
                if (recorder != null) s += "  ● REC";
                if (!buttonMsg.isEmpty()) s += "\n" + buttonMsg;
                status.setText(s);
            }
            ui.postDelayed(this, 500);
        }
    };

    private void flash() {
        texture.animate().alpha(0.3f).setDuration(80)
                .withEndAction(() -> texture.animate().alpha(1f).setDuration(120)).start();
    }

    private void setStatus(String s) {
        ui.post(() -> status.setText(s));
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    /** getParcelableExtra(String, Class) only exists from API 33. */
    @SuppressWarnings("deprecation")
    private static UsbDevice usbDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}

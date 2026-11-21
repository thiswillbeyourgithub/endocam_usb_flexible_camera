package org.endocam;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * H.264/MP4 recorder fed from the camera frame callback.
 *
 * Frames are pushed through MediaCodec's Image API (getInputImage) so the
 * native code can write into whatever YUV 4:2:0 layout the device encoder
 * uses, instead of guessing between I420/NV12/NV21 color formats.
 *
 * Presentation timestamps are the real capture times, so the video plays
 * back at real speed even though the endoscope's frame rate varies (it drops
 * to ~10 fps in low light).
 *
 * All methods are called from the native frame thread except start/stop
 * which are synchronized against it.
 */
public class VideoRecorder {
    private static final String TAG = "endocam";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    private final UvcCamera camera;
    private final int width, height;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private int track = -1;
    private long firstNs = -1;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    public VideoRecorder(UvcCamera camera, int width, int height) {
        this.camera = camera;
        this.width = width;
        this.height = height;
    }

    /**
     * @param fd destination (from a MediaStore entry opened "rw")
     * @param rotationDegrees stored as an MP4 orientation hint so players
     *        rotate the video like the on-screen preview
     */
    public synchronized void start(FileDescriptor fd, int rotationDegrees) throws IOException {
        MediaFormat fmt = MediaFormat.createVideoFormat(MIME, width, height);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 6);
        // Nominal only: real timing comes from the presentation timestamps.
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.setOrientationHint(rotationDegrees);
        track = -1;
        firstNs = -1;
    }

    public synchronized boolean isRecording() {
        return codec != null;
    }

    /** Encodes the camera's latest frame. Called on every new frame. */
    public synchronized void onFrame(long timestampNs) {
        if (codec == null) return;
        try {
            int idx = codec.dequeueInputBuffer(10_000);
            if (idx >= 0) {
                if (firstNs < 0) firstNs = timestampNs;
                Image img = codec.getInputImage(idx);
                boolean ok = img != null && camera.fillYuv420(img, width, height);
                codec.queueInputBuffer(idx, 0, ok ? width * height * 3 / 2 : 0,
                        (timestampNs - firstNs) / 1000, 0);
            }
            drain(false);
        } catch (RuntimeException e) {
            Log.e(TAG, "encoder error", e);
        }
    }

    /** Flushes the encoder and finalizes the MP4. */
    public synchronized void stop() {
        if (codec == null) return;
        try {
            int idx = codec.dequeueInputBuffer(100_000);
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drain(true);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "encoder flush error", e);
        }
        codec.stop();
        codec.release();
        codec = null;
        try {
            if (track >= 0) muxer.stop();
        } catch (RuntimeException e) {
            Log.e(TAG, "muxer stop error", e);
        }
        muxer.release();
        muxer = null;
    }

    private void drain(boolean untilEos) {
        while (true) {
            int out = codec.dequeueOutputBuffer(info, untilEos ? 100_000 : 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return;
                continue;
            }
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The muxer can only start once the encoder reports its real
                // output format (it carries the SPS/PPS headers).
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                continue;
            }
            if (out < 0) continue;
            ByteBuffer buf = codec.getOutputBuffer(out);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (buf != null && info.size > 0 && track >= 0 && !config) {
                muxer.writeSampleData(track, buf, info);
            }
            codec.releaseOutputBuffer(out, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }
}

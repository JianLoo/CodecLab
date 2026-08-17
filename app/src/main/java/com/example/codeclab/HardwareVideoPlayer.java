package com.example.codeclab;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Hardware-only video preview path.
 *
 * The extractor feeds compressed access units directly to a real hardware
 * MediaCodec decoder. Decoded frames are rendered by the codec into the
 * caller's SurfaceView surface; no ImageReader, YUV buffer, Bitmap, or
 * TextureView copy is involved.
 */
public final class HardwareVideoPlayer {
    private static final long TIMEOUT_US = 10_000;
    private static final long NS_PER_US = 1_000L;

    public interface Listener {
        void onVideoSize(int width, int height);
        void onStarted(String decoderName);
        void onCompleted();
        void onError(String error);
    }

    private final Object lock = new Object();
    private volatile boolean stopRequested;
    private volatile MediaCodec decoder;
    private Thread worker;

    /** Returns the display-oriented size before any decoded frame is rendered. */
    public static int[] probeDisplaySize(File input) throws IOException {
        if (input == null || !input.isFile() || !input.canRead()) {
            throw new IOException("视频文件不可读: " + input);
        }
        MediaExtractor extractor = new MediaExtractor();
        try (FileInputStream stream = new FileInputStream(input)) {
            extractor.setDataSource(stream.getFD());
            int track = findVideoTrack(extractor);
            if (track < 0) throw new IOException("未找到视频轨道");
            MediaFormat format = extractor.getTrackFormat(track);
            int width = format.getInteger(MediaFormat.KEY_WIDTH);
            int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            int rotation = format.containsKey(MediaFormat.KEY_ROTATION)
                    ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
            if (rotation == 90 || rotation == 270) {
                int swap = width;
                width = height;
                height = swap;
            }
            return new int[]{width, height};
        } finally {
            extractor.release();
        }
    }

    public void play(File input, Surface surface, Listener listener) {
        if (input == null || !input.isFile() || !input.canRead()) {
            if (listener != null) listener.onError("视频文件不可读: " + input);
            return;
        }
        if (surface == null || !surface.isValid()) {
            if (listener != null) listener.onError("播放 Surface 尚未就绪");
            return;
        }
        stop();
        stopRequested = false;
        Thread thread = new Thread(() -> run(input, surface, listener),
                "codec-lab-video-player");
        synchronized (lock) {
            worker = thread;
        }
        thread.start();
    }

    public void stop() {
        stopRequested = true;
        Thread thread;
        synchronized (lock) {
            thread = worker;
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            if (worker == thread && (thread == null || !thread.isAlive())) worker = null;
        }
    }

    private void run(File input, Surface surface, Listener listener) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try (FileInputStream stream = new FileInputStream(input)) {
            extractor.setDataSource(stream.getFD());
            int track = findVideoTrack(extractor);
            if (track < 0) throw new IOException("未找到视频轨道");
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("video/")) {
                throw new IOException("不是视频轨道: " + mime);
            }
            int width = format.getInteger(MediaFormat.KEY_WIDTH);
            int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            int rotation = format.containsKey(MediaFormat.KEY_ROTATION)
                    ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
            codec = createHardwareDecoder(mime);
            decoder = codec;
            String decoderName = codec.getName();
            codec.configure(format, surface, null, 0);
            notifyDisplaySize(listener, width, height, rotation);
            codec.start();
            if (listener != null) listener.onStarted(decoderName);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            boolean outputEos = false;
            long firstPtsUs = Long.MIN_VALUE;
            long startNs = 0;
            while (!outputEos && !stopRequested) {
                if (!inputEos) {
                    int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inputIndex);
                        if (buffer == null) throw new IOException("解码器输入缓冲区不可用");
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            long ptsUs = Math.max(0, extractor.getSampleTime());
                            codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    int outputWidth = outputFormat.containsKey(MediaFormat.KEY_WIDTH)
                            ? outputFormat.getInteger(MediaFormat.KEY_WIDTH) : width;
                    int outputHeight = outputFormat.containsKey(MediaFormat.KEY_HEIGHT)
                            ? outputFormat.getInteger(MediaFormat.KEY_HEIGHT) : height;
                    notifyDisplaySize(listener, outputWidth, outputHeight, rotation);
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (outputIndex < 0) continue;

                if (info.size > 0 && info.presentationTimeUs >= 0) {
                    if (firstPtsUs == Long.MIN_VALUE) {
                        firstPtsUs = info.presentationTimeUs;
                        startNs = System.nanoTime();
                    }
                    pace(startNs + (info.presentationTimeUs - firstPtsUs) * NS_PER_US);
                }
                codec.releaseOutputBuffer(outputIndex, true);
                outputEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            }
            if (!stopRequested && outputEos && listener != null) listener.onCompleted();
        } catch (Throwable error) {
            if (!stopRequested && listener != null) listener.onError(message(error));
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            decoder = null;
            extractor.release();
            synchronized (lock) {
                if (worker == Thread.currentThread()) worker = null;
            }
        }
    }

    private void pace(long targetNs) {
        while (!stopRequested) {
            long remaining = targetNs - System.nanoTime();
            if (remaining <= 0) return;
            try {
                if (remaining > 2_000_000L) {
                    long millis = Math.min(20, remaining / 1_000_000L);
                    int nanos = (int) Math.min(999_999, remaining % 1_000_000L);
                    Thread.sleep(millis, nanos);
                } else {
                    Thread.yield();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static MediaCodec createHardwareDecoder(String mime) throws IOException {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder() || !isHardwareCodec(info)) continue;
            for (String type : info.getSupportedTypes()) {
                if (mime.equalsIgnoreCase(type)) {
                    return MediaCodec.createByCodecName(info.getName());
                }
            }
        }
        throw new IOException("没有可用的硬件视频解码器: " + mime);
    }

    private static boolean isHardwareCodec(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated() && !info.isSoftwareOnly();
        }
        String name = info.getName().toLowerCase(Locale.US);
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.")
                || name.startsWith("c2.google.") || name.contains("ffmpeg")
                || name.contains("software") || name.endsWith(".sw"));
    }

    private static int findVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    private static void notifySize(Listener listener, int width, int height) {
        if (listener != null && width > 0 && height > 0) listener.onVideoSize(width, height);
    }

    private static void notifyDisplaySize(Listener listener, int width, int height, int rotation) {
        if (rotation == 90 || rotation == 270) notifySize(listener, height, width);
        else notifySize(listener, width, height);
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + ": "
                + (message == null ? "unknown error" : message);
    }
}

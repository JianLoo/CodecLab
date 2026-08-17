package com.example.codeclab;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Direct Android hardware pipeline. FFmpeg is intentionally not involved in
 * this path: MediaExtractor feeds a hardware decoder, decoded linear Images
 * are copied into a MediaCodec encoder, and the encoder produces both an MP4
 * preview and an Annex-B elementary stream.
 *
 * This class owns both independent hardware stages. It can decode to YUV,
 * encode a YUV stream, or connect the two directly for hardware/hardware.
 * It never substitutes a software MediaCodec implementation.
 */
public final class MediaCodecTranscoder {
    private static final long TIMEOUT_US = 10_000;
    private static final long STALL_TIMEOUT_NS = 30_000_000_000L;
    private static final int CODEC_CONFIG = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
    private static final int EOS = MediaCodec.BUFFER_FLAG_END_OF_STREAM;

    public interface Listener {
        void onProgress(int percent, String stage);
    }

    private MediaCodecTranscoder() {}

    public static String decodeToYuv(String inputPath, String outputFile, int width, int height,
                                     int fps, String pixelFormat, Listener listener) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try (FileInputStream input = new FileInputStream(inputPath);
             FileOutputStream output = new FileOutputStream(outputFile)) {
            extractor.setDataSource(input.getFD());
            int track = findVideoTrack(extractor);
            if (track < 0) return error("未找到视频轨道");
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            int sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            long duration = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            decoder = createHardwareDecoder(mime);
            String decoderName = decoder.getName();
            // ByteBuffer/Image output is required here. Qualcomm decoders may
            // expose a tiled/UBWC vendor Surface layout through ImageReader;
            // treating that memory as linear YUV causes green block artifacts.
            decoder.configure(format, null, null, 0);
            decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            boolean outputEos = false;
            long frames = 0;
            long nextPts = 0;
            int frameSize = width * height * 3 / 2;
            ByteBuffer converted = ByteBuffer.allocateDirect(frameSize);
            byte[] outputChunk = new byte[frameSize];
            while (!outputEos) {
                if (!inputEos) {
                    int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, EOS);
                            inputEos = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                        || index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (index < 0) continue;
                outputEos = (info.flags & EOS) != 0;
                Image image = decoder.getOutputImage(index);
                try {
                    if (image != null && info.size > 0) {
                        long pts = info.presentationTimeUs;
                        if (pts >= nextPts - 500_000L / Math.max(1, fps)) {
                            converted.clear();
                            if ("nv12".equals(pixelFormat)) copyImageAsNv12(image, converted, width, height);
                            else copyImageAsYuv420p(image, converted, width, height);
                            int bytes = converted.position();
                            converted.flip();
                            converted.get(outputChunk, 0, bytes);
                            output.write(outputChunk, 0, bytes);
                            frames++;
                            nextPts = pts + 1_000_000L / Math.max(1, fps);
                            int percent = duration > 0
                                    ? (int) Math.min(100, pts * 100 / duration) : 0;
                            notify(listener, percent, "Java MediaCodec 硬件解码");
                        }
                    }
                } finally {
                    if (image != null) image.close();
                    decoder.releaseOutputBuffer(index, false);
                }
            }
            notify(listener, 100, "硬件解码完成");
            return new JSONObject().put("ok", true).put("frames", frames)
                    .put("width", width).put("height", height).put("fps", fps)
                    .put("pixelFormat", pixelFormat).put("decoderName", decoderName).toString();
        } catch (Throwable t) {
            return error(t.getClass().getSimpleName() + ": " + safeMessage(t));
        } finally {
            if (decoder != null) { try { decoder.stop(); } catch (Exception ignored) { } decoder.release(); }
            extractor.release();
        }
    }

    public static String encodeYuv(String yuvPath, String inputPath, String outputDir,
                                   String outputCodec, int width, int height, int fps,
                                   int bitrateKbps, boolean metrics, String decoderMode,
                                   String decoderName, String pixelFormat, Listener listener) {
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        FileOutputStream raw = null;
        try (FileInputStream yuv = new FileInputStream(yuvPath)) {
            File dir = new File(outputDir);
            if (!dir.isDirectory() && !dir.mkdirs()) return error("无法创建输出目录: " + dir);
            String encoderMime = "hevc".equals(outputCodec) || "h265".equals(outputCodec)
                    ? "video/hevc" : "video/avc";
            File rawFile = new File(dir, encoderMime.equals("video/hevc") ? "output.hevc" : "output.h264");
            File preview = new File(dir, "preview.mp4");
            File report = new File(dir, "metrics.json");
            EncoderConfig encoderConfig = createEncoder(encoderMime, width, height, fps, bitrateKbps);
            encoder = encoderConfig.codec;
            String encoderName = encoder.getName();
            encoder.start();
            EncoderInputLayout encoderLayout = resolveInputLayout(encoder,
                    encoderConfig.colorFormat, width, height);
            raw = new FileOutputStream(rawFile);
            muxer = new MediaMuxer(preview.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            EncoderSink sink = new EncoderSink(raw, muxer, outputCodec);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int frameSize = width * height * 3 / 2;
            byte[] frame = new byte[frameSize];
            long frames = 0;
            long lastOutputNs = System.nanoTime();
            long observedSamples = 0;
            while (readFully(yuv, frame)) {
                boolean queued = false;
                while (!queued) {
                    int index = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = encoder.getInputBuffer(index);
                        buffer.clear();
                        putFrameForEncoder(buffer, frame, "nv12".equalsIgnoreCase(pixelFormat),
                                encoderLayout, width, height);
                        int inputSize = buffer.position();
                        long pts = frames * 1_000_000L / Math.max(1, fps);
                        encoder.queueInputBuffer(index, 0, inputSize, pts, 0);
                        frames++;
                        queued = true;
                    }
                    sink.drain(encoder, info, queued ? 0 : TIMEOUT_US);
                    if (sink.samples != observedSamples) {
                        observedSamples = sink.samples;
                        lastOutputNs = System.nanoTime();
                    } else if (System.nanoTime() - lastOutputNs > STALL_TIMEOUT_NS) {
                        throw new IOException("硬件编码器超过 30 秒没有输出，已终止；未切换软件编码");
                    }
                }
                notify(listener, Math.min(88, (int) (frames % 89)), "Java MediaCodec 硬件编码");
            }
            boolean eosQueued = false;
            boolean eosOutput = false;
            while (!eosOutput) {
                if (!eosQueued) {
                    int index = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0,
                                frames * 1_000_000L / Math.max(1, fps), EOS);
                        eosQueued = true;
                    }
                }
                eosOutput = sink.drain(encoder, info, TIMEOUT_US);
                if (sink.samples != observedSamples) {
                    observedSamples = sink.samples;
                    lastOutputNs = System.nanoTime();
                } else if (System.nanoTime() - lastOutputNs > STALL_TIMEOUT_NS) {
                    throw new IOException("硬件编码器排空超时，已终止；未切换软件编码");
                }
            }
            if (!sink.started) return error("硬件编码器没有输出有效码流");
            try { muxer.stop(); } catch (Exception ignored) { }
            muxer.release();
            muxer = null;
            raw.close();
            raw = null;
            String metricsJson = "null";
            if (metrics) metricsJson = NativeCodecEngine.metricsYuv(yuvPath, pixelFormat,
                    preview.getAbsolutePath(), width, height, fps, report.getAbsolutePath());
            JSONObject details = new JSONObject()
                    .put("decoder", new JSONObject().put("mode", decoderMode)
                            .put("name", decoderName).put("pixelFormat", pixelFormat))
                    .put("encoder", new JSONObject().put("mode", "hardware")
                            .put("name", encoderName).put("pixelFormat", colorFormatName(encoderLayout.colorFormat)
                                    + " stride=" + encoderLayout.stride
                                    + " slice=" + encoderLayout.sliceHeight))
                    .put("stream", new JSONObject().put("bytes", rawFile.length())
                            .put("nalUnits", sink.nalUnits).put("keyframes", sink.keyframes));
            return new JSONObject().put("ok", true).put("decoderMode", decoderMode)
                    .put("encoderMode", "hardware").put("warning", JSONObject.NULL)
                    .put("codecDetails", details).put("elementaryStream", rawFile.getAbsolutePath())
                    .put("previewFile", preview.getAbsolutePath())
                    .put("metricsFile", metrics ? report.getAbsolutePath() : JSONObject.NULL)
                    .put("metrics", metrics ? new JSONObject(metricsJson) : JSONObject.NULL)
                    .put("frames", frames).toString();
        } catch (Throwable t) {
            return error(t.getClass().getSimpleName() + ": " + safeMessage(t));
        } finally {
            if (muxer != null) { try { muxer.stop(); } catch (Exception ignored) { } muxer.release(); }
            if (raw != null) { try { raw.close(); } catch (Exception ignored) { } }
            if (encoder != null) { try { encoder.stop(); } catch (Exception ignored) { } encoder.release(); }
        }
    }

    public static String transcode(String inputPath, String outputDir, String outputCodec,
                                   int width, int height, int fps, int bitrateKbps,
                                   boolean metrics, Listener listener) {
        try {
            File input = new File(inputPath);
            File dir = new File(outputDir);
            if (!input.isFile()) return error("输入文件不存在: " + inputPath);
            if (!dir.isDirectory() && !dir.mkdirs()) return error("无法创建输出目录: " + dir);
            return run(input, dir, outputCodec, width, height, fps, bitrateKbps, metrics, listener);
        } catch (Throwable t) {
            return error(t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    private static String run(File input, File dir, String outputCodec, int width, int height,
                              int fps, int bitrateKbps, boolean metrics, Listener listener)
            throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        FileInputStream inputStream = new FileInputStream(input);
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        FileOutputStream raw = null;
        FileOutputStream referenceOut = null;
        String rawPath = new File(dir, "h265".equals(outputCodec) || "hevc".equals(outputCodec)
                ? "output.hevc" : "output.h264").getAbsolutePath();
        File preview = new File(dir, "preview.mp4");
        File report = new File(dir, "metrics.json");
        File referenceFile = new File(dir, "pre_encode_reference.yuv");
        long frames = 0;
        long nextPts = 0;
        long duration = 0;
        boolean decoderEos = false;
        boolean encoderInputEosSent = false;
        boolean encoderOutputEos = false;
        int nalUnits = 0;
        int keyframes = 0;
        MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();
        String decoderName = "unknown";
        String encoderName = "unknown";
        int encoderColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
        int encoderStride = width;
        int encoderSliceHeight = height;
        int sourceWidth = 0;
        int sourceHeight = 0;
        String sourceMime = null;
        boolean encodingComplete = false;
        try {
            inputStream.getFD().sync();
            extractor.setDataSource(inputStream.getFD());
            int track = findVideoTrack(extractor);
            if (track < 0) return error("未找到视频轨道");
            extractor.selectTrack(track);
            MediaFormat sourceFormat = extractor.getTrackFormat(track);
            sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME);
            if (sourceMime == null || !sourceMime.startsWith("video/")) {
                return error("输入视频编码格式不受硬件解码器支持: " + sourceMime);
            }
            sourceWidth = sourceFormat.getInteger(MediaFormat.KEY_WIDTH);
            sourceHeight = sourceFormat.getInteger(MediaFormat.KEY_HEIGHT);
            duration = sourceFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? sourceFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            decoder = createHardwareDecoder(sourceMime);
            decoderName = decoder.getName();
            // Use linear decoder output for transcode. A Surface-backed
            // ImageReader can expose Qualcomm UBWC/tiled memory even when the
            // public format says YUV_420_888, which cannot be copied as rows.
            decoder.configure(sourceFormat, null, null, 0);

            String encoderMime = "hevc".equals(outputCodec) || "h265".equals(outputCodec)
                    ? "video/hevc" : "video/avc";
            EncoderConfig encoderConfig = createEncoder(encoderMime, width, height, fps, bitrateKbps);
            encoder = encoderConfig.codec;
            encoderColorFormat = encoderConfig.colorFormat;
            encoderName = encoder.getName();
            encoder.start();
            EncoderInputLayout encoderLayout = resolveInputLayout(encoder,
                    encoderColorFormat, width, height);
            encoderColorFormat = encoderLayout.colorFormat;
            encoderStride = encoderLayout.stride;
            encoderSliceHeight = encoderLayout.sliceHeight;
            decoder.start();
            raw = new FileOutputStream(rawPath);
            if (metrics) referenceOut = new FileOutputStream(referenceFile);
            muxer = new MediaMuxer(preview.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            EncoderSink sink = new EncoderSink(raw, muxer, outputCodec);
            notify(listener, 0, "Java MediaCodec 硬件解码/编码");
            int tightFrameSize = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2);
            ByteBuffer tightI420 = ByteBuffer.allocateDirect(tightFrameSize);
            byte[] tightFrame = new byte[tightFrameSize];

            boolean decoderInputEos = false;
            while (!encoderOutputEos) {
                if (!decoderInputEos) {
                    int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inputIndex);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, EOS);
                            decoderInputEos = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputIndex, 0, size, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                boolean madeFrame = false;
                while (true) {
                    int outputIndex = decoder.dequeueOutputBuffer(decoderInfo, 0);
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                    if (outputIndex < 0) continue;
                    if ((decoderInfo.flags & EOS) != 0) decoderEos = true;
                    Image image = decoder.getOutputImage(outputIndex);
                    try {
                        if (image != null && decoderInfo.size > 0) {
                            long pts = decoderInfo.presentationTimeUs;
                            if (pts >= nextPts - (500_000L / Math.max(1, fps))) {
                                if (encodeImage(encoder, encoderLayout, image, width, height, fps, pts,
                                        listener, frames, duration, referenceOut,
                                        tightI420, tightFrame)) {
                                    frames++;
                                    madeFrame = true;
                                    nextPts = pts + 1_000_000L / Math.max(1, fps);
                                }
                            }
                            if (!encoderOutputEos) encoderOutputEos = sink.drain(encoder, encoderInfo);
                        }
                    } finally {
                        if (image != null) image.close();
                        decoder.releaseOutputBuffer(outputIndex, false);
                    }
                    if (decoderEos) break;
                }
                if (decoderEos && !encoderInputEosSent) {
                    int inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        encoder.queueInputBuffer(inputIndex, 0, 0, Math.max(0, nextPts), EOS);
                        encoderInputEosSent = true;
                    }
                }
                encoderOutputEos = sink.drain(encoder, encoderInfo);
                if (!madeFrame && decoderInputEos && !decoderEos && !encoderInputEosSent) {
                    // Give a slow decoder another polling cycle instead of
                    // busy-spinning on devices with deep MediaCodec queues.
                    Thread.yield();
                }
            }
            if (!sink.started) throw new IOException("硬件编码器没有输出有效码流");
            nalUnits = sink.nalUnits;
            keyframes = sink.keyframes;
            encodingComplete = true;
            notify(listener, 90, "硬件码流写入完成");
        } finally {
            if (muxer != null) {
                try { muxer.stop(); } catch (Exception ignored) { }
            }
            if (muxer != null) muxer.release();
            if (raw != null) raw.close();
            if (referenceOut != null) referenceOut.close();
            if (decoder != null) { try { decoder.stop(); } catch (Exception ignored) { } decoder.release(); }
            if (encoder != null) { try { encoder.stop(); } catch (Exception ignored) { } encoder.release(); }
            extractor.release();
            inputStream.close();
            if (!encodingComplete && referenceFile.isFile() && !referenceFile.delete()) {
                referenceFile.deleteOnExit();
            }
        }

        String metricsJson = "null";
        if (metrics && preview.isFile()) {
            metricsJson = NativeCodecEngine.metricsYuv(referenceFile.getAbsolutePath(), "yuv420p",
                    preview.getAbsolutePath(), width, height, fps, report.getAbsolutePath());
            if (referenceFile.isFile() && !referenceFile.delete()) {
                referenceFile.deleteOnExit();
            }
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("decoderMode", "hardware");
        result.put("encoderMode", "hardware");
        result.put("warning", JSONObject.NULL);
        JSONObject details = new JSONObject();
        details.put("decoder", new JSONObject().put("mode", "hardware")
                .put("name", decoderName).put("pixelFormat", "yuv420_888"));
        details.put("encoder", new JSONObject().put("mode", "hardware")
                .put("name", encoderName).put("pixelFormat", colorFormatName(encoderColorFormat)
                        + " stride=" + encoderStride + " slice=" + encoderSliceHeight));
        details.put("stream", new JSONObject().put("bytes", new File(rawPath).length())
                .put("nalUnits", nalUnits).put("keyframes", keyframes));
        result.put("codecDetails", details);
        result.put("elementaryStream", rawPath);
        result.put("previewFile", preview.getAbsolutePath());
        result.put("metricsFile", metrics ? report.getAbsolutePath() : JSONObject.NULL);
        result.put("metrics", metrics ? new JSONObject(metricsJson) : JSONObject.NULL);
        result.put("frames", frames);
        return result.toString();
    }

    private static EncoderConfig createEncoder(String mime, int width, int height, int fps, int bitrateKbps)
            throws IOException {
        MediaCodec codec = null;
        MediaCodecInfo.CodecCapabilities selected = null;
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder() && isHardwareCodec(info)) {
                for (String type : info.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(type)) {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                        if (hasYuvInput(caps.colorFormats)) {
                            codec = MediaCodec.createByCodecName(info.getName());
                            selected = caps;
                            break;
                        }
                    }
                }
            }
            if (codec != null) break;
        }
        if (codec == null) throw new IOException("没有支持 YUV 输入的硬件编码器: " + mime);
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(16, bitrateKbps) * 1000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, fps));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, chooseYuv(selected.colorFormats));
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return new EncoderConfig(codec, chooseYuv(selected.colorFormats));
    }

    private static final class EncoderConfig {
        final MediaCodec codec;
        final int colorFormat;

        EncoderConfig(MediaCodec codec, int colorFormat) {
            this.codec = codec;
            this.colorFormat = colorFormat;
        }
    }

    /**
     * Some Qualcomm codecs accept the flexible request but silently negotiate
     * a concrete layout (for example 0x15/NV12). The input buffer must follow
     * this post-start format, not the requested format.
     */
    private static final class EncoderInputLayout {
        final int colorFormat;
        final int stride;
        final int sliceHeight;

        EncoderInputLayout(int colorFormat, int stride, int sliceHeight) {
            this.colorFormat = colorFormat;
            this.stride = stride;
            this.sliceHeight = sliceHeight;
        }
    }

    private static EncoderInputLayout resolveInputLayout(MediaCodec codec, int requested,
                                                          int width, int height) {
        int colorFormat = requested;
        int stride = width;
        int sliceHeight = height;
        try {
            MediaFormat input = codec.getInputFormat();
            if (input != null) {
                if (input.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
                    colorFormat = input.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                }
                if (input.containsKey("stride")) stride = input.getInteger("stride");
                if (input.containsKey("slice-height")) sliceHeight = input.getInteger("slice-height");
            }
        } catch (Throwable ignored) {
            // Older vendor codecs may not expose the input format. Keep the
            // negotiated request as the best available fallback.
        }
        // OMX byte-buffer encoders commonly omit their internal padding from
        // getInputFormat(). A 16-line slice alignment is required by Qualcomm
        // for heights such as 1080 (actual chroma starts after 1088 lines).
        stride = Math.max(stride, align(width, 16));
        sliceHeight = Math.max(sliceHeight, align(height, 16));
        return new EncoderInputLayout(colorFormat, Math.max(width, stride),
                Math.max(height, sliceHeight));
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
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
        throw new IOException("没有可用的硬件解码器: " + mime);
    }

    private static boolean isHardwareCodec(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated() && !info.isSoftwareOnly();
        }
        String name = info.getName().toLowerCase(java.util.Locale.US);
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.")
                || name.startsWith("c2.google.") || name.contains("ffmpeg")
                || name.contains("software") || name.endsWith(".sw"));
    }

    private static final class EncoderSink {
        final FileOutputStream raw;
        final MediaMuxer muxer;
        final String codec;
        boolean started;
        int track = -1;
        int nalUnits;
        int keyframes;
        long samples;

        EncoderSink(FileOutputStream raw, MediaMuxer muxer, String codec) {
            this.raw = raw;
            this.muxer = muxer;
            this.codec = codec;
        }

        boolean drain(MediaCodec encoder, MediaCodec.BufferInfo info) throws IOException {
            return drain(encoder, info, 0);
        }

        boolean drain(MediaCodec encoder, MediaCodec.BufferInfo info, long firstTimeoutUs)
                throws IOException {
            boolean eos = false;
            boolean first = true;
            while (true) {
                int outputIndex = encoder.dequeueOutputBuffer(info, first ? firstTimeoutUs : 0);
                first = false;
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (started) throw new IllegalStateException("编码器输出格式重复变化");
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    started = true;
                    writeCodecConfig(raw, encoder.getOutputFormat(), codec);
                    continue;
                }
                if (outputIndex < 0) continue;
                ByteBuffer encoded = encoder.getOutputBuffer(outputIndex);
                if (encoded != null && info.size > 0) {
                    encoded.position(info.offset);
                    encoded.limit(info.offset + info.size);
                    ByteBuffer sample = encoded.slice();
                    if ((info.flags & CODEC_CONFIG) != 0) {
                        writeAnnexB(raw, sample.duplicate(), codec, true);
                    } else {
                        writeAnnexB(raw, sample.duplicate(), codec, false);
                        int[] stats = updateNalStats(sample.duplicate(), codec,
                                (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                        nalUnits += stats[0];
                        keyframes += stats[1];
                        if (started) muxer.writeSampleData(track, sample.duplicate(), info);
                        samples++;
                    }
                }
                if ((info.flags & EOS) != 0) eos = true;
                encoder.releaseOutputBuffer(outputIndex, false);
                if (eos) break;
            }
            return eos;
        }
    }

    private static boolean hasYuvInput(int[] formats) {
        for (int format : formats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return true;
        }
        return false;
    }

    private static int chooseYuv(int[] formats) {
        for (int format : formats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return format;
        }
        for (int format : formats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return format;
        }
        for (int format : formats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return format;
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
    }

    private static boolean encodeImage(MediaCodec encoder, EncoderInputLayout layout, Image image,
                                       int width, int height,
                                       int fps, long pts, Listener listener, long frame, long duration,
                                       FileOutputStream referenceOut, ByteBuffer tightI420,
                                       byte[] tightFrame)
            throws IOException {
        int index = encoder.dequeueInputBuffer(TIMEOUT_US);
        if (index < 0) return false;
        ByteBuffer buffer = encoder.getInputBuffer(index);
        if (buffer == null) return false;
        tightI420.clear();
        copyImageAsYuv420p(image, tightI420, width, height);
        int visibleSize = tightI420.position();
        tightI420.flip();
        tightI420.get(tightFrame, 0, visibleSize);
        buffer.clear();
        putFrameForEncoder(buffer, tightFrame, false, layout, width, height);
        int size = buffer.position();
        encoder.queueInputBuffer(index, 0, size, pts, 0);
        if (referenceOut != null) referenceOut.write(tightFrame, 0, visibleSize);
        int percent = duration > 0 ? (int) Math.min(88, 10 + frame * 78 / Math.max(1, duration / (1_000_000L / Math.max(1, fps)))) : 10;
        notify(listener, percent, "Java MediaCodec 硬件转码");
        return true;
    }

    private static boolean isPlanarInput(int colorFormat) {
        return colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    }

    private static String colorFormatName(int colorFormat) {
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return "yuv420_planar";
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return "nv12";
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return "yuv420_flexible(i420)";
        return "0x" + Integer.toHexString(colorFormat);
    }

    private static void putFrameForEncoder(ByteBuffer output, byte[] frame,
                                           boolean sourceNv12, EncoderInputLayout layout,
                                           int width, int height) {
        boolean targetPlanar = isPlanarInput(layout.colorFormat);
        int[] packed = usableLayout(output, layout, width, height, targetPlanar);
        int stride = packed[0];
        int sliceHeight = packed[1];
        int requiredSize = packed[2];
        int ySize = width * height;
        int chromaSize = ((width + 1) / 2) * ((height + 1) / 2);
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;
        fillEncoderPadding(output, stride, sliceHeight, width, height, targetPlanar);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                output.put(row * stride + col, frame[row * width + col]);
            }
        }
        if (targetPlanar) {
            int chromaStride = (stride + 1) / 2;
            int chromaSliceHeight = (sliceHeight + 1) / 2;
            int uTarget = stride * sliceHeight;
            int vTarget = uTarget + chromaStride * chromaSliceHeight;
            for (int row = 0; row < chromaHeight; row++) {
                for (int col = 0; col < chromaWidth; col++) {
                    int sourceIndex = row * chromaWidth + col;
                    byte u = sourceNv12 ? frame[ySize + sourceIndex * 2]
                            : frame[ySize + sourceIndex];
                    byte v = sourceNv12 ? frame[ySize + sourceIndex * 2 + 1]
                            : frame[ySize + chromaSize + sourceIndex];
                    output.put(uTarget + row * chromaStride + col, u);
                    output.put(vTarget + row * chromaStride + col, v);
                }
            }
        } else {
            int uvTarget = stride * sliceHeight;
            for (int row = 0; row < chromaHeight; row++) {
                for (int col = 0; col < chromaWidth; col++) {
                    int sourceIndex = row * chromaWidth + col;
                    byte u = sourceNv12 ? frame[ySize + sourceIndex * 2]
                            : frame[ySize + sourceIndex];
                    byte v = sourceNv12 ? frame[ySize + sourceIndex * 2 + 1]
                            : frame[ySize + chromaSize + sourceIndex];
                    int target = uvTarget + row * stride + col * 2;
                    output.put(target, u);
                    output.put(target + 1, v);
                }
            }
        }
        output.position(requiredSize);
    }

    private static void copyImageForEncoder(Image image, ByteBuffer output,
                                            EncoderInputLayout layout,
                                            int targetWidth, int targetHeight) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) throw new IllegalArgumentException("解码器没有输出 YUV 三平面");
        int srcWidth = image.getWidth();
        int srcHeight = image.getHeight();
        byte[] y = readPlane(planes[0], srcWidth, srcHeight);
        int srcChromaWidth = (srcWidth + 1) / 2;
        int srcChromaHeight = (srcHeight + 1) / 2;
        byte[] u = readPlane(planes[1], srcChromaWidth, srcChromaHeight);
        byte[] v = readPlane(planes[2], srcChromaWidth, srcChromaHeight);
        boolean targetPlanar = isPlanarInput(layout.colorFormat);
        int[] packed = usableLayout(output, layout, targetWidth, targetHeight, targetPlanar);
        int stride = packed[0];
        int sliceHeight = packed[1];
        int requiredSize = packed[2];
        fillEncoderPadding(output, stride, sliceHeight, targetWidth, targetHeight, targetPlanar);
        for (int row = 0; row < targetHeight; row++) {
            int sy = Math.min(srcHeight - 1, row * srcHeight / Math.max(1, targetHeight));
            for (int col = 0; col < targetWidth; col++) {
                int sx = Math.min(srcWidth - 1, col * srcWidth / Math.max(1, targetWidth));
                output.put(row * stride + col, y[sy * srcWidth + sx]);
            }
        }
        int chromaWidth = (targetWidth + 1) / 2;
        int chromaHeight = (targetHeight + 1) / 2;
        if (targetPlanar) {
            int chromaStride = (stride + 1) / 2;
            int chromaSliceHeight = (sliceHeight + 1) / 2;
            int uTarget = stride * sliceHeight;
            int vTarget = uTarget + chromaStride * chromaSliceHeight;
            for (int row = 0; row < chromaHeight; row++) {
                int sy = Math.min(srcChromaHeight - 1,
                        row * srcChromaHeight / Math.max(1, chromaHeight));
                for (int col = 0; col < chromaWidth; col++) {
                    int sx = Math.min(srcChromaWidth - 1,
                            col * srcChromaWidth / Math.max(1, chromaWidth));
                    output.put(uTarget + row * chromaStride + col, u[sy * srcChromaWidth + sx]);
                    output.put(vTarget + row * chromaStride + col, v[sy * srcChromaWidth + sx]);
                }
            }
        } else {
            int uvTarget = stride * sliceHeight;
            for (int row = 0; row < chromaHeight; row++) {
                int sy = Math.min(srcChromaHeight - 1,
                        row * srcChromaHeight / Math.max(1, chromaHeight));
                for (int col = 0; col < chromaWidth; col++) {
                    int sx = Math.min(srcChromaWidth - 1,
                            col * srcChromaWidth / Math.max(1, chromaWidth));
                    int target = uvTarget + row * stride + col * 2;
                    output.put(target, u[sy * srcChromaWidth + sx]);
                    output.put(target + 1, v[sy * srcChromaWidth + sx]);
                }
            }
        }
        output.position(requiredSize);
    }

    private static int[] usableLayout(ByteBuffer output, EncoderInputLayout layout,
                                      int width, int height, boolean planar) {
        int stride = Math.max(width, layout.stride);
        int sliceHeight = Math.max(height, layout.sliceHeight);
        int required = encoderFrameSize(stride, sliceHeight, planar);
        if (required > output.capacity()) {
            stride = width;
            sliceHeight = height;
            required = encoderFrameSize(stride, sliceHeight, planar);
        }
        if (required > output.capacity()) {
            throw new IllegalArgumentException("编码器输入缓冲区不足: " + output.capacity()
                    + " < " + required);
        }
        return new int[]{stride, sliceHeight, required};
    }

    private static int encoderFrameSize(int stride, int sliceHeight, boolean planar) {
        int ySize = stride * sliceHeight;
        if (planar) return ySize + 2 * ((stride + 1) / 2) * ((sliceHeight + 1) / 2);
        return ySize + stride * ((sliceHeight + 1) / 2);
    }

    private static void fillEncoderPadding(ByteBuffer output, int stride, int sliceHeight,
                                           int width, int height, boolean planar) {
        int ySize = stride * sliceHeight;
        for (int row = 0; row < sliceHeight; row++) {
            int start = row * stride + (row < height ? width : 0);
            int end = (row + 1) * stride;
            for (int index = start; index < end; index++) output.put(index, (byte) 0);
        }
        int required = encoderFrameSize(stride, sliceHeight, planar);
        for (int index = ySize; index < required; index++) output.put(index, (byte) 128);
    }

    private static void copyImageAsNv12(Image image, ByteBuffer output, int targetWidth, int targetHeight) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) throw new IllegalArgumentException("解码器没有输出 YUV 三平面");
        int srcWidth = image.getWidth();
        int srcHeight = image.getHeight();
        byte[] y = readPlane(planes[0], srcWidth, srcHeight);
        byte[] u = readPlane(planes[1], (srcWidth + 1) / 2, (srcHeight + 1) / 2);
        byte[] v = readPlane(planes[2], (srcWidth + 1) / 2, (srcHeight + 1) / 2);
        for (int row = 0; row < targetHeight; row++) {
            int sy = Math.min(srcHeight - 1, row * srcHeight / Math.max(1, targetHeight));
            for (int col = 0; col < targetWidth; col++) {
                int sx = Math.min(srcWidth - 1, col * srcWidth / Math.max(1, targetWidth));
                output.put(y[sy * srcWidth + sx]);
            }
        }
        int chromaW = (targetWidth + 1) / 2;
        int chromaH = (targetHeight + 1) / 2;
        int srcChromaW = (srcWidth + 1) / 2;
        int srcChromaH = (srcHeight + 1) / 2;
        for (int row = 0; row < chromaH; row++) {
            int sy = Math.min(srcChromaH - 1, row * srcChromaH / Math.max(1, chromaH));
            for (int col = 0; col < chromaW; col++) {
                int sx = Math.min(srcChromaW - 1, col * srcChromaW / Math.max(1, chromaW));
                output.put(u[sy * srcChromaW + sx]);
                output.put(v[sy * srcChromaW + sx]);
            }
        }
    }

    private static void copyImageAsYuv420p(Image image, ByteBuffer output,
                                           int targetWidth, int targetHeight) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) throw new IllegalArgumentException("解码器没有输出 YUV 三平面");
        int srcWidth = image.getWidth();
        int srcHeight = image.getHeight();
        byte[] y = readPlane(planes[0], srcWidth, srcHeight);
        byte[] u = readPlane(planes[1], (srcWidth + 1) / 2, (srcHeight + 1) / 2);
        byte[] v = readPlane(planes[2], (srcWidth + 1) / 2, (srcHeight + 1) / 2);
        writeScaledPlane(output, y, srcWidth, srcHeight, targetWidth, targetHeight);
        int targetChromaW = (targetWidth + 1) / 2;
        int targetChromaH = (targetHeight + 1) / 2;
        int srcChromaW = (srcWidth + 1) / 2;
        int srcChromaH = (srcHeight + 1) / 2;
        writeScaledPlane(output, u, srcChromaW, srcChromaH, targetChromaW, targetChromaH);
        writeScaledPlane(output, v, srcChromaW, srcChromaH, targetChromaW, targetChromaH);
    }

    private static void writeScaledPlane(ByteBuffer output, byte[] source,
                                         int sourceWidth, int sourceHeight,
                                         int targetWidth, int targetHeight) {
        for (int row = 0; row < targetHeight; row++) {
            int sy = Math.min(sourceHeight - 1, row * sourceHeight / Math.max(1, targetHeight));
            for (int col = 0; col < targetWidth; col++) {
                int sx = Math.min(sourceWidth - 1, col * sourceWidth / Math.max(1, targetWidth));
                output.put(source[sy * sourceWidth + sx]);
            }
        }
    }

    private static boolean readFully(FileInputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) return offset == buffer.length;
            offset += read;
        }
        return true;
    }

    private static byte[] readPlane(Image.Plane plane, int width, int height) {
        ByteBuffer source = plane.getBuffer().duplicate();
        int base = source.position();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        byte[] result = new byte[width * height];
        for (int row = 0; row < height; row++) {
            int rowStart = Math.min(source.limit(), base + row * rowStride);
            for (int col = 0; col < width; col++) {
                int index = rowStart + col * pixelStride;
                result[row * width + col] = index < source.limit() ? source.get(index) : 0;
            }
        }
        return result;
    }

    private static int findVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    private static void writeCodecConfig(FileOutputStream raw, MediaFormat format, String codec)
            throws IOException {
        String[] keys = "hevc".equals(codec) || "h265".equals(codec)
                ? new String[]{"csd-0", "csd-1"} : new String[]{"csd-0", "csd-1"};
        for (String key : keys) {
            ByteBuffer data = format.getByteBuffer(key);
            if (data != null) writeAnnexB(raw, data.duplicate(), codec, true);
        }
    }

    private static void writeAnnexB(FileOutputStream out, ByteBuffer data, String codec, boolean config)
            throws IOException {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        if (hasStartCode(bytes)) {
            out.write(bytes);
            return;
        }
        int offset = 0;
        while (offset + 4 <= bytes.length) {
            int length = ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                    | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
            if (length <= 0 || offset + 4 + length > bytes.length) break;
            out.write(new byte[]{0, 0, 0, 1});
            out.write(bytes, offset + 4, length);
            offset += 4 + length;
        }
        if (offset == 0 && bytes.length > 0) {
            out.write(new byte[]{0, 0, 0, 1});
            out.write(bytes);
        }
    }

    private static boolean hasStartCode(byte[] data) {
        return data.length >= 4 && ((data[0] == 0 && data[1] == 0 && data[2] == 1)
                || (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1));
    }

    private static int[] updateNalStats(ByteBuffer data, String codec, boolean key) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        int count = 0;
        int idr = 0;
        int offset = 0;
        while (offset + 4 <= bytes.length) {
            int length = ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                    | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
            if (length <= 0 || offset + 4 + length > bytes.length) break;
            int header = bytes[offset + 4] & 0xff;
            int type = codec.equals("hevc") || codec.equals("h265")
                    ? ((header >> 1) & 0x3f) : (header & 0x1f);
            count++;
            if ((codec.equals("hevc") || codec.equals("h265"))
                    ? (type == 19 || type == 20 || type == 21) : type == 5) idr++;
            offset += 4 + length;
        }
        if (count == 0) count = 1;
        return new int[]{count, idr > 0 || key ? 1 : 0};
    }

    private static void notify(Listener listener, int percent, String stage) {
        if (listener != null) listener.onProgress(percent, stage);
    }

    private static String error(String text) {
        try { return new JSONObject().put("ok", false).put("error", text).toString(); }
        catch (Exception ignored) { return "{\"ok\":false,\"error\":\"hardware transcode failed\"}"; }
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? "unknown error" : message;
    }
}

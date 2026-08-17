package com.example.codeclab;

public final class NativeCodecEngine {
    static {
        System.loadLibrary("codec_core");
    }

    public interface Listener {
        void onProgress(int percent, String stage);
    }

    private NativeCodecEngine() {}

    public static native String capabilities();
    public static native String probe(String inputPath);
    public static native String transcode(String requestJson, Listener listener);
    public static native String decodeYuv(String requestJson, Listener listener);
    public static native String encodeYuv(String requestJson, Listener listener);
    public static native String metrics(String inputPath, String previewPath,
                                        int width, int height, int fps, String reportPath);
    public static native String metricsYuv(String referenceYuvPath, String pixelFormat,
                                           String previewPath, int width, int height, int fps,
                                           String reportPath);
    public static native void cancel();
}

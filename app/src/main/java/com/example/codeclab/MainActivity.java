package com.example.codeclab;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.SurfaceTexture;
import android.graphics.Matrix;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<InputItem> inputs = new ArrayList<>();
    private ActivityResultLauncher<Intent> picker;
    private TextView fileSummary;
    private LinearLayout inputListContainer;
    private TextView statusText;
    private LinearLayout results;
    private ProgressBar progress;
    private Spinner decodeMode;
    private Spinner codec;
    private Spinner encodeMode;
    private Spinner sourcePicker;
    private Spinner outputPicker;
    private EditText widthInput;
    private EditText heightInput;
    private EditText fpsInput;
    private EditText bitrateInput;
    private CheckBox metricsCheck;
    private TextureView videoView;
    private SurfaceView hardwareVideoView;
    private SurfaceHolder hardwareHolder;
    private HardwareVideoPlayer hardwarePlayer;
    private boolean hardwareSurfaceReady;
    private boolean hardwarePlaybackActive;
    private Surface videoSurface;
    private MediaPlayer mediaPlayer;
    private Uri pendingVideoUri;
    private File pendingVideoFile;
    private int videoWidth;
    private int videoHeight;
    private File lastPreview;
    private final List<File> outputPreviews = new ArrayList<>();
    private ArrayAdapter<String> sourceAdapter;
    private ArrayAdapter<String> outputAdapter;
    private boolean batchRunning;
    private double psnrSum;
    private int psnrCount;
    private double vmafSum;
    private int vmafCount;
    private long metricFrameCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        bindViews();
        setupSpinners();
        setupPlaybackSelectors();
        setupPicker();
        setupPlayer();
        restoreImportedFiles();

        findViewById(R.id.importButton).setOnClickListener(v -> openPicker());
        findViewById(R.id.startButton).setOnClickListener(v -> startBatch());
        findViewById(R.id.playSourceButton).setOnClickListener(v -> playSource());
        findViewById(R.id.playOutputButton).setOnClickListener(v -> playOutput());

        try {
            JSONObject caps = new JSONObject(NativeCodecEngine.capabilities());
            statusText.setText("Native core " + caps.optString("version", "unknown") + " · "
                    + (caps.optBoolean("ffmpeg") ? "FFmpeg 已启用" : "等待接入 FFmpeg 预编译包")
                    + (caps.optBoolean("vmaf") ? " · VMAF 已启用" : " · VMAF 不可用"));
        } catch (Exception e) {
            statusText.setText("Native core 加载失败: " + e.getMessage());
        }
    }

    private void bindViews() {
        fileSummary = findViewById(R.id.fileSummary);
        inputListContainer = findViewById(R.id.inputListContainer);
        statusText = findViewById(R.id.statusText);
        results = findViewById(R.id.resultsContainer);
        progress = findViewById(R.id.progress);
        decodeMode = findViewById(R.id.decodeMode);
        codec = findViewById(R.id.codec);
        encodeMode = findViewById(R.id.encodeMode);
        sourcePicker = findViewById(R.id.sourcePicker);
        outputPicker = findViewById(R.id.outputPicker);
        widthInput = findViewById(R.id.widthInput);
        heightInput = findViewById(R.id.heightInput);
        fpsInput = findViewById(R.id.fpsInput);
        bitrateInput = findViewById(R.id.bitrateInput);
        metricsCheck = findViewById(R.id.metricsCheck);
        videoView = findViewById(R.id.videoView);
        hardwareVideoView = findViewById(R.id.hardwareVideoView);
    }

    private void setupSpinners() {
        setChoices(decodeMode, new String[]{"硬件解码（Java MediaCodec）", "软件解码（FFmpeg）"});
        setChoices(codec, new String[]{"H.264 / AVC", "H.265 / HEVC"});
        setChoices(encodeMode, new String[]{"硬件编码（Java MediaCodec）", "软件编码（x264/x265）"});
        // There is intentionally no automatic fallback. The two selectors
        // map to four strict, independently routed decode/encode combinations.
        encodeMode.setSelection(0);
        decodeMode.setSelection(0);
    }

    private void setChoices(Spinner spinner, String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values);
        spinner.setAdapter(adapter);
    }

    private void setupPlaybackSelectors() {
        sourceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        outputAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        sourcePicker.setAdapter(sourceAdapter);
        outputPicker.setAdapter(outputAdapter);
    }

    private void setupPicker() {
        picker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            Intent data = result.getData();
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            importUris(uris);
        });
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("video/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addCategory(Intent.CATEGORY_OPENABLE);
        picker.launch(intent);
    }

    private void importUris(List<Uri> uris) {
        statusText.setText("正在导入 " + uris.size() + " 个文件…");
        worker.execute(() -> {
            File inputDir = inputDirectory();
            //noinspection ResultOfMethodCallIgnored
            inputDir.mkdirs();
            List<InputItem> imported = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    String displayName = queryName(uri);
                    File target = uniqueFile(inputDir, displayName);
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(target)) {
                        if (in == null) throw new IllegalStateException("无法读取 " + uri);
                        byte[] buffer = new byte[1024 * 1024];
                        int read;
                        while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
                    }
                    imported.add(new InputItem(displayName, target));
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
            runOnUiThread(() -> {
                inputs.addAll(imported);
                refreshInputSelectionUi();
                statusText.setText(imported.isEmpty() ? "没有成功导入新文件" :
                        "已导入 " + imported.size() + " 个新视频，共 " + inputs.size() + " 个");
            });
        });
    }

    private void restoreImportedFiles() {
        File inputDir = inputDirectory();
        File[] files = inputDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.US);
            return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")
                    || lower.endsWith(".m4v") || lower.endsWith(".webm");
        });
        if (files == null || files.length == 0) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File file : files) inputs.add(new InputItem(file.getName(), file));
        refreshInputSelectionUi();
        statusText.setText("已恢复 " + inputs.size() + " 个视频");
    }

    private void refreshInputSelectionUi() {
        fileSummary.setText(inputs.isEmpty() ? "尚未选择文件" :
                "已选择 " + inputs.size() + " 个视频\n" + summarize(inputs));
        inputListContainer.removeAllViews();
        sourceAdapter.clear();
        for (int i = 0; i < inputs.size(); i++) {
            InputItem item = inputs.get(i);
            sourceAdapter.add((i + 1) + ". " + item.displayName);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(8, 2, 0, 2);
            TextView name = new TextView(this);
            name.setText((i + 1) + ". " + item.displayName);
            name.setTextColor(0xff424b5f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button remove = new Button(this);
            remove.setText("取消选择");
            int index = i;
            remove.setOnClickListener(v -> removeInput(index));
            remove.setEnabled(!batchRunning);
            row.addView(remove, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            inputListContainer.addView(row);
        }
        sourceAdapter.notifyDataSetChanged();
        if (inputs.isEmpty()) sourcePicker.setSelection(0);
    }

    private void removeInput(int index) {
        if (batchRunning) {
            Toast.makeText(this, "批量处理中，暂不能取消文件", Toast.LENGTH_SHORT).show();
            return;
        }
        if (index < 0 || index >= inputs.size()) return;
        InputItem removed = inputs.remove(index);
        if (removed.file.isFile() && !removed.file.delete()) {
            Log.w("CodecLab", "Unable to delete cancelled input " + removed.file);
        }
        refreshInputSelectionUi();
        statusText.setText("已取消选择: " + removed.displayName);
    }

    private File inputDirectory() {
        // Keep the input path in the app's private data directory. FFmpeg is
        // loaded in-process, so it has the same access rights as Java; using
        // the emulated-storage alias can produce false ENOENT on older OEM ROMs.
        File dir = new File(getFilesDir(), "inputs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }


    private String queryName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return sanitize(cursor.getString(index));
            }
        }
        return "video_" + System.currentTimeMillis() + ".mp4";
    }

    private static String sanitize(String name) {
        return name == null ? "video.mp4" : name.replaceAll("[^a-zA-Z0-9._()\\-\\u4e00-\\u9fa5]", "_");
    }

    private static File uniqueFile(File dir, String name) {
        File file = new File(dir, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        return new File(dir, base + "_" + System.nanoTime() + ext);
    }

    private static String summarize(List<InputItem> items) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(items.size(), 4); i++) {
            if (i > 0) text.append('\n');
            text.append("• ").append(items.get(i).displayName);
        }
        if (items.size() > 4) text.append("\n…另有 ").append(items.size() - 4).append(" 个文件");
        return text.toString();
    }

    private void startBatch() {
        if (inputs.isEmpty()) {
            Toast.makeText(this, "请先导入视频", Toast.LENGTH_SHORT).show();
            return;
        }
        final int width = bounded(widthInput, 720, 16, 7680);
        final int height = bounded(heightInput, 1280, 16, 4320);
        final int fps = bounded(fpsInput, 30, 1, 240);
        final int bitrate = bounded(bitrateInput, 4000, 16, 100000);
        final String outputCodec = codec.getSelectedItemPosition() == 0 ? "h264" : "hevc";
        final String decoder = mode(decodeMode.getSelectedItemPosition(), true);
        final String encoder = mode(encodeMode.getSelectedItemPosition(), false);
        final boolean calculateMetrics = metricsCheck.isChecked();
        // Decode and encode choices are independent. Every branch below is
        // strict: a failed implementation reports its error and never changes
        // the selected mode behind the user's back.
        final boolean hardwareDecode = "hardware".equals(decoder);
        final boolean hardwareEncode = "hardware".equals(encoder);
        final List<InputItem> batchInputs = new ArrayList<>(inputs);
        batchRunning = true;
        findViewById(R.id.startButton).setEnabled(false);
        results.removeAllViews();
        refreshInputSelectionUi();
        lastPreview = null;
        outputPreviews.clear();
        outputAdapter.clear();
        outputAdapter.notifyDataSetChanged();
        resetMetricAggregate();
        progress.setProgress(0);

        worker.execute(() -> {
            for (int i = 0; i < batchInputs.size(); i++) {
                InputItem item = batchInputs.get(i);
                int index = i;
                try {
                    // Keep native output in the app-private data directory.
                    // Some older OEM ROMs expose getExternalFilesDir() as a
                    // read-only emulated-storage alias to native code.
                    File jobDir = new File(getFilesDir(), "outputs/" + stripExt(item.displayName)
                            + "_" + System.currentTimeMillis());
                    //noinspection ResultOfMethodCallIgnored
                    jobDir.mkdirs();
                    String raw;
                    if (hardwareDecode && hardwareEncode) {
                        raw = MediaCodecTranscoder.transcode(nativePath(item.file), nativePath(jobDir),
                                outputCodec, width, height, fps, bitrate, calculateMetrics,
                                (percent, stage) -> runOnUiThread(() -> {
                                    int total = (index * 100 + percent) / batchInputs.size();
                                    progress.setProgress(total);
                                    statusText.setText((index + 1) + "/" + inputs.size() + " · " + stage);
                                }));
                    } else if (!hardwareDecode && hardwareEncode) {
                        raw = runSoftwareDecodeHardwareEncode(item.file, jobDir, outputCodec,
                                width, height, fps, bitrate, calculateMetrics, index, batchInputs.size());
                    } else if (hardwareDecode) {
                        raw = runHardwareDecodeSoftwareEncode(item.file, jobDir, outputCodec,
                                width, height, fps, bitrate, calculateMetrics,
                                index, batchInputs.size());
                    } else {
                        raw = runNativeTranscode(item.file, jobDir, outputCodec, width, height,
                                fps, bitrate, calculateMetrics, index, batchInputs.size());
                    }
                    JSONObject response = new JSONObject(raw);
                    runOnUiThread(() -> addResult(item, response));
                } catch (Exception e) {
                    runOnUiThread(() -> addError(item, e.getMessage()));
                }
            }
            runOnUiThread(() -> {
                batchRunning = false;
                refreshInputSelectionUi();
                showMetricAggregate();
                progress.setProgress(100);
                statusText.setText("批量任务完成");
                findViewById(R.id.startButton).setEnabled(true);
            });
        });
    }

    private String runNativeTranscode(File inputFile, File jobDir, String outputCodec,
                                      int width, int height, int fps, int bitrate,
                                      boolean metrics, int index, int totalJobs) throws Exception {
        JSONObject req = new JSONObject()
                .put("input", nativePath(inputFile))
                .put("outputDir", nativePath(jobDir))
                .put("outputCodec", outputCodec)
                .put("decoder", "software")
                .put("encoder", "software")
                .put("width", width).put("height", height)
                .put("fps", fps).put("bitrateKbps", bitrate)
                .put("metrics", metrics);
        try (ParcelFileDescriptor nativeInput = ParcelFileDescriptor.open(inputFile,
                ParcelFileDescriptor.MODE_READ_ONLY);
             ParcelFileDescriptor nativeOutput = ParcelFileDescriptor.open(jobDir,
                     ParcelFileDescriptor.MODE_READ_ONLY)) {
            req.put("inputFd", nativeInput.getFd());
            req.put("outputDirFd", nativeOutput.getFd());
            return NativeCodecEngine.transcode(req.toString(), (percent, stage) ->
                    runOnUiThread(() -> {
                        int overall = (index * 100 + percent) / Math.max(1, totalJobs);
                        progress.setProgress(overall);
                        statusText.setText((index + 1) + "/" + inputs.size() + " · " + stage);
                    }));
        }
    }

    private String runSoftwareDecodeHardwareEncode(File inputFile, File jobDir,
                                                    String outputCodec, int width, int height,
                                                    int fps, int bitrate, boolean metrics,
                                                    int index, int totalJobs) throws Exception {
        File yuv = new File(jobDir, "intermediate.yuv");
        try {
            JSONObject decodeRequest = new JSONObject()
                    .put("input", nativePath(inputFile))
                    .put("outputFile", nativePath(yuv))
                    // The hardware encoder negotiates its actual input color
                    // format. Store planar I420 here; MediaCodecTranscoder
                    // converts it to NV12 only when the codec explicitly
                    // advertises a semi-planar input format.
                    .put("pixelFormat", "yuv420p")
                    .put("width", width).put("height", height).put("fps", fps);
            String decoded = NativeCodecEngine.decodeYuv(decodeRequest.toString(), (percent, stage) ->
                    updateBatchProgress(index, totalJobs, percent / 2, stage));
            JSONObject decodeResult = new JSONObject(decoded);
            if (!decodeResult.optBoolean("ok")) return decoded;
            return MediaCodecTranscoder.encodeYuv(nativePath(yuv), nativePath(inputFile),
                    nativePath(jobDir), outputCodec, width, height, fps, bitrate, metrics,
                    "software", decodeResult.optString("decoderName", "ffmpeg"), "yuv420p",
                    (percent, stage) -> updateBatchProgress(index, totalJobs, 50 + percent / 2, stage));
        } finally {
            if (yuv.isFile() && !yuv.delete()) Log.w("CodecLab", "Unable to remove " + yuv);
        }
    }

    private String runHardwareDecodeSoftwareEncode(File inputFile, File jobDir,
                                                    String outputCodec, int width, int height,
                                                    int fps, int bitrate, boolean metrics,
                                                    int index, int totalJobs) throws Exception {
        File yuv = new File(jobDir, "intermediate.yuv");
        try {
            String decoded = MediaCodecTranscoder.decodeToYuv(nativePath(inputFile), nativePath(yuv),
                    width, height, fps, "yuv420p",
                    (percent, stage) -> updateBatchProgress(index, totalJobs, percent / 2, stage));
            JSONObject decodeResult = new JSONObject(decoded);
            if (!decodeResult.optBoolean("ok")) return decoded;
            JSONObject encodeRequest = new JSONObject()
                    .put("yuvFile", nativePath(yuv))
                    .put("input", nativePath(inputFile))
                    .put("outputDir", nativePath(jobDir))
                    .put("outputCodec", outputCodec)
                    .put("pixelFormat", "yuv420p")
                    .put("decoderMode", "hardware")
                    .put("decoderName", decodeResult.optString("decoderName", "MediaCodec"))
                    .put("width", width).put("height", height).put("fps", fps)
                    .put("bitrateKbps", bitrate).put("metrics", metrics);
            return NativeCodecEngine.encodeYuv(encodeRequest.toString(), (percent, stage) ->
                    updateBatchProgress(index, totalJobs, 50 + percent / 2, stage));
        } finally {
            if (yuv.isFile() && !yuv.delete()) Log.w("CodecLab", "Unable to remove " + yuv);
        }
    }

    private void updateBatchProgress(int index, int totalJobs, int percent, String stage) {
        runOnUiThread(() -> {
            int overall = (index * 100 + Math.max(0, Math.min(100, percent)))
                    / Math.max(1, totalJobs);
            progress.setProgress(overall);
            statusText.setText((index + 1) + "/" + inputs.size() + " · " + stage);
        });
    }

    private int positive(EditText field, int fallback) {
        try {
            int value = Integer.parseInt(field.getText().toString());
            return value > 0 ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int bounded(EditText field, int fallback, int minimum, int maximum) {
        int value = positive(field, fallback);
        return value < minimum || value > maximum ? fallback : value;
    }

    private static String mode(int position, boolean decoder) {
        return position == 0 ? "hardware" : "software";
    }

    private void addResult(InputItem input, JSONObject response) {
        boolean ok = response.optBoolean("ok");
        TextView view = resultView();
        if (!ok) {
            view.setText("✕ " + input.displayName + "\n" + response.optString("error", "处理失败"));
            results.addView(view);
            return;
        }
        JSONObject metrics = response.optJSONObject("metrics");
        accumulateMetrics(metrics);
        String previewPath = response.optString("previewFile", "");
        File previewFile = resolveNativeFile(previewPath);
        if (previewFile != null) addOutputPreview(input.displayName, previewFile);
        boolean vmafAvailable = metrics != null && metrics.optBoolean("vmafAvailable", false);
        String score = metrics == null ? "" :
                "\nPSNR " + metricText(metrics, "psnr") + " dB · VMAF "
                        + (vmafAvailable ? metricText(metrics, "vmaf") : "N/A（未内置 libvmaf）");
        String encoderInfo = "\n解码模式: " + response.optString("decoderMode", "software")
                + " · 编码模式: " + response.optString("encoderMode", "software");
        String warning = response.optString("warning", "");
        if (!warning.isEmpty() && !"null".equals(warning)) encoderInfo += "（" + warning + "）";
        String codecDetails = formatCodecDetails(response.optJSONObject("codecDetails"));
        String metricsPath = response.isNull("metricsFile") ? ""
                : javaPath(response.optString("metricsFile"));
        view.setText("✓ " + input.displayName + score + "\n"
                + encoderInfo + codecDetails + "\n" + javaPath(response.optString("elementaryStream")) + "\n"
                + metricsPath);
        LinearLayout card = resultCard();
        card.addView(view);
        results.addView(card);
    }

    private static String formatCodecDetails(JSONObject details) {
        if (details == null) return "";
        JSONObject decoder = details.optJSONObject("decoder");
        JSONObject encoder = details.optJSONObject("encoder");
        JSONObject stream = details.optJSONObject("stream");
        String decoderText = decoder == null ? "N/A" :
                decoder.optString("name", "unknown") + " / "
                        + decoder.optString("pixelFormat", "unknown");
        String encoderText = encoder == null ? "N/A" :
                encoder.optString("name", "unknown") + " / "
                        + encoder.optString("pixelFormat", "unknown");
        String streamText = stream == null ? "N/A" :
                stream.optLong("bytes", 0) + " bytes, NAL "
                        + stream.optInt("nalUnits", 0) + ", 关键帧 "
                        + stream.optInt("keyframes", 0);
        return "\n硬编解码解析: 解码器 " + decoderText
                + " · 编码器 " + encoderText
                + "\n裸流解析: " + streamText;
    }

    private void addError(InputItem input, String error) {
        TextView view = resultView();
        view.setText("✕ " + input.displayName + "\n" + error);
        results.addView(view);
    }

    private void resetMetricAggregate() {
        psnrSum = 0.0;
        psnrCount = 0;
        vmafSum = 0.0;
        vmafCount = 0;
        metricFrameCount = 0L;
        TextView aggregate = findViewById(R.id.aggregateMetrics);
        if (aggregate != null) {
            aggregate.setVisibility(android.view.View.GONE);
            aggregate.setText("");
        }
    }

    private void accumulateMetrics(JSONObject metrics) {
        if (metrics == null) return;
        metricFrameCount += Math.max(0L, metrics.optLong("frames", 0L));
        double psnr = metrics.optDouble("psnr", Double.NaN);
        if (Double.isFinite(psnr)) {
            psnrSum += psnr;
            psnrCount++;
        }
        if (metrics.optBoolean("vmafAvailable", false)) {
            double vmaf = metrics.optDouble("vmaf", Double.NaN);
            if (Double.isFinite(vmaf)) {
                vmafSum += vmaf;
                vmafCount++;
            }
        }
    }

    private void showMetricAggregate() {
        TextView aggregate = findViewById(R.id.aggregateMetrics);
        if (aggregate == null || (psnrCount == 0 && vmafCount == 0)) return;
        String psnr = psnrCount == 0 ? "N/A" : String.format(Locale.US, "%.3f dB", psnrSum / psnrCount);
        String vmaf = vmafCount == 0 ? "N/A" : String.format(Locale.US, "%.3f", vmafSum / vmafCount);
        File reportFile = new File(getFilesDir(), "outputs/batch_metrics_average.json");
        try {
            File parent = reportFile.getParentFile();
            if (parent != null) parent.mkdirs();
            JSONObject report = new JSONObject()
                    .put("metricBasis", "pre_encode_yuv_vs_decoded_output")
                    .put("psnr", psnrCount == 0 ? JSONObject.NULL : psnrSum / psnrCount)
                    .put("vmaf", vmafCount == 0 ? JSONObject.NULL : vmafSum / vmafCount)
                    .put("psnrFiles", psnrCount)
                    .put("vmafFiles", vmafCount)
                    .put("frames", metricFrameCount);
            try (FileOutputStream output = new FileOutputStream(reportFile)) {
                output.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w("CodecLab", "Unable to write batch metric summary", e);
        }
        aggregate.setText("批量平均客观指标（按文件平均）\n"
                + "PSNR " + psnr + " · VMAF " + vmaf
                + "\n有效文件：PSNR " + psnrCount + " 个，VMAF " + vmafCount
                + " 个 · 统计帧数 " + metricFrameCount
                + "\n汇总报告: " + javaPath(reportFile.getAbsolutePath()));
        aggregate.setVisibility(android.view.View.VISIBLE);
    }

    private TextView resultView() {
        TextView view = new TextView(this);
        view.setTextColor(0xff30394d);
        view.setTextSize(14);
        view.setPadding(14, 14, 14, 14);
        view.setBackgroundColor(0xffffffff);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 8;
        view.setLayoutParams(lp);
        return view;
    }

    private LinearLayout resultCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xffffffff);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 8;
        card.setLayoutParams(lp);
        return card;
    }

    private static String metricText(JSONObject metrics, String key) {
        if (metrics == null || metrics.isNull(key)) return "N/A";
        double value = metrics.optDouble(key, Double.NaN);
        return Double.isFinite(value) ? String.format(Locale.US, "%.3f", value) : "N/A";
    }

    private void setupPlayer() {
        hardwarePlayer = new HardwareVideoPlayer();
        hardwareVideoView.setVisibility(android.view.View.INVISIBLE);
        hardwareHolder = hardwareVideoView.getHolder();
        hardwareHolder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                hardwareSurfaceReady = true;
                if (pendingVideoFile != null && hardwareVideoView.getVisibility()
                        == android.view.View.VISIBLE && !hardwarePlaybackActive) {
                    File file = pendingVideoFile;
                    hardwareVideoView.post(() -> {
                        if (file.equals(pendingVideoFile)) startHardwarePlayback(file);
                    });
                }
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format,
                                                 int width, int height) {
                applyVideoAspect();
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                hardwareSurfaceReady = false;
                hardwarePlaybackActive = false;
                hardwarePlayer.stop();
            }
        });
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                videoSurface = new Surface(texture);
                if (pendingVideoUri != null) preparePlayer(pendingVideoUri);
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                applyVideoAspect();
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                releasePlayer();
                if (videoSurface != null) {
                    videoSurface.release();
                    videoSurface = null;
                }
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
    }

    private void playSource() {
        if (inputs.isEmpty()) return;
        int index = sourcePicker.getSelectedItemPosition();
        if (index < 0 || index >= inputs.size()) index = 0;
        playFile(inputs.get(index).file);
    }

    private void playOutput() {
        if (outputPreviews.isEmpty()) {
            Toast.makeText(this, "尚无可播放的编码后视频", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = outputPicker.getSelectedItemPosition();
        if (index < 0 || index >= outputPreviews.size()) index = 0;
        lastPreview = outputPreviews.get(index);
        playFile(lastPreview);
    }

    private void addOutputPreview(String inputName, File preview) {
        if (!preview.isFile() || !preview.canRead()) return;
        if (outputPreviews.contains(preview)) return;
        outputPreviews.add(preview);
        String label = inputName == null || inputName.isEmpty()
                ? preview.getParentFile().getName()
                : inputName + " · " + preview.getParentFile().getName();
        outputAdapter.add(label);
        outputAdapter.notifyDataSetChanged();
        lastPreview = preview;
    }

    private void playFile(File file) {
        statusText.setText("准备播放: " + (file == null ? "空文件" : file.getName()));
        Log.d("CodecLab", "playFile path=" + file + " exists="
                + (file != null && file.exists()) + " readable="
                + (file != null && file.canRead()));
        if (file == null || !file.exists() || !file.isFile() || !file.canRead()) {
            Toast.makeText(this, "视频文件不可读: " + file, Toast.LENGTH_LONG).show();
            return;
        }
        pendingVideoFile = file;
        pendingVideoUri = null;
        hardwarePlaybackActive = false;
        hardwarePlayer.stop();
        stopMediaPlayer();
        try {
            int[] displaySize = HardwareVideoPlayer.probeDisplaySize(file);
            videoWidth = displaySize[0];
            videoHeight = displaySize[1];
        } catch (Exception error) {
            videoWidth = 0;
            videoHeight = 0;
            startFallbackPlayback(file, "读取视频尺寸失败: " + error.getMessage());
            return;
        }
        showHardwareSurface();
        if (hardwareSurfaceReady) {
            // Let the fitted SurfaceView size reach SurfaceFlinger before the
            // decoder is allowed to render its first output buffer.
            hardwareVideoView.post(() -> {
                if (file.equals(pendingVideoFile)) startHardwarePlayback(file);
            });
        } else {
            statusText.setText("等待硬件播放 Surface");
        }
    }

    private void startHardwarePlayback(File file) {
        if (!hardwareSurfaceReady || hardwareHolder == null || hardwarePlaybackActive) return;
        hardwarePlaybackActive = true;
        hardwarePlayer.stop();
        statusText.setText("准备硬件播放: " + file.getName());
        hardwarePlayer.play(file, hardwareHolder.getSurface(), new HardwareVideoPlayer.Listener() {
            @Override public void onVideoSize(int width, int height) {
                runOnUiThread(() -> {
                    if (file.equals(pendingVideoFile)) {
                        videoWidth = width;
                        videoHeight = height;
                        applyVideoAspect();
                    }
                });
            }

            @Override public void onStarted(String decoderName) {
                runOnUiThread(() -> {
                    if (file.equals(pendingVideoFile)) {
                        statusText.setText("硬件播放中 · " + decoderName);
                        hardwareVideoView.setKeepScreenOn(true);
                    }
                });
            }

            @Override public void onCompleted() {
                runOnUiThread(() -> {
                    if (file.equals(pendingVideoFile)) {
                        hardwarePlaybackActive = false;
                        statusText.setText("播放完成");
                        hardwareVideoView.setKeepScreenOn(false);
                    }
                });
            }

            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    if (file.equals(pendingVideoFile)) {
                        hardwarePlaybackActive = false;
                        startFallbackPlayback(file, error);
                    }
                });
            }
        });
    }

    private void startFallbackPlayback(File file, String hardwareError) {
        hardwarePlaybackActive = false;
        hardwarePlayer.stop();
        showFallbackSurface();
        statusText.setText("硬件播放不可用，切换系统播放器: " + hardwareError);
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Log.d("CodecLab", "playFile uri=" + uri);
            pendingVideoUri = uri;
            if (videoSurface != null) preparePlayer(uri);
        } catch (Exception e) {
            Log.e("CodecLab", "FileProvider playback setup failed", e);
            // FileProvider is the robust path for MediaPlayer, but retain a
            // direct file fallback for vendor players that reject content URIs.
            try {
                pendingVideoUri = Uri.fromFile(file);
                if (videoSurface != null) preparePlayer(pendingVideoUri);
            } catch (Exception ignored) {
                Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showHardwareSurface() {
        videoView.setVisibility(android.view.View.INVISIBLE);
        applyHardwareVideoAspect();
        hardwareVideoView.setVisibility(android.view.View.VISIBLE);
        hardwareVideoView.setKeepScreenOn(false);
    }

    private void showFallbackSurface() {
        hardwareVideoView.setVisibility(android.view.View.INVISIBLE);
        videoView.setVisibility(android.view.View.VISIBLE);
        videoView.setKeepScreenOn(false);
        videoWidth = 0;
        videoHeight = 0;
    }

    private void preparePlayer(Uri uri) {
        stopMediaPlayer();
        try {
            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            player.setSurface(videoSurface);
            player.setOnPreparedListener(mp -> {
                mp.start();
                statusText.setText("播放中");
                videoView.setKeepScreenOn(true);
            });
            player.setOnVideoSizeChangedListener((mp, width, height) -> {
                videoWidth = width;
                videoHeight = height;
                applyVideoAspect();
            });
            player.setOnCompletionListener(mp -> {
                statusText.setText("播放完成");
                videoView.setKeepScreenOn(false);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                statusText.setText("播放失败（MediaPlayer " + what + "/" + extra + "）");
                Toast.makeText(this, "视频无法播放，请检查 MP4 编码格式", Toast.LENGTH_LONG).show();
                return true;
            });
            player.setDataSource(this, uri);
            player.prepareAsync();
        } catch (Exception e) {
            Log.e("CodecLab", "MediaPlayer prepare failed", e);
            statusText.setText("播放失败: " + e.getMessage());
            releasePlayer();
        }
    }

    private void stopMediaPlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) { }
            mediaPlayer.reset();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void releasePlayer() {
        hardwarePlaybackActive = false;
        hardwarePlayer.stop();
        stopMediaPlayer();
        hardwareVideoView.setKeepScreenOn(false);
        videoView.setKeepScreenOn(false);
    }

    private void applyVideoAspect() {
        if (videoWidth <= 0 || videoHeight <= 0) return;
        if (hardwareVideoView.getVisibility() == android.view.View.VISIBLE) {
            applyHardwareVideoAspect();
            return;
        }
        if (videoView == null || videoView.getWidth() <= 0 || videoView.getHeight() <= 0) return;
        float viewWidth = videoView.getWidth();
        float viewHeight = videoView.getHeight();
        float fitScale = Math.min(viewWidth / videoWidth, viewHeight / videoHeight);
        float fittedWidth = videoWidth * fitScale;
        float fittedHeight = videoHeight * fitScale;
        Matrix transform = new Matrix();
        // Fit inside the preview area and keep the source aspect ratio. The
        // FrameLayout background supplies letterbox bars instead of stretching.
        // TextureView already maps its texture to the view bounds; scale the
        // two axes around the center to add only the required letterbox bars.
        transform.setScale(fittedWidth / viewWidth, fittedHeight / viewHeight,
                viewWidth / 2f, viewHeight / 2f);
        videoView.setTransform(transform);
        videoView.invalidate();
    }

    private void applyHardwareVideoAspect() {
        if (videoWidth <= 0 || videoHeight <= 0) return;
        android.view.View parent = (android.view.View) hardwareVideoView.getParent();
        if (parent == null || parent.getWidth() <= 0 || parent.getHeight() <= 0) return;
        float fitScale = Math.min(parent.getWidth() / (float) videoWidth,
                parent.getHeight() / (float) videoHeight);
        int fittedWidth = Math.max(1, Math.round(videoWidth * fitScale));
        int fittedHeight = Math.max(1, Math.round(videoHeight * fitScale));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                hardwareVideoView.getLayoutParams();
        if (params.width != fittedWidth || params.height != fittedHeight
                || params.gravity != Gravity.CENTER) {
            params.width = fittedWidth;
            params.height = fittedHeight;
            params.gravity = Gravity.CENTER;
            hardwareVideoView.setLayoutParams(params);
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String nativePath(File file) {
        String path = file.getAbsolutePath();
        // Several Android 8/9 OEM builds expose app data to Java as
        // /data/user/0 but their native file APIs only resolve the equivalent
        // /data/data path. Both names point at the same sandbox.
        if (path.startsWith("/data/user/0/")) {
            return "/data/data/" + path.substring("/data/user/0/".length());
        }
        return path;
    }

    private static String javaPath(String path) {
        if (path != null) path = path.replace("\\/", "/").replace("\\\\", "\\");
        String prefix = "/data/data/com.example.codeclab/";
        if (path != null && path.startsWith(prefix)) {
            return "/data/user/0/com.example.codeclab/" + path.substring(prefix.length());
        }
        return path == null ? "" : path;
    }

    private File resolveNativeFile(String path) {
        if (path == null || path.isEmpty()) return null;
        path = path.replace("\\/", "/").replace("\\\\", "\\");
        File direct = new File(path);
        if (direct.isFile() && direct.canRead()) return direct;
        File javaFile = new File(javaPath(path));
        if (javaFile.isFile() && javaFile.canRead()) return javaFile;
        Log.w("CodecLab", "Output preview does not exist: " + path
                + " / " + javaFile.getAbsolutePath());
        return null;
    }

    @Override protected void onDestroy() {
        releasePlayer();
        NativeCodecEngine.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class InputItem {
        final String displayName;
        final File file;
        InputItem(String displayName, File file) {
            this.displayName = displayName;
            this.file = file;
        }
    }
}

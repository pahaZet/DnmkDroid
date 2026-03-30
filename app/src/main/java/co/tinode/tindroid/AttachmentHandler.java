package co.tinode.tindroid;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.exifinterface.media.ExifInterface;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import co.tinode.tindroid.db.BaseDb;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tinodesdk.LargeFileHelper;
import co.tinode.tinodesdk.PromisedReply;
import co.tinode.tinodesdk.Storage;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.Topic;
import co.tinode.tinodesdk.model.Drafty;
import co.tinode.tinodesdk.model.ServerMessage;
import co.tinode.tinodesdk.model.TheCard;

public class AttachmentHandler extends Worker {
    private static final long MIN_IMAGE_TARGET_BYTES = 4L * 1024 * 1024;
    private static final long MAX_IMAGE_TARGET_BYTES = 12L * 1024 * 1024;
    private static final int IMAGE_UPLOAD_QUALITY = 88;
    private static final int IMAGE_UPLOAD_MIN_QUALITY = 76;
    private static final int IMAGE_PREVIEW_QUALITY = 80;
    private static final long VIDEO_TRANSCODE_HEADROOM_BYTES = 3L * 1024 * 1024;
    private static final float VIDEO_TRANSCODE_TRIGGER_FRACTION = 0.8f;
    private static final int VIDEO_MAX_SHORT_SIDE = 720;
    private static final int VIDEO_MAX_LONG_SIDE = 1280;
    private static final int VIDEO_DEFAULT_BITRATE = 2_500_000;
    private static final int VIDEO_MAX_BITRATE = 4_000_000;
    private static final int VIDEO_MIN_BITRATE = 350_000;
    private static final int VIDEO_AUDIO_BITRATE_ESTIMATE = 128_000;
    private static final long VIDEO_TRANSCODE_TIMEOUT_MINUTES = 30;

    final static String ARG_OPERATION = "operation";
    final static String ARG_OPERATION_IMAGE = "image";
    final static String ARG_OPERATION_FILE = "file";
    final static String ARG_OPERATION_AUDIO = "audio";
    final static String ARG_OPERATION_VIDEO = "video";

    // Bundle argument names.
    final static String ARG_TOPIC_NAME = Const.INTENT_EXTRA_TOPIC;
    final static String ARG_LOCAL_URI = "local_uri";
    final static String ARG_REMOTE_URI = "remote_uri";
    final static String ARG_SRC_BYTES = "bytes";
    final static String ARG_SRC_BITMAP = "bitmap";
    final static String ARG_PREVIEW = "preview";
    final static String ARG_MIME_TYPE = "mime";
    final static String ARG_PRE_MIME_TYPE = "pre_mime";
    final static String ARG_PRE_URI = "pre_rem_uri";
    final static String ARG_IMAGE_WIDTH = "width";
    final static String ARG_IMAGE_HEIGHT = "height";
    final static String ARG_DURATION = "duration";
    final static String ARG_FILE_SIZE = "fileSize";

    final static String ARG_FILE_PATH = "filePath";
    final static String ARG_FILE_NAME = "fileName";
    final static String ARG_MSG_ID = "msgId";
    final static String ARG_IMAGE_CAPTION = "caption";
    final static String ARG_PROGRESS = "progress";
    final static String ARG_ERROR = "error";
    final static String ARG_FATAL = "fatal";
    final static String ARG_AVATAR = "square_img";

    final static String TAG_UPLOAD_WORK = "AttachmentUploader";

    private static final String TAG = "AttachmentHandler";

    private LargeFileHelper mUploader = null;
    private volatile Transformer mTransformer = null;
    private volatile Handler mTransformerHandler = null;
    private volatile HandlerThread mTransformerThread = null;

    public AttachmentHandler(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    private enum UploadType {
        UNKNOWN, AUDIO, FILE, IMAGE, VIDEO;

        static UploadType parse(String type) {
            if (ARG_OPERATION_AUDIO.equals(type)) {
                return AUDIO;
            } else if (ARG_OPERATION_FILE.equals(type)) {
                return FILE;
            } else if (ARG_OPERATION_IMAGE.equals(type)) {
                return IMAGE;
            } else if (ARG_OPERATION_VIDEO.equals(type)) {
                return VIDEO;
            }
            return UNKNOWN;
        }
    }

    @NonNull
    static UploadDetails getFileDetails(@NonNull final Context context, @NonNull Uri uri, @Nullable String filePath) {
        final ContentResolver resolver = context.getContentResolver();
        String fname = null;
        long fsize = 0L;
        int orientation = -1;

        UploadDetails result = new UploadDetails();
        result.width = 0;
        result.height = 0;

        String mimeType = resolver.getType(uri);
        if (mimeType == null) {
            mimeType = UiUtils.getMimeType(uri);
        }

        if ("application/json".equals(mimeType)) {
            // Replace JSON mime type with 'application/octet-stream' to avoid collision with Drafty form responses.
            // Remove this code in 2026.
            mimeType = "application/octet-stream";
        }

        result.mimeType = mimeType;

        String[] projection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, MediaStore.MediaColumns.ORIENTATION};
        } else {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }

        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    fname = cursor.getString(idx);
                }
                idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) {
                    fsize = cursor.getLong(idx);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    idx = cursor.getColumnIndex(MediaStore.MediaColumns.ORIENTATION);
                    if (idx >= 0) {
                        orientation = cursor.getInt(idx);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // In degrees.
        result.imageOrientation = orientation;

        // Still no size? Try opening directly.
        if (fsize <= 0) {
            String path = filePath != null ? filePath : UiUtils.getContentPath(context, uri);
            if (path != null) {
                result.filePath = path;

                File file = new File(path);
                if (fname == null) {
                    fname = file.getName();
                }
                fsize = file.length();
            } else {
                try {
                    DocumentFile df = DocumentFile.fromSingleUri(context, uri);
                    if (df != null) {
                        fname = df.getName();
                        fsize = df.length();
                    }
                } catch (SecurityException ignored) {
                }
            }
        }

        result.fileName = fname;
        result.fileSize = fsize;

        return result;
    }

    static Operation enqueueMsgAttachmentUploadRequest(AppCompatActivity activity, String operation, Bundle args) {
        String topicName = args.getString(AttachmentHandler.ARG_TOPIC_NAME);
        // Create a new message which will be updated with upload progress.
        Drafty content = new Drafty();
        HashMap<String, Object> head = new HashMap<>();
        head.put("mime", Drafty.MIME_TYPE);
        Storage.Message msg = BaseDb.getInstance().getStore()
                .msgDraft(Cache.getTinode().getTopic(topicName), content, head);
        if (msg == null) {
            Log.w(TAG, "Failed to create draft message");
            return null;
        }

        UploadType type = UploadType.parse(operation);
        Uri uri = args.getParcelable(AttachmentHandler.ARG_LOCAL_URI);
        if (uri == null) {
            Log.w(TAG, "Missing local attachment URI");
            return null;
        }

        Data.Builder data = new Data.Builder()
                .putString(ARG_OPERATION, operation)
                .putString(ARG_LOCAL_URI, uri.toString())
                .putLong(ARG_MSG_ID, msg.getDbId())
                .putString(ARG_TOPIC_NAME, topicName)
                .putString(ARG_FILE_NAME, args.getString(ARG_FILE_NAME))
                .putLong(ARG_FILE_SIZE, args.getLong(ARG_FILE_SIZE))
                .putString(ARG_MIME_TYPE, args.getString(ARG_MIME_TYPE))
                .putString(ARG_IMAGE_CAPTION, args.getString(ARG_IMAGE_CAPTION))
                .putString(ARG_FILE_PATH, args.getString(ARG_FILE_PATH))
                .putInt(ARG_IMAGE_WIDTH, args.getInt(ARG_IMAGE_WIDTH))
                .putInt(ARG_IMAGE_HEIGHT, args.getInt(ARG_IMAGE_HEIGHT));

        if (type == UploadType.AUDIO || type == UploadType.VIDEO) {
            byte[] preview = args.getByteArray(ARG_PREVIEW);
            if (preview != null) {
                data.putByteArray(ARG_PREVIEW, preview);
            }
            data.putInt(ARG_DURATION, args.getInt(ARG_DURATION));
            Uri preUri = args.getParcelable(AttachmentHandler.ARG_PRE_URI);
            if (preUri != null) {
                data.putString(ARG_PRE_URI, preUri.toString());
            }
            if (type == UploadType.VIDEO && preview != null || preUri != null) {
                data.putString(ARG_PRE_MIME_TYPE, args.getString(ARG_PRE_MIME_TYPE));
            }
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest upload = new OneTimeWorkRequest.Builder(AttachmentHandler.class)
                .setInputData(data.build())
                .setConstraints(constraints)
                .addTag(TAG_UPLOAD_WORK)
                .build();

        return WorkManager.getInstance(activity).enqueueUniqueWork(Long.toString(msg.getDbId()),
                ExistingWorkPolicy.REPLACE, upload);
    }

    @SuppressWarnings("UnusedReturnValue")
    static long enqueueDownloadAttachment(AppCompatActivity activity, String ref, byte[] bits,
                                          String fname, String mimeType) {
        long downloadId = -1;
        if (ref != null) {
            try {
                URL url = new URL(Cache.getTinode().getBaseUrl(), ref);
                String scheme = url.getProtocol();
                // Make sure the file is downloaded over http or https protocols.
                if (scheme.equals("http") || scheme.equals("https")) {
                    LargeFileHelper lfh = Cache.getTinode().getLargeFileHelper();
                    downloadId = remoteDownload(activity, Uri.parse(url.toString()), fname, mimeType, lfh.headers());
                } else {
                    Log.w(TAG, "Unsupported transport protocol '" + scheme + "'");
                    Toast.makeText(activity, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
                }
            } catch (MalformedURLException ex) {
                Log.w(TAG, "Server address is not yet configured", ex);
                Toast.makeText(activity, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
            }
        } else if (bits != null) {
            // Create file in a downloads directory by default.
            File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            // Make sure Downloads folder exists.
            path.mkdirs();

            File file = new File(path, fname);

            if (TextUtils.isEmpty(mimeType)) {
                mimeType = UiUtils.getMimeType(Uri.fromFile(file));
                if (mimeType == null) {
                    mimeType = "*/*";
                }
            }

            Uri result;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    // Save file to local storage.
                    fos.write(bits);
                    result = FileProvider.getUriForFile(activity, "co.tinode.tindroid.provider", file);
                } catch (IOException ex) {
                    Log.w(TAG, "Failed to save attachment to storage", ex);
                    Toast.makeText(activity, R.string.failed_to_save_download, Toast.LENGTH_SHORT).show();
                    return downloadId;
                }
            } else {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fname);
                cv.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                ContentResolver resolver = activity.getContentResolver();
                Uri dst = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                result = resolver.insert(dst, cv);
                if (result != null) {
                    try {
                        new ParcelFileDescriptor.
                                AutoCloseOutputStream(resolver.openFileDescriptor(result, "w")).write(bits);
                    } catch (IOException ex) {
                        Log.w(TAG, "Failed to save attachment to media storage", ex);
                        Toast.makeText(activity, R.string.failed_to_save_download, Toast.LENGTH_SHORT).show();
                        return downloadId;
                    }
                    cv.clear();
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(result, cv, null, null);
                }
            }

            // Make the downloaded file is visible.
            MediaScannerConnection.scanFile(activity,
                    new String[]{file.toString()}, null, null);

            // Open downloaded file.
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(result, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                activity.startActivity(intent);
            } catch (ActivityNotFoundException ex) {
                Log.w(TAG, "No application can handle downloaded file", ex);
                Toast.makeText(activity, R.string.failed_to_open_file, Toast.LENGTH_SHORT).show();
                activity.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
            }
        } else {
            Log.w(TAG, "Invalid or missing attachment");
            Toast.makeText(activity, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
        }

        return downloadId;
    }

    interface DownloadProgressListener {
        void onProgress(long downloaded, long total);

        void onComplete();

        void onError();
    }

    interface AttachmentFileListener {
        void onReady(@NonNull File file);

        void onError();
    }

    static boolean isAttachmentCached(@NonNull Context context, @Nullable String ref, @Nullable String fname) {
        if (TextUtils.isEmpty(ref)) {
            return false;
        }
        File file = getCachedAttachmentFile(context, ref, fname);
        return file.exists() && file.length() > 0;
    }

    static void fetchAttachmentFile(@NonNull AppCompatActivity activity, @Nullable String ref, @Nullable byte[] bits,
                                    @Nullable String fname, @Nullable DownloadProgressListener progressListener,
                                    @NonNull AttachmentFileListener fileListener) {
        if (bits != null) {
            try {
                File file = createOpenableAttachmentFile(activity, fname);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bits);
                }
                fileListener.onReady(file);
            } catch (IOException ex) {
                Log.w(TAG, "Failed to prepare attachment file", ex);
                if (progressListener != null) {
                    progressListener.onError();
                }
                fileListener.onError();
            }
            return;
        }

        if (ref == null) {
            Log.w(TAG, "Invalid or missing attachment");
            if (progressListener != null) {
                progressListener.onError();
            }
            fileListener.onError();
            return;
        }

        final URL url;
        try {
            url = new URL(Cache.getTinode().getBaseUrl(), ref);
        } catch (MalformedURLException ex) {
            Log.w(TAG, "Server address is not yet configured", ex);
            if (progressListener != null) {
                progressListener.onError();
            }
            fileListener.onError();
            return;
        }

        String scheme = url.getProtocol();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            Log.w(TAG, "Unsupported transport protocol '" + scheme + "'");
            if (progressListener != null) {
                progressListener.onError();
            }
            fileListener.onError();
            return;
        }

        final File file = getCachedAttachmentFile(activity, ref, fname);
        if (file.exists() && file.length() > 0) {
            if (progressListener != null) {
                progressListener.onComplete();
            }
            fileListener.onReady(file);
            return;
        }

        new Thread(() -> {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                final int[] lastPercent = {-1};
                Cache.getTinode().getLargeFileHelper().download(url.toString(), fos, (downloaded, total) -> {
                    if (progressListener == null) {
                        return;
                    }

                    int percent = total > 0 ? (int) (downloaded * 100 / total) : -1;
                    if (percent == lastPercent[0]) {
                        return;
                    }
                    lastPercent[0] = percent;
                    activity.runOnUiThread(() -> progressListener.onProgress(downloaded, total));
                });
                activity.runOnUiThread(() -> {
                    if (progressListener != null) {
                        progressListener.onComplete();
                    }
                    fileListener.onReady(file);
                });
            } catch (Exception ex) {
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Failed to delete temporary attachment file " + file);
                }
                Log.w(TAG, "Failed to download attachment file", ex);
                activity.runOnUiThread(() -> {
                    if (progressListener != null) {
                        progressListener.onError();
                    }
                    fileListener.onError();
                });
            }
        }).start();
    }

    static void openAttachment(AppCompatActivity activity, String ref, byte[] bits,
                               String fname, String mimeType,
                               @Nullable DownloadProgressListener progressListener) {
        if (bits != null) {
            try {
                File file = createOpenableAttachmentFile(activity, fname);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bits);
                }
                openAttachmentFile(activity, file, mimeType);
            } catch (IOException ex) {
                Log.w(TAG, "Failed to prepare in-band attachment for opening", ex);
                Toast.makeText(activity, R.string.failed_to_save_download, Toast.LENGTH_SHORT).show();
                if (progressListener != null) {
                    progressListener.onError();
                }
            }
            return;
        }

        if (ref == null) {
            Log.w(TAG, "Invalid or missing attachment");
            Toast.makeText(activity, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
            if (progressListener != null) {
                progressListener.onError();
            }
            return;
        }

        final URL url;
        try {
            url = new URL(Cache.getTinode().getBaseUrl(), ref);
        } catch (MalformedURLException ex) {
            Log.w(TAG, "Server address is not yet configured", ex);
            Toast.makeText(activity, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
            if (progressListener != null) {
                progressListener.onError();
            }
            return;
        }

        String scheme = url.getProtocol();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            Log.w(TAG, "Unsupported transport protocol '" + scheme + "'");
            Toast.makeText(activity, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
            if (progressListener != null) {
                progressListener.onError();
            }
            return;
        }

        final File file = getCachedAttachmentFile(activity, ref, fname);
        if (file.exists() && file.length() > 0) {
            openAttachmentFile(activity, file, mimeType);
            if (progressListener != null) {
                progressListener.onComplete();
            }
            return;
        }

        final String finalMimeType = mimeType;
        new Thread(() -> {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                final int[] lastPercent = {-1};
                Cache.getTinode().getLargeFileHelper().download(url.toString(), fos, (downloaded, total) -> {
                    if (progressListener == null) {
                        return;
                    }

                    int percent = total > 0 ? (int) (downloaded * 100 / total) : -1;
                    if (percent == lastPercent[0]) {
                        return;
                    }
                    lastPercent[0] = percent;
                    activity.runOnUiThread(() -> progressListener.onProgress(downloaded, total));
                });
                activity.runOnUiThread(() -> {
                    openAttachmentFile(activity, file, finalMimeType);
                    if (progressListener != null) {
                        progressListener.onComplete();
                    }
                });
            } catch (Exception ex) {
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Failed to delete temporary attachment file " + file);
                }
                Log.w(TAG, "Failed to download attachment for opening", ex);
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
                    if (progressListener != null) {
                        progressListener.onError();
                    }
                });
            }
        }).start();
    }

    private static long remoteDownload(AppCompatActivity activity, final Uri uri, final String fname, final String mime,
                                       final Map<String, String> headers) {

        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            return -1;
        }

        // Ensure directory exists.
        Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .mkdirs();

        DownloadManager.Request req = new DownloadManager.Request(uri);
        // Always add Origin header to satisfy CORS. If server does not need CORS it won't hurt anyway.
        req.addRequestHeader("Origin", Cache.getTinode().getHttpOrigin());
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                req.addRequestHeader(entry.getKey(), entry.getValue());
            }
        }

        return dm.enqueue(
                req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI |
                                DownloadManager.Request.NETWORK_MOBILE)
                        .setMimeType(mime)
                        .setAllowedOverRoaming(false)
                        .setTitle(fname)
                        .setDescription(activity.getString(R.string.download_title))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setVisibleInDownloadsUi(true)
                        .setDestinationUri(Uri.fromFile(new File(Environment
                                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fname))));
    }

    @NonNull
    private static File getCachedAttachmentFile(@NonNull Context context, @NonNull String ref, @Nullable String fname) {
        File dir = new File(context.getCacheDir(), "attachments");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String safeName = sanitizeAttachmentFileName(fname);
        return new File(dir, Integer.toHexString(ref.hashCode()) + "_" + safeName);
    }

    @NonNull
    private static File createOpenableAttachmentFile(@NonNull Context context, @Nullable String fname)
            throws IOException {
        File dir = new File(context.getCacheDir(), "attachments");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create cache directory for attachments");
        }

        String safeName = sanitizeAttachmentFileName(fname);
        int dot = safeName.lastIndexOf('.');
        String prefix = dot > 0 ? safeName.substring(0, dot) : safeName;
        String suffix = dot > 0 ? safeName.substring(dot) : "";
        if (prefix.length() < 3) {
            prefix = (prefix + "___").substring(0, 3);
        }
        return File.createTempFile(prefix + "_", suffix, dir);
    }

    @NonNull
    private static String sanitizeAttachmentFileName(@Nullable String fname) {
        String safeName = fname;
        if (TextUtils.isEmpty(safeName)) {
            safeName = "attachment";
        }

        int slash = Math.max(safeName.lastIndexOf('/'), safeName.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < safeName.length()) {
            safeName = safeName.substring(slash + 1);
        }

        int query = safeName.indexOf('?');
        if (query >= 0) {
            safeName = safeName.substring(0, query);
        }

        safeName = safeName.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (TextUtils.isEmpty(safeName)) {
            safeName = "attachment";
        }
        if (safeName.length() > 80) {
            int dot = safeName.lastIndexOf('.');
            String suffix = dot > 0 ? safeName.substring(dot) : "";
            int maxBaseLength = 80 - suffix.length();
            if (maxBaseLength < 1) {
                safeName = safeName.substring(0, 80);
            } else {
                safeName = safeName.substring(0, maxBaseLength) + suffix;
            }
        }
        return safeName;
    }

    private static void openAttachmentFile(@NonNull AppCompatActivity activity, @NonNull File file,
                                           @Nullable String mimeType) {
        String resolvedMimeType = mimeType;
        if (TextUtils.isEmpty(resolvedMimeType) || "application/octet-stream".equals(resolvedMimeType)) {
            String detectedMimeType = UiUtils.getMimeType(Uri.fromFile(file));
            if (!TextUtils.isEmpty(detectedMimeType)) {
                resolvedMimeType = detectedMimeType;
            }
        }
        if (TextUtils.isEmpty(resolvedMimeType)) {
            resolvedMimeType = "*/*";
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(FileProvider.getUriForFile(activity, "co.tinode.tindroid.provider", file),
                resolvedMimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Log.w(TAG, "No application can handle attachment " + file, ex);
            Toast.makeText(activity, R.string.failed_to_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    private static @Nullable URI wrapRefUrl(@Nullable String refUrl) {
        URI ref = null;
        if (refUrl != null) {
            try {
                ref = new URI(refUrl);
                if (ref.isAbsolute()) {
                    ref = new URI(Cache.getTinode().getBaseUrl().toString()).relativize(ref);
                }
            } catch (URISyntaxException | MalformedURLException ignored) {
            }
        }
        return ref;
    }

    // Send audio recording.
    private static Drafty draftyAudio(String mimeType, byte[] preview, byte[] bits, String refUrl,
                                      int duration, String fname, long size) {
        return new Drafty().insertAudio(0, mimeType, bits, preview, duration, fname, wrapRefUrl(refUrl), size);
    }

    // Send image.
    private static Drafty draftyImage(String caption, String mimeType, byte[] bits, String refUrl,
                                      int width, int height, String fname, long size) {
        Drafty content = new Drafty();
        content.insertImage(0, mimeType, bits, width, height, fname, wrapRefUrl(refUrl), size);
        if (!TextUtils.isEmpty(caption)) {
            content.appendLineBreak()
                    .append(Drafty.fromPlainText(caption));
        }
        return content;
    }

    // Send file in-band
    private static Drafty draftyFile(String mimeType, String fname, byte[] bits) {
        Drafty content = new Drafty();
        content.attachFile(mimeType, bits, fname);
        return content;
    }

    // Send file as a link.
    private static Drafty draftyAttachment(String mimeType, String fname, String refUrl, long size) {
        Drafty content = new Drafty();
        content.attachFile(mimeType, fname, refUrl, size);
        return content;
    }

    // Send image.
    private static Drafty draftyVideo(String caption, String mimeType, byte[] bits, String refUrl,
                                      int width, int height,
                                      int duration, byte[] preview, String preref, String premime,
                                      String fname, long size) {
        Drafty content = new Drafty();
        content.insertVideo(0, mimeType, bits, width, height, preref == null ? preview : null,
                wrapRefUrl(preref), premime, duration, fname, wrapRefUrl(refUrl), size);
        if (!TextUtils.isEmpty(caption)) {
            content.appendLineBreak()
                    .append(Drafty.fromPlainText(caption));
        }
        return content;
    }

    @NonNull
    @Override
    public ListenableWorker.Result doWork() {
        return uploadMessageAttachment(getApplicationContext(), getInputData());
    }

    @Override
    public void onStopped() {
        if (mUploader != null) {
            mUploader.cancel();
        }
        requestTransformerCancel();

        super.onStopped();
    }

    // This is long running/blocking call. It should not be called on UI thread.
    private ListenableWorker.Result uploadMessageAttachment(final Context context, final Data args) {
        Storage store = BaseDb.getInstance().getStore();

        // File upload "file", "image", "audio", "video".
        // When "video": video itself + video poster (image).
        UploadType operation = UploadType.parse(args.getString(ARG_OPERATION));

        final String topicName = args.getString(ARG_TOPIC_NAME);
        // URI must exist.
        Uri sourceUri = Uri.parse(args.getString(ARG_LOCAL_URI));
        // filePath is optional
        String sourcePath = args.getString(ARG_FILE_PATH);
        final long msgId = args.getLong(ARG_MSG_ID, 0);

        final Data.Builder result = new Data.Builder()
                .putString(ARG_TOPIC_NAME, topicName)
                .putLong(ARG_MSG_ID, msgId);

        final Topic topic = Cache.getTinode().getTopic(topicName);

        // Maximum size of file to send in-band. The default is 256KB reduced by base64 expansion
        // factor 3/4 and minus overhead = 195584.
        final long maxInbandAttachmentSize = Cache.getTinode().getServerLimit(Tinode.MAX_MESSAGE_SIZE,
                (1L << 18)) * 3 / 4 - 1024;
        // Maximum size of file to upload. Default: 8MB.
        final long maxFileUploadSize = Cache.getTinode().getServerLimit(Tinode.MAX_FILE_UPLOAD_SIZE, 1L << 23);

        Drafty content = null;
        boolean success = false;
        InputStream is = null;
        Bitmap bmp = null;
        File transcodedVideo = null;
        try {
            final ContentResolver resolver = context.getContentResolver();
            final UploadDetails uploadDetails = getFileDetails(context, sourceUri, sourcePath);

            if (uploadDetails.fileSize == 0) {
                Log.w(TAG, "File size is zero; uri=" + sourceUri + "; file=" + sourcePath);
                return ListenableWorker.Result.failure(
                        result.putBoolean(ARG_FATAL, true)
                                .putString(ARG_ERROR, context.getString(R.string.unable_to_attach_file)).build());
            }

            if (TextUtils.isEmpty(uploadDetails.fileName)) {
                uploadDetails.fileName = context.getString(R.string.default_attachment_name);
            }

            if (TextUtils.isEmpty(uploadDetails.mimeType)) {
                uploadDetails.mimeType = args.getString(ARG_MIME_TYPE);
            }

            uploadDetails.valueRef = null;
            uploadDetails.previewRef = null;
            uploadDetails.previewSize = 0;

            // Image is being attached. Ensure the image has correct orientation and size.
            if (operation == UploadType.IMAGE) {
                // Make sure the image is not too large in byte-size and in linear dimensions.
                PreparedImage preparedImage = prepareImage(resolver, sourceUri, uploadDetails,
                        getTargetImageSize(maxFileUploadSize));
                bmp = preparedImage.bitmap;
                is = new ByteArrayInputStream(preparedImage.bits);
                uploadDetails.fileSize = preparedImage.bits.length;

                // Create a tiny preview bitmap.
                if (bmp.getWidth() > Const.IMAGE_PREVIEW_DIM || bmp.getHeight() > Const.IMAGE_PREVIEW_DIM) {
                    uploadDetails.previewBits = UtilsBitmap.bitmapToBytes(UtilsBitmap.scaleBitmap(bmp,
                                    Const.IMAGE_PREVIEW_DIM, Const.IMAGE_PREVIEW_DIM, false),
                            "image/jpeg", IMAGE_PREVIEW_QUALITY);
                }
            } else {
                uploadDetails.duration = args.getInt(ARG_DURATION, 0);
                // Poster could be provided as a byte array.
                uploadDetails.previewBits = args.getByteArray(ARG_PREVIEW);
                if (uploadDetails.previewBits == null) {
                    // Check if poster is provided as a local URI.
                    String preUriStr = args.getString(ARG_PRE_URI);
                    if (preUriStr != null) {
                        InputStream posterIs = resolver.openInputStream(Uri.parse(preUriStr));
                        if (posterIs != null) {
                            uploadDetails.previewBits = readAll(posterIs);
                            posterIs.close();
                        }
                    }
                }
                if (operation == UploadType.VIDEO) {
                    uploadDetails.width = args.getInt(ARG_IMAGE_WIDTH, 0);
                    uploadDetails.height = args.getInt(ARG_IMAGE_HEIGHT, 0);
                    uploadDetails.previewMime = args.getString(ARG_PRE_MIME_TYPE);
                    populateVideoMetadata(context, sourceUri, sourcePath, uploadDetails);
                    transcodedVideo = transcodeVideoIfNeeded(context, sourceUri, uploadDetails, maxFileUploadSize);
                    if (transcodedVideo != null) {
                        sourceUri = Uri.fromFile(transcodedVideo);
                        sourcePath = transcodedVideo.getAbsolutePath();
                        uploadDetails.filePath = sourcePath;
                        uploadDetails.fileName = ensureMp4FileName(uploadDetails.fileName);
                        uploadDetails.mimeType = MimeTypes.VIDEO_MP4;
                        uploadDetails.fileSize = transcodedVideo.length();
                    }
                    uploadDetails.previewSize = uploadDetails.previewBits != null ?
                            uploadDetails.previewBits.length : 0;
                    if (uploadDetails.previewSize > uploadDetails.fileSize) {
                        // Image poster is greater than video itself. This is not currently supported.
                        Log.w(TAG, "Video poster size " + uploadDetails.previewSize +
                                " is greater than video " + uploadDetails.fileSize);
                        return ListenableWorker.Result.failure(
                                result.putBoolean(ARG_FATAL, true)
                                        .putString(ARG_ERROR, context.getString(R.string.unable_to_attach_file)).build());
                    }
                }
            }

            if (uploadDetails.fileSize > maxFileUploadSize) {
                // Fail: file is too big to be send in-band or out of band.
                if (is != null) {
                    is.close();
                }
                Log.w(TAG, "Unable to process attachment: too big, size=" + uploadDetails.fileSize);
                return ListenableWorker.Result.failure(
                        result.putString(ARG_ERROR,
                                        context.getString(
                                                R.string.attachment_too_large,
                                                UtilsString.bytesToHumanSize(uploadDetails.fileSize),
                                                UtilsString.bytesToHumanSize(maxFileUploadSize)))
                                .putBoolean(ARG_FATAL, true)
                                .build());
            } else {
                if (is == null) {
                    is = resolver.openInputStream(sourceUri);
                }
                if (is == null) {
                    throw new IOException("Failed to open file at " + sourceUri);
                }

                if (uploadDetails.fileSize + uploadDetails.previewSize > maxInbandAttachmentSize) {
                    // Send out of band.
                    uploadDetails.valueRef = "mid:uploading-" + msgId;
                    if (uploadDetails.previewSize > maxInbandAttachmentSize / 4) {
                        uploadDetails.previewRef = "mid:uploading-" + msgId + "/1";
                    }
                } else {
                    uploadDetails.valueBits = readAll(is);
                }

                Drafty msgDraft = prepareDraft(operation, uploadDetails, args.getString(ARG_IMAGE_CAPTION));
                if (msgDraft != null) {
                    store.msgDraftUpdate(topic, msgId, msgDraft);
                } else {
                    store.msgDiscard(topic, msgId);
                    throw new IllegalArgumentException("Unknown operation " + operation);
                }

                if (uploadDetails.valueRef != null) {
                    setProgressAsync(new Data.Builder()
                            .putAll(result.build())
                            .putLong(ARG_PROGRESS, 0)
                            .putLong(ARG_FILE_SIZE, uploadDetails.fileSize).build());

                    // Upload results.
                    // noinspection unchecked
                    PromisedReply<ServerMessage>[] uploadPromises = (PromisedReply<ServerMessage>[]) new PromisedReply[2];

                    // Upload large media.
                    mUploader = Cache.getTinode().getLargeFileHelper();
                    uploadPromises[0] = mUploader.uploadAsync(is, uploadDetails.fileName,
                            uploadDetails.mimeType, uploadDetails.fileSize,
                            topicName, (progress, size) -> setProgressAsync(new Data.Builder()
                                    .putAll(result.build())
                                    .putLong(ARG_PROGRESS, progress)
                                    .putLong(ARG_FILE_SIZE, size)
                                    .build()));

                    // Optionally upload video poster.
                    if (uploadDetails.previewRef != null) {
                        uploadPromises[1] = mUploader.uploadAsync(new ByteArrayInputStream(uploadDetails.previewBits),
                                "poster", uploadDetails.previewMime, uploadDetails.previewSize,
                                topicName, null);
                        // ByteArrayInputStream:close() is a noop. No need to call close().
                    } else {
                        uploadPromises[1] = null;
                    }

                    ServerMessage[] msgs = new ServerMessage[2];
                    try {
                        // Wait for uploads to finish. This is a long-running blocking call.
                        Object[] objs = PromisedReply.allOf(uploadPromises).getResult();
                        msgs[0] = (ServerMessage) objs[0];
                        msgs[1] = (ServerMessage) objs[1];
                    } catch (Exception ex) {
                        store.msgFailed(topic, msgId);
                        throw ex;
                    }

                    mUploader = null;

                    success = msgs[0] != null && msgs[0].ctrl != null && msgs[0].ctrl.code == 200;

                    if (success) {
                        String url = msgs[0].ctrl.getStringParam("url", null);
                        result.putString(ARG_REMOTE_URI, url);
                        switch (operation) {
                            case AUDIO:
                                content = draftyAudio(uploadDetails.mimeType, uploadDetails.previewBits,
                                        null, url, uploadDetails.duration, uploadDetails.fileName,
                                        uploadDetails.fileSize);
                                break;

                            case FILE:
                                content = draftyAttachment(uploadDetails.mimeType, uploadDetails.fileName,
                                        url, uploadDetails.fileSize);
                                break;

                            case IMAGE:
                                content = draftyImage(args.getString(ARG_IMAGE_CAPTION), uploadDetails.mimeType,
                                        uploadDetails.previewBits, url, uploadDetails.width, uploadDetails.height,
                                        uploadDetails.fileName, uploadDetails.fileSize);
                                break;

                            case VIDEO:
                                String posterUrl = null;
                                if (msgs[1] != null && msgs[1].ctrl != null && msgs[1].ctrl.code == 200) {
                                    posterUrl = msgs[1].ctrl.getStringParam("url", null);
                                }
                                content = draftyVideo(args.getString(ARG_IMAGE_CAPTION), uploadDetails.mimeType,
                                        null, url, uploadDetails.width, uploadDetails.height,
                                        uploadDetails.duration, uploadDetails.previewBits,
                                        posterUrl, uploadDetails.previewMime,
                                        uploadDetails.fileName, uploadDetails.fileSize);
                                break;
                        }
                    } else {
                        result.putBoolean(ARG_FATAL, true)
                                .putString(ARG_ERROR, "Server returned error");
                    }
                } else {
                    // Send in-band.
                    success = true;
                    setProgressAsync(new Data.Builder()
                            .putAll(result.build())
                            .putLong(ARG_PROGRESS, 0)
                            .putLong(ARG_FILE_SIZE, uploadDetails.fileSize)
                            .build());
                }
            }
        } catch (CancellationException ignored) {
            result.putString(ARG_ERROR, context.getString(R.string.canceled));
            Log.d(TAG, "Upload cancelled");
        } catch (Exception ex) {
            result.putString(ARG_ERROR, ex.getMessage());
            Log.w(TAG, "Failed to upload file", ex);
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
            if (operation == UploadType.AUDIO && sourcePath != null) {
                new File(sourcePath).delete();
            }
            if (transcodedVideo != null && transcodedVideo.exists()) {
                // Temporary transcoded copy is only needed during the current upload job.
                transcodedVideo.delete();
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }

        if (success) {
            // Success: mark message as ready for delivery. If content==null it won't be saved.
            store.msgReady(topic, msgId, content);
            return ListenableWorker.Result.success(result.build());
        } else {
            // Failure. Draft has been discarded earlier. We cannot discard it here because
            // copyStream cannot be interrupted.
            return ListenableWorker.Result.failure(result.build());
        }
    }

    /**
     * Scale the avatar to appropriate size and upload it to the server of necessary.
     * @param pub VxCard to save avatar to.
     * @param bmp new avatar; no action is taken if avatar is null.
     * @return result of the operation.
     */
    static PromisedReply<ServerMessage> uploadAvatar(@NonNull final VxCard pub, @Nullable Bitmap bmp,
                                                     @Nullable String topicName) {
        if (bmp == null) {
            // No action needed.
            return new PromisedReply<>((ServerMessage) null);
        }

        final String mimeType= "image/png";

        int width = bmp.getWidth();
        int height = bmp.getHeight();
        if (width < Const.MIN_AVATAR_SIZE || height < Const.MIN_AVATAR_SIZE) {
            // FAIL.
            return new PromisedReply<>(new Exception("Image is too small"));
        }

        if (width != height || width > Const.MAX_AVATAR_SIZE) {
            bmp = UtilsBitmap.scaleSquareBitmap(bmp, Const.MAX_AVATAR_SIZE);
            width = bmp.getWidth();
            height = bmp.getHeight();
        }

        if (pub.photo == null) {
            pub.photo = new TheCard.Photo();
        }
        pub.photo.width = width;
        pub.photo.height = height;

        PromisedReply<ServerMessage> result;
        try (InputStream is = UtilsBitmap.bitmapToStream(bmp, mimeType)) {
            long fileSize = is.available();
            if (fileSize > Const.MAX_INBAND_AVATAR_SIZE) {
                // Sending avatar out of band.

                // Generate small avatar preview.
                pub.photo.data = UtilsBitmap.bitmapToBytes(UtilsBitmap.scaleSquareBitmap(bmp, Const.AVATAR_THUMBNAIL_DIM), mimeType);
                // Upload then return result with a link. This is a long-running blocking call.
                LargeFileHelper uploader = Cache.getTinode().getLargeFileHelper();
                result = uploader.uploadAsync(is, System.currentTimeMillis() + ".png", mimeType, fileSize,
                        topicName, null).thenApply(new PromisedReply.SuccessListener<>() {
                    @Override
                    public PromisedReply<ServerMessage> onSuccess(ServerMessage msg) {
                        if (msg != null && msg.ctrl != null && msg.ctrl.code == 200) {
                            pub.photo.ref = msg.ctrl.getStringParam("url", null);
                        }
                        return null;
                    }
                });
            } else {
                // Can send a small avatar in-band.
                pub.photo.data = UtilsBitmap.bitmapToBytes(UtilsBitmap.scaleSquareBitmap(bmp, Const.AVATAR_THUMBNAIL_DIM), mimeType);
                result = new PromisedReply<>((ServerMessage) null);
            }
        } catch (IOException | IllegalArgumentException ex) {
            Log.w(TAG, "Failed to upload avatar", ex);
            result = new PromisedReply<>(ex);
        }

        return result;
    }

    // Create placeholder draft message.
    private static Drafty prepareDraft(UploadType operation, UploadDetails uploadDetails, String caption) {
        Drafty msgDraft = null;

        switch (operation) {
            case AUDIO:
                if (TextUtils.isEmpty(uploadDetails.mimeType)) {
                    uploadDetails.mimeType = "audio/aac";
                }

                msgDraft = draftyAudio(uploadDetails.mimeType, uploadDetails.previewBits,
                        uploadDetails.valueBits, uploadDetails.valueRef, uploadDetails.duration,
                        uploadDetails.fileName, uploadDetails.valueBits.length);
                break;

            case FILE:
                if (!TextUtils.isEmpty(uploadDetails.valueRef)) {
                    msgDraft = draftyAttachment(uploadDetails.mimeType, uploadDetails.fileName,
                            uploadDetails.valueRef, uploadDetails.fileSize);
                } else {
                    msgDraft = draftyFile(uploadDetails.mimeType, uploadDetails.fileName, uploadDetails.valueBits);
                }
                break;

            case IMAGE:
                if (TextUtils.isEmpty(uploadDetails.mimeType)) {
                    uploadDetails.mimeType = "image/jpeg";
                }
                if (uploadDetails.width == 0 && uploadDetails.previewBits != null) {
                    BitmapFactory.Options options = boundsFromBitmapBits(uploadDetails.previewBits);
                    uploadDetails.width = options.outWidth;
                    uploadDetails.height = options.outHeight;
                }
                byte[] bits = uploadDetails.valueRef != null ? uploadDetails.previewBits : uploadDetails.valueBits;
                msgDraft = draftyImage(caption, uploadDetails.mimeType, bits, uploadDetails.valueRef,
                        uploadDetails.width, uploadDetails.height, uploadDetails.fileName, uploadDetails.fileSize);
                break;

            case VIDEO:
                if (TextUtils.isEmpty(uploadDetails.mimeType)) {
                    uploadDetails.mimeType = "video/mpeg";
                }
                msgDraft = draftyVideo(caption, uploadDetails.mimeType,
                        uploadDetails.valueBits, uploadDetails.valueRef, uploadDetails.width, uploadDetails.height,
                        uploadDetails.duration, uploadDetails.previewBits, uploadDetails.previewRef,
                        uploadDetails.previewMime, uploadDetails.fileName, uploadDetails.fileSize);
                break;
        }

        return msgDraft;
    }

    private static long getTargetImageSize(long maxFileUploadSize) {
        return Math.max(MIN_IMAGE_TARGET_BYTES, Math.min(MAX_IMAGE_TARGET_BYTES, maxFileUploadSize / 4));
    }

    private static String normalizeImageMimeType(String mimeType) {
        return "image/png".equals(mimeType) ? "image/png" : "image/jpeg";
    }

    private static byte[] compressBitmapForUpload(@NonNull Bitmap bmp, @NonNull String mimeType,
                                                  long targetByteSize) {
        if (!"image/jpeg".equals(mimeType) || targetByteSize <= 0) {
            return UtilsBitmap.bitmapToBytes(bmp, mimeType, 100);
        }

        int quality = IMAGE_UPLOAD_QUALITY;
        byte[] bits = UtilsBitmap.bitmapToBytes(bmp, mimeType, quality);
        while (bits.length > targetByteSize && quality > IMAGE_UPLOAD_MIN_QUALITY) {
            quality = Math.max(IMAGE_UPLOAD_MIN_QUALITY, quality - 4);
            bits = UtilsBitmap.bitmapToBytes(bmp, mimeType, quality);
        }
        return bits;
    }

    // Make sure the image is not too large in byte-size and in linear dimensions, has correct orientation.
    private static PreparedImage prepareImage(ContentResolver r, Uri src, UploadDetails uploadDetails,
                                              long targetByteSize) throws IOException {
        InputStream is = r.openInputStream(src);
        if (is == null) {
            throw new IOException("Decoding bitmap: source not available");
        }
        Bitmap bmp = BitmapFactory.decodeStream(is, null, null);
        is.close();

        if (bmp == null) {
            throw new IOException("Failed to decode bitmap");
        }

        uploadDetails.mimeType = normalizeImageMimeType(uploadDetails.mimeType);

        // Also ensure the image has correct orientation.
        int orientation = ExifInterface.ORIENTATION_UNDEFINED;
        try {
            // Opening original image, not a scaled copy.
            if (uploadDetails.imageOrientation == -1) {
                is = r.openInputStream(src);
                if (is != null) {
                    ExifInterface exif = new ExifInterface(is);
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_UNDEFINED);
                    is.close();
                }
            } else {
                switch (uploadDetails.imageOrientation) {
                    case 0:
                        orientation = ExifInterface.ORIENTATION_NORMAL;
                        break;
                    case 90:
                        orientation = ExifInterface.ORIENTATION_ROTATE_90;
                        break;
                    case 180:
                        orientation = ExifInterface.ORIENTATION_ROTATE_180;
                        break;
                    case 270:
                        orientation = ExifInterface.ORIENTATION_ROTATE_270;
                        break;
                    default:
                }
            }

            switch (orientation) {
                case ExifInterface.ORIENTATION_NORMAL:
                    break;
                case ExifInterface.ORIENTATION_UNDEFINED:
                    Log.d(TAG, "Unable to obtain image orientation");
                default:
                    // Rotate image to ensure correct orientation.
                    bmp = UtilsBitmap.rotateBitmap(bmp, orientation);
                    break;
            }
        } catch (IOException ex) {
            Log.w(TAG, "Failed to obtain image orientation", ex);
        }

        uploadDetails.width = bmp.getWidth();
        uploadDetails.height = bmp.getHeight();

        byte[] bits = compressBitmapForUpload(bmp, uploadDetails.mimeType, targetByteSize);
        uploadDetails.fileSize = bits.length;
        return new PreparedImage(bmp, bits);
    }

    private static void populateVideoMetadata(@NonNull Context context, @NonNull Uri sourceUri,
                                              @Nullable String sourcePath, @NonNull UploadDetails uploadDetails) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (!TextUtils.isEmpty(sourcePath)) {
                retriever.setDataSource(sourcePath);
            } else {
                retriever.setDataSource(context, sourceUri);
            }

            int width = parseMetadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int height = parseMetadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int rotation = parseMetadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation == 90 || rotation == 270) {
                int tmp = width;
                width = height;
                height = tmp;
            }

            if (uploadDetails.width <= 0 && width > 0) {
                uploadDetails.width = width;
            }
            if (uploadDetails.height <= 0 && height > 0) {
                uploadDetails.height = height;
            }
            if (uploadDetails.duration <= 0) {
                uploadDetails.duration = parseMetadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Failed to read video metadata", ex);
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
            }
        }
    }

    private static int parseMetadataInt(@NonNull MediaMetadataRetriever retriever, int key) {
        try {
            String value = retriever.extractMetadata(key);
            return TextUtils.isEmpty(value) ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean shouldTranscodeVideo(@NonNull UploadDetails uploadDetails, long maxFileUploadSize) {
        int longSide = Math.max(uploadDetails.width, uploadDetails.height);
        int shortSide = Math.min(uploadDetails.width, uploadDetails.height);
        long triggerSize = (long) (maxFileUploadSize * VIDEO_TRANSCODE_TRIGGER_FRACTION);
        return longSide > VIDEO_MAX_LONG_SIDE || shortSide > VIDEO_MAX_SHORT_SIDE ||
                uploadDetails.fileSize > triggerSize;
    }

    @NonNull
    private static int[] getTargetVideoSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return new int[]{VIDEO_MAX_SHORT_SIDE, VIDEO_MAX_LONG_SIDE};
        }

        int maxWidth = width >= height ? VIDEO_MAX_LONG_SIDE : VIDEO_MAX_SHORT_SIDE;
        int maxHeight = width >= height ? VIDEO_MAX_SHORT_SIDE : VIDEO_MAX_LONG_SIDE;
        float scale = Math.min(1f, Math.min((float) maxWidth / width, (float) maxHeight / height));
        int scaledWidth = makeEven(Math.max(2, Math.round(width * scale)));
        int scaledHeight = makeEven(Math.max(2, Math.round(height * scale)));
        return new int[]{scaledWidth, scaledHeight};
    }

    private static int makeEven(int value) {
        if (value < 2) {
            return 2;
        }
        return (value & 1) == 0 ? value : value - 1;
    }

    private static int calculateTargetVideoBitrate(@NonNull UploadDetails uploadDetails, long maxFileUploadSize) {
        if (uploadDetails.duration <= 0) {
            return VIDEO_DEFAULT_BITRATE;
        }

        long targetBytes = Math.max(8L << 20, maxFileUploadSize - VIDEO_TRANSCODE_HEADROOM_BYTES);
        long targetBits = targetBytes * 8;
        long audioBits = (long) VIDEO_AUDIO_BITRATE_ESTIMATE * uploadDetails.duration / 1000L;
        long videoBits = Math.max((long) VIDEO_MIN_BITRATE * uploadDetails.duration / 1000L, targetBits - audioBits);
        long bitrate = videoBits * 1000L / uploadDetails.duration;
        return (int) Math.max(VIDEO_MIN_BITRATE, Math.min(VIDEO_MAX_BITRATE, bitrate));
    }

    @NonNull
    private static String ensureMp4FileName(@Nullable String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "video.mp4";
        }

        int dotAt = fileName.lastIndexOf('.');
        String base = dotAt > 0 ? fileName.substring(0, dotAt) : fileName;
        return base + ".mp4";
    }

    @Nullable
    private File transcodeVideoIfNeeded(@NonNull Context context, @NonNull Uri sourceUri,
                                        @NonNull UploadDetails uploadDetails,
                                        long maxFileUploadSize) throws IOException {
        if (!shouldTranscodeVideo(uploadDetails, maxFileUploadSize)) {
            return null;
        }

        int[] targetSize = getTargetVideoSize(uploadDetails.width, uploadDetails.height);
        int targetBitrate = calculateTargetVideoBitrate(uploadDetails, maxFileUploadSize);
        File outputFile = File.createTempFile("VID_TR_", ".mp4", context.getCacheDir());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ExportException> errorRef = new AtomicReference<>();
        AtomicReference<RuntimeException> threadErrorRef = new AtomicReference<>();
        Transformer.Listener listener = new Transformer.Listener() {
            @Override
            public void onCompleted(androidx.media3.transformer.Composition composition,
                                    androidx.media3.transformer.ExportResult exportResult) {
                latch.countDown();
            }

            @Override
            public void onError(androidx.media3.transformer.Composition composition,
                                androidx.media3.transformer.ExportResult exportResult,
                                ExportException exportException) {
                errorRef.set(exportException);
                latch.countDown();
            }
        };

        VideoEncoderSettings videoEncoderSettings = new VideoEncoderSettings.Builder()
                .setBitrate(targetBitrate)
                .build();
        EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
                .setEffects(new Effects(
                        Collections.emptyList(),
                        Collections.singletonList(Presentation.createForWidthAndHeight(
                                targetSize[0], targetSize[1], Presentation.LAYOUT_SCALE_TO_FIT))))
                .build();

        HandlerThread transformerThread = new HandlerThread("AttachmentVideoTransformer");
        transformerThread.start();
        Handler transformerHandler = new Handler(transformerThread.getLooper());
        mTransformerThread = transformerThread;
        mTransformerHandler = transformerHandler;

        CountDownLatch startLatch = new CountDownLatch(1);
        transformerHandler.post(() -> {
            try {
                mTransformer = new Transformer.Builder(context)
                        .setLooper(transformerThread.getLooper())
                        .setPortraitEncodingEnabled(true)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(new DefaultEncoderFactory.Builder(context)
                                .setEnableFallback(true)
                                .setRequestedVideoEncoderSettings(videoEncoderSettings)
                                .build())
                        .addListener(listener)
                        .build();
                mTransformer.start(editedMediaItem, outputFile.getAbsolutePath());
            } catch (RuntimeException ex) {
                threadErrorRef.set(ex);
                latch.countDown();
            } finally {
                startLatch.countDown();
            }
        });

        try {
            long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(VIDEO_TRANSCODE_TIMEOUT_MINUTES);
            startLatch.await();
            while (!latch.await(1, TimeUnit.SECONDS)) {
                if (isStopped()) {
                    requestTransformerCancel();
                    throw new CancellationException();
                }
                if (System.nanoTime() >= deadlineNanos) {
                    requestTransformerCancel();
                    throw new IOException("Timed out while transcoding video");
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            requestTransformerCancel();
            throw new CancellationException();
        } finally {
            shutdownTransformer(listener);
        }

        RuntimeException threadError = threadErrorRef.get();
        if (threadError != null) {
            outputFile.delete();
            throw new IOException("Failed to transcode video", threadError);
        }
        ExportException exportException = errorRef.get();
        if (exportException != null) {
            outputFile.delete();
            throw new IOException("Failed to transcode video", exportException);
        }

        uploadDetails.width = targetSize[0];
        uploadDetails.height = targetSize[1];
        uploadDetails.mimeType = MimeTypes.VIDEO_MP4;
        return outputFile;
    }

    private void requestTransformerCancel() {
        Handler handler = mTransformerHandler;
        if (handler != null) {
            handler.post(() -> {
                if (mTransformer != null) {
                    mTransformer.cancel();
                }
            });
        }
    }

    private void shutdownTransformer(@NonNull Transformer.Listener listener) {
        Handler handler = mTransformerHandler;
        HandlerThread thread = mTransformerThread;
        if (handler != null && thread != null) {
            CountDownLatch cleanupLatch = new CountDownLatch(1);
            handler.post(() -> {
                if (mTransformer != null) {
                    mTransformer.removeListener(listener);
                }
                mTransformer = null;
                cleanupLatch.countDown();
            });
            try {
                cleanupLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            thread.quitSafely();
            try {
                thread.join(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        } else {
            mTransformer = null;
        }
        mTransformerHandler = null;
        mTransformerThread = null;
    }

    private static BitmapFactory.Options boundsFromBitmapBits(byte[] bits) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        InputStream bais = new ByteArrayInputStream(bits);
        BitmapFactory.decodeStream(bais, null, options);
        try {
            bais.close();
        } catch (IOException ignored) {}
        return options;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int len;
        while ((len = is.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
        // No need to close ByteArrayOutputStream.
        // ByteArrayOutputStream.close() is a noop.
        return out.toByteArray();
    }

    static class UploadDetails {
        String mimeType;
        String previewMime;

        String filePath;
        String fileName;
        long fileSize;

        int imageOrientation;
        int width;
        int height;
        int duration;

        String valueRef;
        byte[] valueBits;

        // Video poster.
        String previewFileName;
        int previewSize;
        String previewRef;
        byte[] previewBits;
    }

    static class PreparedImage {
        final Bitmap bitmap;
        final byte[] bits;

        PreparedImage(Bitmap bitmap, byte[] bits) {
            this.bitmap = bitmap;
            this.bits = bits;
        }
    }
}

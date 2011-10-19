package pl.polidea.imagemanager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Point;
import android.net.Uri;
import android.util.Log;

/**
 * Image manager. Manager provides way to load image resources asynchronously
 * with many options like:
 * <ul>
 * <li>loading from
 * <ul>
 * <li>file system
 * <li>application resources
 * </ul>
 * <li>caching
 * <li>low-quality preview
 * <li>sub-sampling
 * <li>loading rescaled bitmap
 * <li>using strong GC-proof cache
 * </ul>
 * Provides optional logging on different levels which makes it easy to debug
 * your code. Image manager should be interfaced mostly by
 * {@link #getImage(ImageManagerRequest)} or using
 * {@link pl.polidea.imagemanager.ManagedImageView}
 * 
 * 
 * @author karooolek
 * @see #getImage(ImageManagerRequest)
 * @see pl.polidea.imagemanager.ManagedImageView
 */
public final class ImageManager {

    private static final String TAG = ImageManager.class.getSimpleName();

    /**
     * Image load thread helper class.
     * 
     * @author karooolek
     */
    private static final class LoadThread extends Thread {
        private LoadThread() {
            super(TAG);
        }

        @Override
        public void run() {
            if (logging) {
                Log.d(TAG, "Image loading thread started");
            }

            // loop
            final boolean exit = false;
            while (!exit) {
                // get loading request
                ImageManagerRequest req = null;
                try {
                    loadingReqs.add(req = loadQueue.take());
                } catch (final InterruptedException e) {
                    break;
                }

                try {
                    // load bitmap
                    final Bitmap bmp = loadImage(req, false);

                    // remove preview image
                    if (isImageLoaded(req)) {
                        final Bitmap prevbmp = getLoadedBitmap(req);
                        if (prevbmp != null && !prevbmp.isRecycled()) {
                            if (logging) {
                                Log.d(TAG, "Unloading preview image " + req);
                            }

                            prevbmp.recycle();

                            if (logging) {
                                Log.d(TAG, "Preview image " + req + " unloaded");
                            }
                        }
                    }

                    // save bitmap
                    loaded.put(req, new LoadedBitmap(bmp, req.strong));
                } catch (final OutOfMemoryError err) {
                    // oh noes! we have no memory for image
                    if (logging) {
                        Log.e(TAG, "Error while loading full image " + req + ". Out of memory.");
                        logImageManagerStatus();
                    }

                    cleanUp();
                }

                loadingReqs.remove(req);
            } // while(!exit)

            if (logging) {
                Log.d(TAG, "Image loading thread ended");
            }
        }
    }

    /**
     * Image download thread helper class.
     * 
     * @author karooolek
     */
    private static final class DownloadThread extends Thread {
        private DownloadThread() {
            super(TAG);
        }

        @Override
        public void run() {
            if (logging) {
                Log.d(TAG, "Image downloading thread started");
            }

            // loop
            final boolean exit = false;
            while (!exit) {
                // get downloading URI
                Uri uri = null;
                try {
                    downloadingUris.add(uri = downloadQueue.take());
                } catch (final InterruptedException e) {
                    break;
                }

                try {
                    // download
                    downloadImage(uri, getFilenameForUri(uri));
                } catch (final Exception e) {
                    // some problems with downloading officer
                    if (logging) {
                        Log.e(TAG, "Error while downloading image from " + uri);
                    }
                }

                downloadingUris.remove(uri);
            } // while(!exit)

            if (logging) {
                Log.d(TAG, "Image downloading thread ended");
            }
        }
    }

    /**
     * Loaded bitmap helper class.
     * 
     * @author karooolek
     */
    private static final class LoadedBitmap {
        private final WeakReference<Bitmap> weakBitmap;
        private final Bitmap bitmap;

        LoadedBitmap(final Bitmap bitmap, final boolean strong) {
            this.bitmap = strong ? bitmap : null;
            this.weakBitmap = strong ? null : new WeakReference<Bitmap>(bitmap);
        }

        Bitmap getBitmap() {
            return weakBitmap == null ? bitmap : weakBitmap.get();
        }
    }

    private static Application application;
    private static long start;
    private static boolean logging = false;
    private static List<ImageManagerRequest> requests = new ArrayList<ImageManagerRequest>();
    private static BlockingQueue<ImageManagerRequest> loadQueue = new LinkedBlockingQueue<ImageManagerRequest>();
    private static List<ImageManagerRequest> loadingReqs = new ArrayList<ImageManagerRequest>();
    private static Map<ImageManagerRequest, LoadedBitmap> loaded = new ConcurrentHashMap<ImageManagerRequest, LoadedBitmap>();
    private static BlockingQueue<Uri> downloadQueue = new LinkedBlockingQueue<Uri>();
    private static List<Uri> downloadingUris = new ArrayList<Uri>();

    private ImageManager() {
        // unreachable private constructor
    }

    /**
     * Initialize image manager for application.
     * 
     * @param application
     *            application context.
     */
    public static void init(final Application application) {
        ImageManager.application = application;
    }

    private static boolean isImageLoaded(final ImageManagerRequest req) {
        return loaded.containsKey(req);
    }

    private static boolean isImageLoading(final ImageManagerRequest req) {
        return loadQueue.contains(req) || loadingReqs.contains(req);
    }

    private static void queueImageLoad(final ImageManagerRequest req) {
        if (logging) {
            Log.d(TAG, "Queuing image " + req + " to load");
        }
        loadQueue.add(req);
    }

    private static Bitmap getLoadedBitmap(final ImageManagerRequest req) {
        return isImageLoaded(req) ? loaded.get(req).getBitmap() : null;
    }

    private static String getFilenameForUri(final Uri uri) {
        return application.getCacheDir() + "/image_manager/" + String.valueOf(uri.toString().hashCode());
    }

    private static boolean isImageDownloaded(final Uri uri) {
        if (isImageDownloading(uri)) {
            return false;
        }

        final File file = new File(getFilenameForUri(uri));
        return file.exists() && !file.isDirectory();
    }

    private static boolean isImageDownloading(final Uri uri) {
        return downloadQueue.contains(uri) || downloadingUris.contains(uri);
    }

    private static void queueImageDownload(final ImageManagerRequest req) {
        if (logging) {
            Log.d(TAG, "Queuing image " + req + " to download");
        }
        downloadQueue.add(req.uri);
    }

    /**
     * Load image request. Loads synchronously image specified by request. Adds
     * loaded image to cache.
     * 
     * @param req
     *            image request
     * @param preview
     *            loading preview or not.
     * @return loaded image.
     */
    public static Bitmap loadImage(final ImageManagerRequest req, final boolean preview) {
        // no request
        if (req == null) {
            return null;
        }

        if (logging) {
            if (preview) {
                Log.d(TAG, "Loading preview image " + req);
            } else {
                Log.d(TAG, "Loading full image " + req);
            }
        }

        Bitmap bmp = null;

        // loading options
        final Options opts = new Options();

        // sub-sampling options
        opts.inSampleSize = preview ? 8 : req.subsample;

        // load from filename
        if (req.filename != null) {
            final File file = new File(req.filename);
            if (!file.exists() || file.isDirectory()) {
                if (logging) {
                    Log.d(TAG, "Error while loading image " + req + ". File does not exist.");
                }
                return null;
            }

            bmp = BitmapFactory.decodeFile(req.filename, opts);
        }

        // load from resources
        if (req.resId >= 0) {
            bmp = BitmapFactory.decodeResource(application.getResources(), req.resId, opts);
        }

        // load from uri
        if (req.uri != null) {
            final String filename = getFilenameForUri(req.uri);

            if (!isImageDownloaded(req.uri)) {
                if (logging) {
                    Log.d(TAG, "Error while loading image " + req + ". File was not downloaded.");
                }
                return null;
            }

            bmp = BitmapFactory.decodeFile(filename, opts);
        }

        // scaling options
        if (!preview && (req.width > 0 && req.height > 0)) {
            final Bitmap sBmp = Bitmap.createScaledBitmap(bmp, req.width, req.height, true);
            if (sBmp != null) {
                bmp.recycle();
                bmp = sBmp;
            }
        }

        if (logging) {
            if (preview) {
                Log.d(TAG, "Preview image " + req + " loaded");
            } else {
                Log.d(TAG, "Full image " + req + " loaded");
            }
        }

        return bmp;
    }

    /**
     * Unload image specified by image request and remove it from cache.
     * 
     * @param req
     *            image request.
     */
    public static void unloadImage(final ImageManagerRequest req) {
        if (logging) {
            Log.d(TAG, "Unloading image " + req);
        }

        requests.remove(req);
        loadQueue.remove(req);
        final Bitmap bmp = getLoadedBitmap(req);
        if (bmp != null) {
            bmp.recycle();
        }

        loaded.remove(req);
        if (logging) {
            Log.d(TAG, "Image " + req + " unloaded");
        }
    }

    private static void readFile(final File filename, final InputStream inputStream) throws IOException {
        final byte[] buffer = new byte[1024];
        final OutputStream out = new FileOutputStream(filename);
        try {
            int r = inputStream.read(buffer);
            while (r != -1) {
                out.write(buffer, 0, r);
                out.flush();
                r = inputStream.read(buffer);
            }
        } finally {
            try {
                out.flush();
            } finally {
                out.close();
            }
        }
    }

    /**
     * Download image from specified URI to specified file in file system.
     * 
     * @param uri
     *            image URI.
     * @param filename
     *            image file name to download.
     * @throws URISyntaxException
     *             thrown when URI is invalid.
     * @throws ClientProtocolException
     *             thrown when there is problem with connecting.
     * @throws IOException
     *             thrown when there is problem with connecting.
     */
    public static void downloadImage(final Uri uri, final String filename) throws URISyntaxException, IOException {
        if (logging) {
            Log.d(TAG, "Downloading image from " + uri);
        }

        // connect to uri
        final DefaultHttpClient client = new DefaultHttpClient();
        final HttpGet getRequest = new HttpGet(new URI(uri.toString()));
        final HttpResponse response = client.execute(getRequest);
        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != HttpStatus.SC_OK) {
            Log.w(TAG, "Error " + statusCode + " while retrieving file from " + uri);
        }

        // create file
        final File file = new File(filename);
        final File parent = new File(file.getParent());
        if (!parent.exists() && !parent.mkdir()) {
            Log.w(TAG, "Parent directory doesn't exist");
        }

        // download
        final HttpEntity entity = response.getEntity();
        if (entity == null) {
            Log.w(TAG, "Null entity received when downloading " + uri);
        }
        final InputStream inputStream = entity.getContent();
        try {
            readFile(file, new BufferedInputStream(inputStream, 1024));
        } finally {
            inputStream.close();
            entity.consumeContent();
        }

        if (logging) {
            Log.d(TAG, "Image from " + uri + " downloaded");
        }
    }

    /**
     * Clean up image manager. Unloads all cached images. Deletes all downloaded images.
     */
    public static synchronized void cleanUp() {
        if (logging) {
            Log.d(TAG, "Image manager clean up");
        }
    
        for (final ImageManagerRequest req : loaded.keySet()) {
            if (logging) {
                Log.d(TAG, "Unloading image " + req);
            }
    
            final Bitmap bmp = getLoadedBitmap(req);
            if (bmp != null) {
                bmp.recycle();
            }
    
            if (logging) {
                Log.d(TAG, "Image " + req + " unloaded");
            }
        }
        loaded.clear();
        loadQueue.clear();
        requests.clear();
    
        if (logging) {
            logImageManagerStatus();
        }
    }

    /**
     * Check if image manager logging is enabled. By default logging is
     * disabled.
     * 
     * @return true if image manager logging is enabled, false otherwise.
     */
    public static boolean isLoggingEnabled() {
        return logging;
    }

    /**
     * Enable/disable image manager logging.
     * 
     * @param logging
     *            enable/disable image manager logging.
     */
    public static void setLoggingEnabled(final boolean logging) {
        ImageManager.logging = logging;
    }

    /**
     * Log image manager current status. Logs:
     * <ul>
     * <li>manager uptime in seconds
     * <li>all loaded images details
     * <li>used memory
     * </ul>
     */
    public static void logImageManagerStatus() {
        final float t = 0.001f * (System.currentTimeMillis() - start);

        Log.d(TAG, "Uptime: " + t + "[s]");

        final int imgn = loaded.size();
        Log.d(TAG, "Loaded images: " + imgn);

        if (imgn > 0) {
            int totalSize = 0;
            for (final LoadedBitmap limg : loaded.values()) {
                final Bitmap bmp = limg.getBitmap();

                // no bitmap
                if (bmp == null) {
                    continue;
                }

                // get bits per pixel
                int bpp = 0;
                if (bmp.getConfig() != null) {
                    switch (bmp.getConfig()) {
                    case ALPHA_8:
                        bpp = 1;
                        break;
                    case RGB_565:
                    case ARGB_4444:
                        bpp = 2;
                        break;
                    case ARGB_8888:
                    default:
                        bpp = 4;
                        break;
                    }
                }

                // count total size
                totalSize += bmp.getWidth() * bmp.getHeight() * bpp;
            }

            Log.d(TAG, "Estimated loaded images size: " + totalSize / 1024 + "[kB]");
        }

        Log.d(TAG, "Queued images: " + loadQueue.size());
    }

    /**
     * Get size of image specified by image request.
     * 
     * @param req
     *            image request.
     * @return image dimensions.
     */
    public static Point getImageSize(final ImageManagerRequest req) {
        final Options options = new Options();
        options.inJustDecodeBounds = true;
        if (req.filename != null) {
            BitmapFactory.decodeFile(req.filename, options);
        }
        if (req.resId >= 0) {
            BitmapFactory.decodeResource(application.getResources(), req.resId, options);
        }
        if (req.uri != null && isImageDownloaded(req.uri)) {
            BitmapFactory.decodeFile(getFilenameForUri(req.uri), options);
        }
        return new Point(options.outWidth, options.outHeight);
    }

    /**
     * Get image specified by image request. This returns image as currently
     * available in manager, which means:
     * <ul>
     * <li>not loaded at all: NULL - no image
     * <li>loaded preview
     * <li>loaded full
     * </ul>
     * If image is not available in cache, image request is posted to
     * asynchronous loading and will be available soon. All image options are
     * specified in image request.
     * 
     * @param req
     *            image request.
     * @return image as currently available in manager (preview/full) or NULL if
     *         it's not available at all.
     * @see pl.polidea.imagemanager.ImageManagerRequest
     */
    public static Bitmap getImage(final ImageManagerRequest req) {
        Bitmap bmp = null;

        // save bitmap request
        synchronized (requests) {
            requests.remove(req);
            requests.add(req);
        }

        // look for bitmap in already loaded resources
        if (isImageLoaded(req)) {
            bmp = getLoadedBitmap(req);
        }

        // bitmap found
        if (bmp != null) {
            return bmp;
        }

        // wait until image is not downloaded
        if (req.uri != null && !isImageDownloaded(req.uri)) {
            // start download if necessary
            if (!isImageDownloading(req.uri)) {
                queueImageDownload(req);
            }
            return null;
        }

        // load preview image quickly
        if (req.preview) {
            try {
                bmp = loadImage(req, true);
                if (bmp == null) {
                    return null;
                }

                // save preview image
                loaded.put(req, new LoadedBitmap(bmp, req.strong));
            } catch (final OutOfMemoryError err) {
                // oh noes! we have no memory for image
                if (logging) {
                    Log.e(TAG, "Error while loading preview image " + req + ". Out of memory.");
                    logImageManagerStatus();
                }
            }
        }

        // add image to loading queue
        if (!isImageLoading(req)) {
            queueImageLoad(req);
        }

        return bmp;
    }

    static {
        // save starting time
        start = System.currentTimeMillis();

        // start threads
        new LoadThread().start();
        new LoadThread().start();
        new LoadThread().start();
        new DownloadThread().start();
        new DownloadThread().start();
        new DownloadThread().start();
    }
}

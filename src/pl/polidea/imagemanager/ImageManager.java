package pl.polidea.imagemanager;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Point;
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

    private static final class LoadedBitmap {
        private final WeakReference<Bitmap> weakBitmap;
        private final Bitmap bitmap;

        LoadedBitmap(final Bitmap bitmap, final boolean strong) {
            this.bitmap = strong ? bitmap : null;
            this.weakBitmap = strong ? new WeakReference<Bitmap>(bitmap) : null;
        }

        Bitmap getBitmap() {
            return weakBitmap == null ? bitmap : weakBitmap.get();
        }
    }

    private ImageManager() {
        // unreachable private constructor
    }

    private static long start;
    private static boolean logging = true;
    static Map<ImageManagerRequest, LoadedBitmap> loaded = new ConcurrentHashMap<ImageManagerRequest, LoadedBitmap>();
    static List<ImageManagerRequest> requests = new ArrayList<ImageManagerRequest>();
    static BlockingQueue<ImageManagerRequest> loadQueue = new LinkedBlockingQueue<ImageManagerRequest>();

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
            bmp = BitmapFactory.decodeResource(req.resources, req.resId, opts);
        }

        // scaling options
        if (!preview && (req.width > 0 && req.height > 0)) {
            final Bitmap sBmp = Bitmap.createScaledBitmap(bmp, req.width, req.height, true);
            if (sBmp != null) {
                bmp.recycle();
                bmp = sBmp;
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
        final Bitmap bmp = loaded.get(req).getBitmap();
        if (bmp != null) {
            bmp.recycle();
        }
        loaded.remove(req);
    }

    /**
     * Clean up image manager. Unloads all cached images.
     */
    public static synchronized void cleanUp() {
        if (logging) {
            Log.d(TAG, "Image manager clean up");
        }

        for (final ImageManagerRequest req : loaded.keySet()) {
            if (logging) {
                Log.d(TAG, "Unloading image " + req);
            }
            final Bitmap bmp = loaded.get(req).getBitmap();
            if (bmp != null) {
                bmp.recycle();
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
     * Initialize image manager loading thread. This is asynchronous thread
     * where image request are processed and loaded to cache.
     */
    public static void initLoadingThread() {
        final Thread t = new Thread(TAG) {
            @Override
            public void run() {
                // save start time
                start = System.currentTimeMillis();

                if (logging) {
                    Log.d(TAG, "Image loading thread started");
                }

                // loop
                final boolean exit = false;
                while (!exit) {
                    // get loading request
                    ImageManagerRequest req;
                    try {
                        req = loadQueue.take();
                    } catch (final InterruptedException e) {
                        break;
                    }

                    try {
                        // load bitmap
                        final Bitmap bmp = loadImage(req, false);

                        // remove preview image
                        if (loaded.containsKey(req)) {
                            final Bitmap prevbmp = loaded.get(req).getBitmap();
                            if (prevbmp != null && !prevbmp.isRecycled()) {
                                if (logging) {
                                    Log.d(TAG, "Unloading preview image " + req);
                                }
                                prevbmp.recycle();
                            }
                        }

                        // save bitmap
                        loaded.put(req, new LoadedBitmap(bmp, req.strong));

                        // logImageManagerStatus();
                    } catch (final OutOfMemoryError err) {
                        // oh noes! we have no memory for image
                        if (logging) {
                            Log.e(TAG, "Error while loading full image " + req + ". Out of memory.");
                        }
                        cleanUp();
                    }
                } // while(!exit)

                if (logging) {
                    Log.d(TAG, "Image loading thread ended");
                }
            }
        };
        t.start();
    }

    /**
     * Check if image manager logging is enabled.
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

        if (logging) {
            Log.d(TAG, "Uptime: " + t + "[s]");
        }

        final int imgn = loaded.size();
        if (logging) {
            Log.d(TAG, "Loaded images: " + imgn);
        }

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

            if (logging) {
                Log.d(TAG, "Loaded images size: " + totalSize / 1024 + "[kB]");
            }
        }

        if (logging) {
            Log.d(TAG, "Queued images: " + loadQueue.size());
        }
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
            BitmapFactory.decodeResource(req.resources, req.resId, options);
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
        if (loaded.containsKey(req)) {
            bmp = loaded.get(req).getBitmap();
        }

        // bitmap found
        if (bmp != null) {
            return bmp;
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

                // logImageManagerStatus();
            } catch (final OutOfMemoryError err) {
                // oh noes! we have no memory for image
                if (logging) {
                    Log.e(TAG, "Error while loading preview image " + req + ". Out of memory.");
                    logImageManagerStatus();
                }
            }
        }

        // add image to loading queue
        if (!loadQueue.contains(req)) {
            if (logging) {
                Log.d(TAG, "Queuing image " + req + " to load");
            }
            loadQueue.add(req);
        }

        return bmp;
    }

    static {
        initLoadingThread();
    }
}

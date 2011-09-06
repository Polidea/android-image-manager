package pl.polidea.imagemanager;

import android.content.res.Resources;

/**
 * Image manager request. This is image manager helper class and public
 * interface to communicate with image manager.
 * 
 * @author karooolek
 * 
 */
public final class ImageManagerRequest {

    /**
     * Image file name in file system.
     */
    public String filename = null;

    /**
     * Resources containing image.
     */
    public Resources resources = null;

    /**
     * Image resoucrce ID in resources.
     */
    public int resId = -1;

    /**
     * Sub-sampling value.
     */
    public int subsample = 1;

    /**
     * Desired image width.
     */
    public int width = -1;

    /**
     * Desired image height.
     */
    public int height = -1;

    /**
     * Low-quality preview option.
     */
    public boolean preview = true;

    /**
     * Strong cache option.
     */
    public boolean strong = false;

    /**
     * Create image request to image from file system.
     * 
     * @param filename
     *            file name in file system.
     */
    public ImageManagerRequest(final String filename) {
        this.filename = filename;
    }

    /**
     * Create image request to image from resources.
     * 
     * @param resources
     *            resources.
     * @param resId
     *            resource ID.
     */
    public ImageManagerRequest(final Resources resources, final int resId) {
        this.resources = resources;
        this.resId = resId;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((filename == null) ? 0 : filename.hashCode());
        result = prime * result + height;
        result = prime * result + (preview ? 1231 : 1237);
        result = prime * result + resId;
        result = prime * result + (strong ? 1231 : 1237);
        result = prime * result + subsample;
        result = prime * result + width;
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final ImageManagerRequest other = (ImageManagerRequest) obj;
        if (filename == null) {
            if (other.filename != null) {
                return false;
            }
        } else if (!filename.equals(other.filename)) {
            return false;
        }
        if (height != other.height) {
            return false;
        }
        if (preview != other.preview) {
            return false;
        }
        if (resId != other.resId) {
            return false;
        }
        if (strong != other.strong) {
            return false;
        }
        if (subsample != other.subsample) {
            return false;
        }
        if (width != other.width) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "[filename=" + filename + ", resId=" + resId + ", subsample=" + subsample + ", width=" + width
                + ", height=" + height + ", preview=" + preview + ", strong=" + strong + "]";
    }

}
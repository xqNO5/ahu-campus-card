package android.content;

import android.content.res.AssetManager;
import java.io.File;

/**
 * Stand-in for android.content.Context, reduced to the only thing the offline harness needs:
 * `getAssets()`. Point it at the module's real `app/src/main/assets` directory so the asset
 * path used on device (`captcha_compact.bin`) is the same string resolved here.
 */
public class Context {

    private final AssetManager assets;

    public Context(AssetManager assets) {
        this.assets = assets;
    }

    /** Convenience for the harness: assets of a module rooted at [assetsDir]. */
    public Context(File assetsDir) {
        this(new AssetManager(assetsDir));
    }

    public AssetManager getAssets() {
        return assets;
    }
}

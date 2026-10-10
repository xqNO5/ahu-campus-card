package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stand-in for Android's AssetManager, backed by a directory that mirrors the APK's `assets/`
 * layout. Missing assets throw IOException, like the real one, so the fallback path in
 * CaptchaRecognizer is exercised rather than assumed.
 */
public class AssetManager {

    private final File root;

    public AssetManager(File root) {
        this.root = root;
    }

    public File getRoot() {
        return root;
    }

    public InputStream open(String name) throws IOException {
        File file = new File(root, name);
        if (!file.isFile()) {
            throw new IOException("asset not found: " + file.getAbsolutePath());
        }
        return new FileInputStream(file);
    }
}

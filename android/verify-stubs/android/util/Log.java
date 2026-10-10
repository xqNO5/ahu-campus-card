package android.util;

/**
 * Stand-in for android.util.Log that writes to stdout, so the offline harness can show what
 * the app would log (and, more usefully, prove that nothing sensitive is logged).
 */
public final class Log {

    private Log() {
    }

    public static int d(String tag, String message) {
        return print("D", tag, message);
    }

    public static int i(String tag, String message) {
        return print("I", tag, message);
    }

    public static int w(String tag, String message) {
        return print("W", tag, message);
    }

    public static int e(String tag, String message) {
        return print("E", tag, message);
    }

    private static int print(String level, String tag, String message) {
        System.out.println("ANDROID-LOG " + level + "/" + tag + ": " + message);
        return 0;
    }
}

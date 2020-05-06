package top.canyie.pine.examples;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Member;

import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;
import top.canyie.pine.utils.ReflectionHelper;

/**
 * @author canyie
 */
public class ExampleApp extends Application {
    public static final String TAG = "PineExample";
    private static ExampleApp instance;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        instance = this;
    }

    @Override public void onCreate() {
        super.onCreate();

        PineConfig.debug = true;
        PineConfig.debuggable = BuildConfig.DEBUG;

        Pine.disableJitInline();
    }

    public static ExampleApp getInstance() {
        if (instance == null) throw new IllegalStateException();
        return instance;
    }
}

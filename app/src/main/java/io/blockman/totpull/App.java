package io.blockman.totpull;

import android.app.Application;
import android.content.Context;

/** Seeds {@link AppStrings} so model/parser classes can resolve localized strings. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppStrings.init(this);
    }
}

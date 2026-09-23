package io.noties.markwon.sample;

import androidx.multidex.MultiDexApplication;

/**
 * 引入 jlatexmath / glide / appcompat 之后方法数超过 65536，而 minSdk 是 16，
 * 所以必须走 MultiDex。
 */
public class SampleApp extends MultiDexApplication {

    @Override
    public void onCreate() {
        super.onCreate();
        // markwon-block 的全局默认样式统一从 DefaultTheme 取（chat-demo 同款观感）
        DefaultTheme.applyBlockDefaults();
    }
}

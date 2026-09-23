package io.noties.markwon.chatdemo.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/**
 * 剪贴板写入兼容工具
 *
 * Kotlin 1.4 + compileSdk 29 下 ClipboardManager.setPrimaryClip() 会被误解析为
 * val 属性赋值（getter @Nullable / setter 平台类型不一致，未合成 var），
 * 在 Java 侧调用可绕过属性合成，稳定写入剪贴板。
 */
public final class ClipboardCompat {

    private ClipboardCompat() {
    }

    /** 写入纯文本剪贴板，返回是否成功 */
    public static boolean setText(Context context, CharSequence label, CharSequence text) {
        try {
            ClipboardManager cm =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
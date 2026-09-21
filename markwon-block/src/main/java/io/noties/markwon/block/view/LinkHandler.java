package io.noties.markwon.block.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 链接跳转处理（复刻豆包 JumpAnalyzer + EnsureManager 兜底）。
 *
 * <p>点击链接：系统浏览器打开；无可用 Handler / 深链解析失败时降级为复制链接。
 */
public final class LinkHandler {

    private LinkHandler() {
    }

    public static void handleClick(@NonNull Context context, @Nullable String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // 降级：复制到剪贴板
            copyToClipboard(context, url);
        }
    }

    public static void copyToClipboard(@NonNull Context context, @NonNull String text) {
        final ClipboardManager cm =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("markdown-block", text));
        }
    }
}
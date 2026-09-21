package io.noties.markwon.sample;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import io.noties.markwon.block.render.MdTheme;
import io.noties.markwon.block.view.MarkdownTextBlockView;

/**
 * markwon-block 模块演示页：展示 {@link MarkdownTextBlockView} 一键接入能力。
 *
 * <p>覆盖三个场景：
 * <ol>
 *     <li><b>整段渲染</b>：{@link MarkdownTextBlockView#renderMarkdown(String)}
 *         一次出全量富文本，文本 / 代码块 / 独立行图片 / 分隔线自动拆成块视图；</li>
 *     <li><b>流式增量</b>：{@link MarkdownTextBlockView#appendMarkdown(String)}
 *         按 SSE 节奏逐 chunk 追加，演示「旧块复用 / 新块追加 / 当前块增量修改」的
 *         打字机渲染（新后缀淡入 + 闪烁光标）；</li>
 *     <li><b>明暗主题</b>：切换 {@link MdTheme#light()} / {@link MdTheme#dark()}，
 *         验证主题实时影响整体配色。</li>
 * </ol>
 */
public class BlockDemoActivity extends AppCompatActivity {

    private static final String TAG = "BlockDemo";

    private static final long STREAM_DELAY_MS = 12L;
    private static final int STREAM_CHUNK_SIZE = 4;

    private MarkdownTextBlockView blockView;
    private ScrollView scrollView;
    private Button themeButton;

    private boolean dark;

    // 流式演示状态
    private String streamSource;
    private int streamOffset;
    private long streamStartMs;
    private final Handler streamHandler = new Handler(Looper.getMainLooper());
    private final Runnable streamTick = this::deliverStreamChunk;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_block_demo);

        blockView = findViewById(R.id.block_view);
        scrollView = findViewById(R.id.block_scroll_view);
        themeButton = findViewById(R.id.button_block_theme);

        // 链接点击兜底：这里只打日志，真实业务一般在 blockView.setOnLinkClick 里接回调
        blockView.setOnLinkClick(url -> Log.i(TAG, "link clicked: " + url));

        findViewById(R.id.button_block_render).setOnClickListener(v -> renderFullDoc());
        findViewById(R.id.button_block_stream).setOnClickListener(v -> startStream());
        themeButton.setOnClickListener(v -> toggleTheme());

        renderFullDoc();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStream();
    }

    // ---------------------------------------------------------------------
    // 场景一：整段渲染
    // ---------------------------------------------------------------------

    private void renderFullDoc() {
        stopStream();
        blockView.renderMarkdown(readRawText(R.raw.case_block_demo));
        scrollToBottom();
    }

    // ---------------------------------------------------------------------
    // 场景二：流式增量（打字机）
    // ---------------------------------------------------------------------

    private void startStream() {
        stopStream();
        streamSource = readRawText(R.raw.case_block_demo);
        streamOffset = 0;
        streamStartMs = SystemClock.elapsedRealtime();
        streamHandler.postDelayed(streamTick, STREAM_DELAY_MS);
        Log.i(TAG, "stream start, source length=" + streamSource.length());
    }

    private void deliverStreamChunk() {
        if (streamSource == null || streamOffset >= streamSource.length()) {
            finishStream();
            return;
        }
        final int end = Math.min(streamSource.length(), streamOffset + STREAM_CHUNK_SIZE);
        final String chunk = streamSource.substring(streamOffset, end);
        streamOffset = end;
        blockView.appendMarkdown(chunk);
        scrollToBottom();
        streamHandler.postDelayed(streamTick, STREAM_DELAY_MS);
    }

    private void finishStream() {
        Log.i(TAG, "stream finished in " + (SystemClock.elapsedRealtime() - streamStartMs) + " ms"
                + ", total=" + (streamSource != null ? streamSource.length() : 0));
        stopStream();
    }

    private void stopStream() {
        streamHandler.removeCallbacks(streamTick);
        streamSource = null;
        streamOffset = 0;
    }

    // ---------------------------------------------------------------------
    // 场景三：明暗主题
    // ---------------------------------------------------------------------

    private void toggleTheme() {
        stopStream();
        dark = !dark;
        blockView.setTheme(dark ? MdTheme.dark() : MdTheme.light());
        themeButton.setText(getString(
                dark ? R.string.button_block_theme_light : R.string.button_block_theme_dark));
        // 主题只影响视觉范式，重新渲染即可生效；整段方式一次性渲染当前文本
        blockView.renderMarkdown(blockView.getMarkdownText());
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ---------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------

    @NonNull
    private String readRawText(@RawRes int resId) {
        InputStream inputStream = null;
        try {
            inputStream = getResources().openRawResource(resId);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        } catch (IOException e) {
            Log.e(TAG, "Exception reading raw markdown resource", e);
            return "";
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                    // no-op
                }
            }
        }
    }
}
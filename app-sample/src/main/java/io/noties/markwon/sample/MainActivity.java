package io.noties.markwon.sample;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.target.Target;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Objects;

import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonAppendState;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.image.AsyncDrawable;
import io.noties.markwon.image.ImagesPlugin;
import io.noties.markwon.image.data.DataUriSchemeHandler;
import io.noties.markwon.image.file.FileSchemeHandler;
import io.noties.markwon.image.glide.GlideImagesPlugin;
import io.noties.markwon.image.network.NetworkSchemeHandler;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;
import io.noties.markwon.syntax.SyntaxHighlightPlugin;
import io.noties.prism4j.Prism4j;

/**
 * 案例页面：从 {@code res/raw} 读取 Markdown 文本，交给 Markwon 渲染到 TextView。
 *
 * <p>渲染链路：
 * <ol>
 *     <li>{@link #readRawText(int)} 读取 raw 资源中的 Markdown 字符串；</li>
 *     <li>{@link #createMarkwon(RequestManager)} 构建 Markwon 实例并注册插件；</li>
 *     <li>{@link Markwon#setMarkdown(TextView, String)} 解析 + 渲染 + 调度异步图片 / 公式。</li>
 * </ol>
 *
 * <p><strong>关于图片方案</strong>：{@link GlideImagesPlugin} 与 {@link ImagesPlugin} 都会向
 * {@code MarkwonConfiguration} 注册 {@code asyncDrawableLoader}，同时注册后一个覆盖另一个，
 * 所以二者只能二选一，由 {@link #IMAGE_MODE} 常量切换。
 */
public class MainActivity extends AppCompatActivity {

    /**
     * 图片加载方案。改成 {@link #IMAGE_MODE_IMAGES_PLUGIN} 即可切到 markwon-image 自带的加载器
     * （走 HttpURLConnection，支持 data-uri / file / SVG / GIF，不依赖 Glide）。
     */
    private static final int IMAGE_MODE_GLIDE = 0;
    private static final int IMAGE_MODE_IMAGES_PLUGIN = 1;
    private static final int IMAGE_MODE = IMAGE_MODE_GLIDE;

    private static final String TAG = "MainActivity";

    /**
     * 流式（SSE）模拟参数：每个 {@link #SSE_CHUNK_DELAY_MS} 毫秒投递
     * {@link #SSE_CHUNK_SIZE} 个字符（可近似理解为 LLM 的 token 粒度）。
     */
    private static final long SSE_CHUNK_DELAY_MS = 10L;
    private static final int SSE_CHUNK_SIZE = 3;

    private Markwon markwon;
    private TextView textView;
    private ScrollView scrollView;

    // 流式模拟的运行时状态
    private MarkwonAppendState sseState;
    private Spanned sseSpanned;
    private String sseSource;
    private int sseOffset;
    private long sseStartMs;
    private final Handler sseHandler = new Handler(Looper.getMainLooper());
    private final Runnable sseTick = this::deliverSseChunk;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textView = findViewById(R.id.text_view);
        scrollView = findViewById(R.id.scroll_view);

        // 使用 applicationContext，避免 Activity 销毁后 Glide 仍在请求导致异常
        markwon = createMarkwon(Glide.with(getApplicationContext()));

        findViewById(R.id.button_image_case).setOnClickListener(v -> show(R.raw.case_image));
        findViewById(R.id.button_all_plugins).setOnClickListener(v -> show(R.raw.case_all_plugins));
        // 流式 SSE 案例：按 token 粒度逐块追加渲染，结束后校验与整段解析的结果是否一致
        findViewById(R.id.button_sse_case).setOnClickListener(v -> startSseCase(R.raw.case_3));
        // markwon-block 模块演示：MarkdownTextBlockView 一键接入（整段 / 流式 / 明暗主题）
        findViewById(R.id.button_block_demo)
                .setOnClickListener(v -> startActivity(new Intent(this, BlockDemoActivity.class)));

        show(R.raw.case_image);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSse();
    }

    private void show(@RawRes int resId) {
        stopSse();
        markwon.setMarkdown(textView, readRawText(resId));
    }

    // ---------------------------------------------------------------------
    // 流式（SSE）模拟
    // ---------------------------------------------------------------------

    /**
     * 开始模拟 SSE：把完整的 Markdown 文本切成固定大小的 chunk，
     * 模拟「LLM 每隔一段时间吐出一个 token」的到达节奏，
     * 每个 chunk 到达后用 {@link Markwon#appendMarkdown} 增量解析并立即渲染。
     */
    private void startSseCase(@RawRes int resId) {
        stopSse();
        sseSource = readRawText(resId);
        sseOffset = 0;
        sseStartMs = SystemClock.elapsedRealtime();
        sseState = new MarkwonAppendState();
        sseSpanned = new SpannableStringBuilder();
        sseHandler.postDelayed(sseTick, SSE_CHUNK_DELAY_MS);
        Log.i(TAG, "SSE start, source length=" + sseSource.length());
    }

    private void deliverSseChunk() {
        if (sseSource == null || sseOffset >= sseSource.length()) {
            finishSse();
            return;
        }

        final int end = Math.min(sseSource.length(), sseOffset + SSE_CHUNK_SIZE);
        final String chunk = sseSource.substring(sseOffset, end);
        sseOffset = end;

        // 核心：增量解析。state 内部只重解析未稳定的尾部，
        // 已稳定的前缀直接复用，避免每来一个 token 就全量重排整篇文档
        long start = System.currentTimeMillis();
        sseSpanned = markwon.appendMarkdown(sseState, chunk);
        long diff = System.currentTimeMillis() - start;
        Log.i(TAG, "SSE deliverSseChunk"
                + ", incremental=" + sseSpanned.length()
                + ", incrementalTime=" + (diff));
        markwon.setParsedMarkdown(textView, sseSpanned);
        scrollToBottom();

        sseHandler.postDelayed(sseTick, SSE_CHUNK_DELAY_MS);
    }

    /**
     * 流式结束：与「一次性整段解析」的结果做字节级对比，
     * 校验 {@link Markwon#appendMarkdown} 的输出与 {@link Markwon#toMarkdown} 完全一致。
     */
    private void finishSse() {
        if (sseSource == null) {
            return;
        }
        long start = System.currentTimeMillis();
        final Spanned full = markwon.toMarkdown(sseSource);
        long diff = System.currentTimeMillis() - start;
        final String expected = full.toString();
        final String incremental = sseSpanned != null ? sseSpanned.toString() : "";
        final boolean identical = expected.equals(incremental);
        Log.i(TAG, "SSE finished in " + (SystemClock.elapsedRealtime() - sseStartMs) + " ms"
                + ", incremental=" + incremental.length()
                + ", full=" + expected.length()
                + ", fullTime=" + (diff)
                + ", identical=" + identical);
        if (!identical) {
            logDiff(expected, incremental);
        }

        // 流式过程中 tail（未稳定区域）里的图片只放占位图、不发起请求。收尾时做一次全量渲染，
        // 这批 drawable 不带 deferred 标记，会真正加载；加载完成后 AsyncDrawableScheduler
        // 发现 bounds 变化，会让 TextView 重新 setText —— 触发重新绘制与重新测量，行高一次性到位。
        markwon.setParsedMarkdown(textView, full);
        scrollToBottom();

        stopSse();
    }

    private static final int MAX_DIFF_LINES = 12;
    private static final int DIFF_CONTEXT = 40;

    /**
     * 逐字符对比，按「公共前缀 / 公共后缀」切出中段差异；中段较大时再按行细分打印。
     */
    private static void logDiff(@NonNull String expected, @NonNull String actual) {
        final int prefix = commonPrefixLength(expected, actual);
        int suffix = 0;
        final int maxSuffix = Math.min(expected.length() - prefix, actual.length() - prefix);
        while (suffix < maxSuffix
                && expected.charAt(expected.length() - 1 - suffix)
                == actual.charAt(actual.length() - 1 - suffix)) {
            suffix += 1;
        }

        final String expMid = expected.substring(prefix, expected.length() - suffix);
        final String actMid = actual.substring(prefix, actual.length() - suffix);
        Log.w(TAG, "DIFF middle (prefix=" + prefix + ", suffix=" + suffix + ")"
                + " exp=[" + escape(clip(expMid)) + "]"
                + " act=[" + escape(clip(actMid)) + "]");

        if (expMid.length() >= 400 || actMid.length() >= 400) {
            // 中段较大，按行细分
            final String[] expLines = expMid.split("\n", -1);
            final String[] actLines = actMid.split("\n", -1);
            final int count = Math.max(expLines.length, actLines.length);
            int logged = 0;
            for (int i = 0; i < count && logged < MAX_DIFF_LINES; i++) {
                final String e = i < expLines.length ? expLines[i] : "<EOF>";
                final String a = i < actLines.length ? actLines[i] : "<EOF>";
                if (!e.equals(a)) {
                    Log.w(TAG, "DIFF line#" + i
                            + " exp=[" + escape(clip(e)) + "]"
                            + " act=[" + escape(clip(a)) + "]");
                    logged += 1;
                }
            }
        }
    }

    private static int commonPrefixLength(@NonNull String a, @NonNull String b) {
        final int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) {
            i += 1;
        }
        return i;
    }

    private static String clip(@NonNull String s) {
        return s.length() <= DIFF_CONTEXT * 2
                ? s
                : s.substring(0, DIFF_CONTEXT) + "..." + s.substring(s.length() - DIFF_CONTEXT);
    }

    private static String escape(@NonNull String s) {
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void stopSse() {
        sseHandler.removeCallbacks(sseTick);
        sseSource = null;
        sseState = null;
        sseSpanned = null;
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.smoothScrollTo(0, textView.getHeight()));
    }

    /**
     * 构建 Markwon 实例。{@link Markwon#builder} 默认已注册 {@code CorePlugin}，这里叠加其余插件。
     */
    @NonNull
    private Markwon createMarkwon(@NonNull final RequestManager requestManager) {
        final Markwon.Builder builder = Markwon.builder(this);

        if (IMAGE_MODE == IMAGE_MODE_GLIDE) {
            builder.usePlugin(createGlideImagesPlugin(requestManager));
        } else {
            builder.usePlugin(createImagesPlugin());
        }

        builder
                // 内联解析器增强（反斜杠转义、更完整的内联规则）
                .usePlugin(MarkwonInlineParserPlugin.create())
                // HTML 标签，默认支持 u / b / i / sub / sup / strike / blockquote / h1-h6 / img / a 等
                .usePlugin(HtmlPlugin.create())
                // 纯文本 URL / 邮箱 / 手机号 自动变成可点击链接
                .usePlugin(LinkifyPlugin.create())
                // ~~删除线~~
                .usePlugin(StrikethroughPlugin.create())
                // - [ ] / - [x] 任务列表，复选框配色见 DefaultTheme#taskListPlugin
                .usePlugin(DefaultTheme.taskListPlugin(this))
                // GFM 表格，尺寸 / 圆角 / 横向滚动 / 底部滚动条见 DefaultTheme#tableTheme。
                // 滚动的开关是 DefaultTheme.TABLE_SCROLL_ENABLED：打开后整块表格区域都能拖动，
                // 底部那条滚动条和代码块是同一样式（共用 DefaultTheme 第五节那组常量）。
                .usePlugin(TablePlugin.create(DefaultTheme.tableTheme(this)))
                // 代码块横向滚动：不可换行 + 顶部语言栏 + 底部滚动条
                // 开关在 DefaultTheme.CODE_BLOCK_SCROLLABLE，相关尺寸/颜色也在那里。
                // 语言栏右侧还有「复制」按钮 —— 样式和行为都是这个插件自己的配置
                // （DefaultTheme#codeBlockScrollPlugin / codeBlockCopyTheme），不经过 MarkwonTheme。
                // 注意：库只把「哪个代码块被点了」+ 原文交出来，剪贴板由宿主自己写，
                // 不接这个 listener 按钮就是个装饰。
                .usePlugin(DefaultTheme.codeBlockScrollPlugin(this)
                        .onCodeBlockCopy((textView, code) -> {
                            final ClipboardManager manager = (ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            if (manager != null) {
                                manager.setPrimaryClip(ClipData.newPlainText(null, code));
                            }
                            Log.i(TAG, "code block copied, length=" + code.length());
                        }))
                // 自定义分隔符：==高亮== 与 ++下划线++（示例扩展，高亮色见 DefaultTheme）
                .usePlugin(DefaultTheme.simpleExtPlugin())
                // LaTeX 公式：$$块级$$ 与 $行内$（显示开关见 DefaultTheme.LATEX_*）
                .usePlugin(DefaultTheme.latexPlugin(this))
                // 代码块高亮，语法定义见 SampleGrammarLocator，未命中的语言回退到 java
                .usePlugin(SyntaxHighlightPlugin.create(
                        new Prism4j(new SampleGrammarLocator()),
                        DefaultTheme.syntaxTheme(),
                        "java"))
                // 主题必须最后注册：MarkwonBuilderImpl 按注册顺序累加 configureTheme，
                // 后注册的覆盖先注册的，而 SyntaxHighlightPlugin 会写 codeBlockBackgroundColor
                // / codeBlockTextColor。
                .usePlugin(DefaultTheme.markwonPlugin(this));

        return builder.build();
    }

    /**
     * 图片方案 A：Glide。Markwon 先插入占位 {@link AsyncDrawable}，Glide 回调写入真实 Drawable，
     * 再由 {@code AsyncDrawableScheduler} 触发 TextView 重绘。
     */
    @NonNull
    private GlideImagesPlugin createGlideImagesPlugin(@NonNull final RequestManager requestManager) {
        return GlideImagesPlugin.create(new GlideImagesPlugin.GlideStore() {
            @NonNull
            @Override
            public RequestBuilder<Drawable> load(@NonNull AsyncDrawable drawable) {
                return requestManager
                        .load(drawable.getDestination())
                        // 关闭 crossfade：流式渲染时每帧都可能重新 attach，淡入动画会加剧闪烁
                        .dontAnimate()
                        // NB 这里刻意不设 placeholder：Glide 的占位图会在 onLoadStarted 里
                        // 被写成 AsyncDrawable 的 result，而 AsyncDrawable 只在
                        // result == 构造期占位 时才再次 load()，一旦被替换，后续 chunk
                        // 重新 attach 就不会再触发加载，图片会永远停在占位图上。
                        // 占位统一由 GlideImagesPlugin 内部的 PlaceholderDrawable 负责。
//                        .error(R.drawable.ic_image_error)
                        ;
            }

            @Override
            public void cancel(@NonNull Target<?> target) {
                requestManager.clear(target);
            }
        });
    }

    /**
     * 图片方案 B：markwon-image 自带的异步加载器，不依赖任何图片框架。
     * classpath 中存在 androidsvg / android-gif-drawable 时会自动启用 SVG、GIF 解码。
     */
    @NonNull
    private ImagesPlugin createImagesPlugin() {
        return ImagesPlugin.create(plugin -> {
            // data:image/png;base64,... 内联图片
            plugin.addSchemeHandler(DataUriSchemeHandler.create());
            // file:// 本地文件（读取外部存储需要 READ_EXTERNAL_STORAGE 权限）
            plugin.addSchemeHandler(FileSchemeHandler.create());
            // http / https，可换成 OkHttpNetworkSchemeHandler.create(okHttpClient)
            plugin.addSchemeHandler(NetworkSchemeHandler.create());

            plugin.placeholderProvider(drawable ->
                    ContextCompat.getDrawable(this, R.drawable.ic_image_placeholder));

            plugin.errorHandler((url, throwable) -> {
                Log.w(TAG, "图片加载失败: " + url, throwable);
                return ContextCompat.getDrawable(this, R.drawable.ic_image_error);
            });
        });
    }

    /**
     * 读取 {@code res/raw} 下的文本内容（UTF-8）。
     */
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
            return getString(R.string.markdown_load_failed);
        } finally {
            closeQuietly(inputStream);
        }
    }

    private static void closeQuietly(@Nullable InputStream inputStream) {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException ignored) {
                // no-op
            }
        }
    }
}

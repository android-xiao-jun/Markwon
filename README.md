# markwon-ext

> 基于 [`Markwon`](https://github.com/noties/Markwon) 4.6.2 的 Android 二次封装。
> 在完整保留原库能力（CommonMark / GFM 表格 / 任务列表 / HTML / 图片 / 公式 / 代码高亮）的前提下，做了三件事：
>
> 1. **新增流式增量渲染** `Markwon#appendMarkdown` —— 面向 SSE / LLM token 流，逐块追加时只重解析「未稳定的尾部」，
>    输出与整段 `toMarkdown` **字节级一致**；
> 2. **把装饰能力收敛成可配置项** —— 代码块横向滚动、语言栏、复制按钮、行内/代码块圆角、表格圆角与表格滚动条等，
>    统一走 `MarkwonTheme`（以及 `CodeBlockCopyTheme` / `TableTheme`），且**一律 opt-in**：不配置就没有这个属性；
> 3. **提供一份框架默认值的显式快照** `DefaultTheme` —— Markwon 的默认样式
> 4. 修复`Table`展示异常，以及增加`Table`可以横向滚动（整块区域拖拽 + 与代码块同样式的底部滚动条，见 [3.7](#37-表格横向滚动拖拽--底部滚动条)）

---

## 一、目录结构

### 1.1 模块清单

| 模块 | artifactId | 产物 / 关键类 | 说明 |
| --- | --- | --- | --- |
| `markwon-core` | `core` | `Markwon` `CorePlugin` `MarkwonTheme` `MarkwonAppendState` `CodeBlockScrollPlugin` | 核心：解析、渲染、主题、流式 API |
| `markwon-inline-parser` | `inline-parser` | `MarkwonInlineParserPlugin` | 可定制内联解析（反斜杠转义等） |
| `markwon-html` | `html` | `HtmlPlugin` | HTML 标签（`u/b/i/sub/sup/strike/blockquote/h1-h6/img/a`…） |
| `markwon-linkify` | `linkify` | `LinkifyPlugin` | 裸 URL / 邮箱 / 手机号自动成链 |
| `markwon-simple-ext` | `simple-ext` | `SimpleExtPlugin` | 基于自定义分隔符的扩展（`==高亮==`、`++下划线++`） |
| `markwon-syntax-highlight` | `syntax-highlight` | `SyntaxHighlightPlugin` `Prism4jThemeDefault` | Prism4j 代码高亮 |
| `markwon-ext-strikethrough` | `ext-strikethrough` | `StrikethroughPlugin` | GFM `~~删除线~~` |
| `markwon-ext-tables` | `ext-tables` | `TablePlugin` `TableTheme` | GFM 表格 |
| `markwon-ext-tasklist` | `ext-tasklist` | `TaskListPlugin` | GFM 任务列表 `- [x]` |
| `markwon-ext-latex` | `ext-latex` | `JLatexMathPlugin` | LaTeX 公式（块级 / 行内） |
| `markwon-image` | `image` | `ImagesPlugin` | 自带异步加载器（data-uri / file / http，可选 SVG / GIF） |
| `markwon-image-glide` | `image-glide` | `GlideImagesPlugin` | 基于 Glide 的图片加载 |
| `markwon-recycler` | `recycler` | `MarkwonAdapter` | 用 RecyclerView 渲染 Markdown（**不是插件**） |
| `markwon-recycler-table` | `recycler-table` | `MarkwonAdapter.Entry` | 把表格渲染成原生 `TableLayout` |
| `markwon-editor` | `editor` | 编辑器视图 | Markdown 编辑（**不是插件**） |
| `markwon-ext-view` | `ext-view` | — | 空壳，暂无源码 |
| `app-sample` | — | `MainActivity` `DefaultTheme` | 案例工程，演示全部插件 |

> `recycler` / `recycler-table` / `editor` **不是** `MarkwonPlugin`，不通过 `usePlugin` 注册。

### 1.2 依赖关系

所有模块都 `api project(':markwon-core')`（即引入扩展 = 自动带上 core）：

```
markwon-core  ──── commonmark (api)
   │
   ├── inline-parser          (api)
   │      └── ext-latex       (api inline-parser)
   ├── html                   (compileOnly commonmark-ext-gfm-strikethrough)
   ├── linkify
   ├── simple-ext
   ├── syntax-highlight       (api prism4j)
   ├── image                  (compileOnly androidsvg / android-gif-drawable / okhttp)
   ├── image-glide
   ├── editor
   ├── recycler               (api androidx.recyclerview)
   │      └── recycler-table  (api recycler + ext-tables)
   ├── ext-strikethrough      (api commonmark-ext-gfm-strikethrough)
   ├── ext-tables             (api commonmark-ext-gfm-tables)
   └── ext-tasklist
```

`markwon-image` 的 `androidsvg` / `android-gif-drawable` / `okhttp` 是 `compileOnly`：**需要哪个能力，由使用方显式引入对应依赖**，
放在 classpath 上就会被自动启用（SVG、GIF 解码器按 classpath 探测）。

---

## 二、插件加载流程

### 2.1 构建期：`Markwon.builder(context).usePlugin(...).build()`

```
Markwon.builder(ctx)                     → new MarkwonBuilderImpl(ctx).usePlugin(CorePlugin.create())
Markwon.builderNoCore(ctx)               → 不预置 CorePlugin
        │
   .usePlugin(p1).usePlugin(p2) ...      → plugins 按注册顺序进 List（CorePlugin 永远在第 0 位）
        │
     .build()
        ├─ plugins.isEmpty() → IllegalStateException
        ├─ preparePlugins() = new RegistryImpl(plugins).process()
        │     └─ 解析插件间依赖：plugin#configure(Registry) 里 registry.require(XxxPlugin.class)
        │        声明「我依赖 XxxPlugin」→ 拓扑排序 + 去重，得出最终执行顺序
        ├─ 创建 5 个 Builder：
        │     Parser.Builder / MarkwonTheme.Builder(builderWithDefaults) /
        │     MarkwonConfiguration.Builder / MarkwonVisitor.Builder / MarkwonSpansFactory.Builder
        ├─ for (plugin : 最终顺序) {                ← 关键：后注册的覆盖先注册的
        │       configureParser → configureTheme → configureConfiguration
        │       → configureVisitor → configureSpansFactory
        │   }
        └─ new MarkwonImpl(bufferType, textSetter, parser, visitorFactory,
                           configuration, plugins, fallbackToRawInputWhenEmpty)
```

⚠️ **注册顺序即优先级**：`configureTheme` 是累加到同一个 `MarkwonTheme.Builder` 上的，
`SyntaxHighlightPlugin` 会把语法主题的底色 / 文字色写进 `codeBlockBackgroundColor` / `codeBlockTextColor`，
所以自己的主题插件（`DefaultTheme.markwonPlugin`）必须注册在它**之后**。

### 2.2 渲染期：`parse` → `render`

```
markwon.toMarkdown(input) = render(parse(input))

parse(input)
  ├─ for (plugin : plugins) input = plugin.processMarkdown(input)   ← 前置文本处理，链式
  └─ parser.parse(input) → org.commonmark.node.Node (AST)

render(node)
  ├─ for (plugin : plugins) plugin.beforeRender(node)               ← 可改 AST
  ├─ visitor = visitorFactory.create(); node.accept(visitor)        ← 遍历 AST，向 SpannableBuilder 打 span
  ├─ for (plugin : plugins) plugin.afterRender(node, visitor)       ← 清理 / 触发动作
  └─ return visitor.builder().spannableStringBuilder()
```

### 2.3 落盘：`setParsedMarkdown` / `setMarkdown`

```
markwon.setMarkdown(textView, md)      = setParsedMarkdown(textView, toMarkdown(md))

setParsedMarkdown(textView, spanned)
  ├─ for (plugin : plugins) plugin.beforeSetText(textView, spanned)
  │     └─ 例：CodeBlockScrollHelper.injectCopy() 在这里把复制按钮样式推进行 span（必须在测量前）
  ├─ textView.setText(spanned, TextView.BufferType.SPANNABLE)
  │     └─ 若配置了 TextSetter（如 PrecomputedTextSetterCompat）则异步执行，完成后回调 ↓
  └─ for (plugin : plugins) plugin.afterSetText(textView)
        └─ 例：ImagesPlugin 在这里注册 AsyncDrawableSpan 并真正发起图片请求
```

### 2.4 流式：`appendMarkdown(state, chunk)`

```
state.appendSource(chunk); state.scan()
  ├─ for (plugin : plugins) plugin.beforeAppendChunk(state)
  │     └─ 复位跨 chunk 的解析器上下文（HtmlPlugin 恢复 lastSettledPreviousIsBlock / isInsidePreTag）
  ├─ if (state.consumeRebuild())  → 链接引用定义迟到，整段从头重解析重渲染（一次会话最多一次）
  ├─ settle 段：已确定不会再变的前缀复用已有的 settled 内容，不重排
  ├─ for (plugin : plugins) plugin.afterSettle(state)
  │     └─ 在 settle 之后、tail 之前快照状态（必须卡这个时机）
  └─ tail 段：只重解析 [settledEnd, sourceLength)
        ├─ builder = state.settledSeed()          ← 直接渲染进「已播种」的 builder
        │     （渲染进空 builder 会让块首的 ensureNewLine 变 no-op → 丢 \n）
        ├─ setDeferLoading(true)：tail 里的图片只放占位图，不发起请求
        └─ return buildOutput(state, builder)
```

调用方式（两种等价，推荐显式持有 `MarkwonAppendState` / `Spanned`）：

```java
// 方式 A：自带 state（推荐，可与 TextView 解耦）
MarkwonAppendState state = new MarkwonAppendState();
Spanned spanned = new SpannableStringBuilder();
for (String chunk : chunks) {
    spanned = markwon.appendMarkdown(state, chunk);
    markwon.setParsedMarkdown(textView, spanned);
}

// 方式 B：直接对着 TextView 追加
for (String chunk : chunks) {
    markwon.appendMarkdown(textView, chunk);
}
```

---

## 三、使用方式

### 3.1 本地模块导入（`implementation project`）

`app-sample/build.gradle` 就是这么用的：

```gradle
// settings.gradle
include ':markwon-core',
        ':markwon-editor',
        ':markwon-ext-latex',
        ':markwon-ext-strikethrough',
        ':markwon-ext-tables',
        ':markwon-ext-tasklist',
        ':markwon-html',
        ':markwon-image',
        ':markwon-image-glide',
        ':markwon-inline-parser',
        ':markwon-linkify',
        ':markwon-recycler',
        ':markwon-recycler-table',
        ':markwon-simple-ext',
        ':markwon-syntax-highlight',
        ':markwon-ext-view',
        ':app-sample'
```

```gradle
// 宿主模块 build.gradle
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation project(':markwon-core')
    implementation project(':markwon-ext-tables')
    // …其余按需
}
```

⚠️ 本仓库的构建尺寸以根 `build.gradle` 的 `ext.config` 为准：`compileSdk 29` / `minSdk 16` / `buildTools 29.0.3`，
AGP 4.0.2 + Gradle 6.1.1，**必须用 JDK 11 构建**（JDK 17 起旧 AGP 会失败）。

### 3.2 远程依赖（JitPack）

仓库已带 `jitpack.yml`（`openjdk11`），直接用 JitPack 私有坐标：

```gradle
// 根 build.gradle 的 allprojects.repositories
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    // 核心，必选
    implementation 'com.github.android-xiao-jun.markwon-ext:core:4.6.2'

    // 按需引入（每个扩展都会自动带上 core）
    implementation 'com.github.android-xiao-jun.markwon-ext:ext-tables:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:ext-tasklist:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:ext-strikethrough:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:ext-latex:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:html:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:inline-parser:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:linkify:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:simple-ext:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:image:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:image-glide:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:syntax-highlight:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:recycler:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:recycler-table:4.6.2'
    implementation 'com.github.android-xiao-jun.markwon-ext:editor:4.6.2'
}
```

- `4.6.2` 换成实际 tag；**若还没打 tag**，可用分支快照 `master-SNAPSHOT`（JitPack 按 master 最新 commit 构建）。
- markwon 自身的发布坐标是 `com.github.android-xiao-jun.markwon-ext`，版本号写在 `gradle/maven-publish.gradle`。
- 需要 `PrecomputedTextSetterCompat` 时，使用方要**显式**引入 `androidx.core` / `androidx.appcompat`（库内是 `compileOnly`）。
- 可选运行时依赖（按能力选）：`com.caverock:androidsvg`（SVG）、`pl.droidsonroids.gif:android-gif-drawable:1.2.15`（GIF，
  与库的 `minSdk 16` 对齐）、`com.squareup.okhttp3:okhttp`（图片网络栈）、`io.noties:prism4j`（语法高亮）。

### 3.3 最小可用示例

```java
TextView textView = findViewById(R.id.text_view);

Markwon markwon = Markwon.builder(this)
        .usePlugin(TablePlugin.create(TableTheme.buildWithDefaults(this)))
        .build();

markwon.setMarkdown(textView, "# Hello\n\n| a | b |\n| --- | --- |\n| 1 | 2 |");
```

`Markwon.create(context)` 是更省的写法，等价于「只注册 `CorePlugin`」。`Markwon.builder(context)` 会自动把 `CorePlugin` 放在首位。

### 3.4 全插件装配（app-sample 案例）

`app-sample/src/main/java/io/noties/markwon/sample/MainActivity.java` 的 `createMarkwon()` 是完整的装配示例，
样式全部从 `DefaultTheme` 的工厂方法取：

```java
final Markwon.Builder builder = Markwon.builder(this);

// 图片：GlideImagesPlugin 与 ImagesPlugin 二选一（都写 asyncDrawableLoader，后注册覆盖先注册）
builder.usePlugin(createGlideImagesPlugin(Glide.with(getApplicationContext())));

builder
        // ---- 解析能力 ----
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(HtmlPlugin.create())
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(DefaultTheme.taskListPlugin(this))
        .usePlugin(TablePlugin.create(DefaultTheme.tableTheme(this)))
        // ---- 代码块横向滚动 + 语言栏 + 复制按钮（样式/行为都挂在这个插件上）----
        .usePlugin(DefaultTheme.codeBlockScrollPlugin(this)
                .onCodeBlockCopy((textView, code) -> {
                    // 库只把「哪个代码块被点了」+ 原文交出来，剪贴板由宿主自己写
                    final ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (manager != null) {
                        manager.setPrimaryClip(ClipData.newPlainText(null, code));
                    }
                }))
        // ---- 示例扩展与公式 ----
        .usePlugin(DefaultTheme.simpleExtPlugin())
        .usePlugin(DefaultTheme.latexPlugin(this))
        // ---- 代码高亮：语法定义见 SampleGrammarLocator，未命中的语言回退 java ----
        .usePlugin(SyntaxHighlightPlugin.create(
                new Prism4j(new SampleGrammarLocator()),
                DefaultTheme.syntaxTheme(),
                "java"))
        // ---- 主题必须最后注册：后注册覆盖先注册，而 SyntaxHighlightPlugin 会写代码块底色/文字色 ----
        .usePlugin(DefaultTheme.markwonPlugin(this));

return builder.build();
```

案例工程三个按钮分别演示：**图片案例**（`res/raw/case_image.txt`，长文档 + 图片 / HTML / `<details>`）、
**全插件案例**（`res/raw/case_all_plugins.txt`，逐个插件走一遍）、**流式 SSE 案例**（`res/raw/case_3.txt`，
按 token 粒度追加，收尾时与整段解析做字节级对比并打印 `identical=true/false`）。

### 3.5 流式（SSE / LLM）用法

```java
private final MarkwonAppendState state = new MarkwonAppendState();
private Spanned spanned = new SpannableStringBuilder();

// 每收到一个 token / 一个 chunk：
spanned = markwon.appendMarkdown(state, chunk);
markwon.setParsedMarkdown(textView, spanned);

// 流结束：做一次全量渲染收尾 —— 此时 tail 里被延后加载的图片会真正发起请求
markwon.setParsedMarkdown(textView, markwon.toMarkdown(fullSource));
```

要点：

- `MarkwonAppendState` 是**一次流式会话**的状态载体，一次会话一个实例，**不要跨会话复用**；
  用 `appendMarkdown(Spanned old, String)` 时状态由框架通过 `AppendStateSpan` 挂在 `Spanned` 上。
- 必须**串行调用**：上一条 chunk 的 `setParsedMarkdown` 完成后再投递下一条
  （配置了 `TextSetter` 时 `setText` 是异步的，`appendMarkdown(textView, chunk)` 的重载要求这一点）。
- 增量结果与 `markwon.toMarkdown(全量源码)` 的输出**字节级一致**，可像案例那样 `identical` 校验。
- 解析仍在**主线程**完成（`appendMarkdown` 是同步 API）；需要丢到子线程时，由调用方自行切线程后再回主线程 `setParsedMarkdown`。

### 3.6 主题定制

Markwon 的默认样式不在一处，而是靠 `0` / `-1` / `null` 这类哨兵值在**三个对象**里运行时回退出来的，
只看字段声明看不出真实默认值。因此本仓库提供了一个**显式快照**入口：

`app-sample/src/main/java/io/noties/markwon/sample/DefaultTheme.java`

- 上半部分是「框架默认值 + 案例选择值」的常量表，每个都标了来源与 `0 = 不存在` 的语义；
- 下半部分是工厂方法：`markwonPlugin(ctx)` / `tableTheme(ctx)` / `syntaxTheme()` /
  `codeBlockScrollPlugin(ctx)` / `codeBlockCopyTheme(ctx)` / `latexPlugin(ctx)` / `taskListPlugin(ctx)` /
  `simpleExtPlugin()`。

三条使用规则：

1. **装饰属性一律 opt-in**：`0`（或 `null` / `false`）= 「这个属性不存在」，不会回落任何写死常量。
   滚动条要「高度 > 0 **且** 轨道/滑块至少有一个颜色」才存在；语言栏要 `codeBlockHeaderHeight > 0`（且只在代码块可滚动时有这一行）；
   复制按钮要 `codeBlockCopyTheme != null && enabled`。
2. **复制按钮是 `CodeBlockScrollPlugin` 的私有能力**，样式配在 **插件**上（`codeBlockScrollPlugin.codeBlockCopyTheme(theme)`），
   `MarkwonTheme` 里**没有**任何 copy API。按钮依附 header 行 → **只在代码块可滚动（`codeBlockScrollable = true`）时才存在**。
   库不写剪贴板，必须自己接 `onCodeBlockCopy` 才能真的复制；没接 listener 时点击是 no-op，也不会闪「已复制」。
3. **正文字色不归 Markwon 管**：正文颜色由承载它的 `TextView`（`textColor`）决定，`MarkwonTheme` 里没有 `textColor`。
   链接色默认取 `TextView` 的 `textColorLink`。

### 3.7 表格横向滚动（拖拽 + 底部滚动条）

```java
Markwon.builder(this)
        .usePlugin(TablePlugin.create(TableTheme.buildWithDefaults(this)
                .tableScrollEnabled(true)          // 开关：默认 false（宽度约束在视口内、列等宽）
                .tableScrollbarHeight(dp(this, 14))  // 滚动条 opt-in，见下
                .tableScrollbarThumbColor(0xFFCACACA)
                .build()))
        .build();
```

打开 `tableScrollEnabled(true)` 之后：

- **列宽按内容算**：先给每列一个 `视口/4` 的底宽，再按各列最宽单元格撑开，上限是 `tableMaxColumnWidth`
  （默认 200dp）；总宽 ≥ 视口时把列拉满到视口，超出视口就可以横向滚动。
- **整块区域拖拽都能滚**：判定用的是表格占据的**行区间**（`getLineTop` / `getLineBottom`），
  而不是手指底下那个字符 —— 于是单元格、边框、滚动条、以及短行右侧的空白都能起手。
  手势的判定与代码块一致：横向位移超过 `touchSlop` 且大于纵向位移才算拖动，
  纵向手势原样交回外层 `ScrollView`。
- **不需要宿主再设 `TableAwareMovementMethod`**：拖拽由 `TablePlugin` 自己装的
  `OnTouchListener` 负责（经过 `GestureRouter` 分发，见「注意事项」），
  平板/普通 `TextView` 都能用 —— `MovementMethod` 只在 `TextView` 自己消费 `ACTION_DOWN`
  时才会被调到，而普通 `TextView` 并不消费。
- **单元格内的链接不受影响**：只有可滚动的表格才会消费 `ACTION_DOWN`；若手势最后只是一次
  **单击**（没拖动），`DOWN` 会被回放给宿主设的 `MovementMethod`，`ClickableSpan` 照常点到。
- **底部滚动条**：与代码块底部那条**同一套样式**（同一组常量，见 `DefaultTheme` 第五节「横向滚动条」）——
  圆头细条，先铺轨道再压滑块，滑块长度按「视口 / 内容」比例、位置取滚动比例。
  它画在**内容位移之外**，并且**固定在可视区**的底部与左右两侧，不随内容滑动。
  ⚠️ 与代码块不同，它**不占行高**：代码块能把这条压在自己的页脚行里，表格没有页脚行可给 ——
  一旦预留，最后一行就会比上面所有行都高。所以表格的滚动条是**覆盖**在卡片底边框内侧的
  （占用的是单元格本来就留出的底部 padding），每一行的高度完全由内容决定。
  与代码块同规则，它也是 **opt-in**：`tableScrollbarHeight > 0` **且** 轨道/滑块至少有一个颜色才存在，
  否则不画（表格照样能拖，只是看不到当前位置）。
- **卡片外框（可视区四条边）**：表格比视口宽时，内容自己的左右边线会随内容滚出屏幕，
  于是只在**可视区**的四条边上补一圈「卡片外框」（左/右边线由每一行各画一段，顶线归首行、底线归末行），
  这样拖动过程中**左右两侧都不会缺边**。不做这一步的话，表格滚到中间时两侧都没有竖线。
  外框与内容自己的边线在 `scrollX == 0` 时**完全重合**，所以静止时看不出多了一层。
- **圆角跟着「可视区」走，不是跟着内容走**：圆角行的**背景**铺在 `scrollX … scrollX + frameWidth`
  （即屏幕上可见的那一段），半径按 `min(cornerRadius, min(可视宽度, 行高) / 2)` 收敛 ——
  于是角自然落在卡片边缘，超出视口的部分和内容一起被 viewport clip 裁掉。
  超宽时内容自己的圆角弧、以及表格的顶/底边框都交给外框画（内容那份会平移，拖动时会横穿圆角）。
  ⚠️ 这里**不用 `clipPath`**：裁剪边缘没有抗锯齿（4dp 圆角会毛边），且 `clipPath` 在 API 18 以下不可用，
  而本库 `minSdk 16`。改为「把背景画成圆角路径 + 弧线描边」，边缘是抗锯齿的。

---

## 四、注意事项

- **注册顺序**：`configureTheme` 后注册覆盖先注册。`SyntaxHighlightPlugin` 会写
  `codeBlockBackgroundColor` / `codeBlockTextColor`，自定义主题插件要注册在它之后。
- **图片加载器二选一**：`GlideImagesPlugin` 与 `ImagesPlugin` 都向 `MarkwonConfiguration` 注册 `asyncDrawableLoader`，
  同时注册后一个会覆盖前一个。
- **`minSdk 16` + Glide / jlatexmath**：方法数会超过 65536 且低于 21，需要 `multiDexEnabled true` +
  `MultiDexApplication`（见 `app-sample` 的 `SampleApp`）。
- **`Prism4jThemeBase` 不能跨包继承出调色板**：`ColorHashMap.add` 是 `protected`，
  改 token 颜色要么在 `io.noties.markwon.syntax` 同包加类，要么直接实现 `Prism4jTheme` 接口。
- **语法高亮需要语法定义**：官方做法是 `prism4j-bundler` 注解处理器生成全量语法；
  案例工程为免引入 annotationProcessor 流程，用 `SampleGrammarLocator` 手写了 java / kotlin / groovy / json / xml 五种。
- **流式下的代码块滚动 / 图片**：tail（未稳定区域）里的图片只放占位图不发起请求，
  块落进 settled 区域（或流结束时的全量渲染）才真正加载。
- **一个 `TextView` 只有一个 `OnTouchListener`**：代码块拖拽（`CodeBlockScrollPlugin`）与表格拖拽
  （`TablePlugin`）都要接手势，后装的会静默顶掉先装的。因此两者都注册到
  `io.noties.markwon.core.scroll.GestureRouter`：它是真正被装上去的那一个，
  把手势发给**第一个消费 `ACTION_DOWN`** 的注册方（注册顺序 = 优先级），
  没人消费则照旧走 `TextView` 自己的处理（链接、选中、父容器滚动）。
  自己写插件要接手势时，用 `GestureRouter.attach(textView).add(key, listener)`，不要直接 `setOnTouchListener`。

---

## 五、案例工程

```
app-sample/
├── src/main/java/io/noties/markwon/sample/
│   ├── MainActivity.java          # 装配 Markwon + 三个案例（图片 / 全插件 / 流式SSE）
│   ├── DefaultTheme.java          # 主题唯一入口：框架默认值快照 + 工厂
│   ├── SampleGrammarLocator.java  # 手写代码高亮语法定义
│   └── SampleApp.java             # MultiDexApplication
└── src/main/res/
    ├── layout/activity_main.xml
    ├── values/strings.xml colors.xml styles.xml
    └── raw/
        ├── case_image.txt         # 图片案例
        ├── case_all_plugins.txt   # 全插件案例
        └── case_3.txt             # 流式 SSE 案例
```

## License

Apache License 2.0 —— 见 [LICENSE](LICENSE)。上游版权归 [noties/Markwon](https://github.com/noties/Markwon) 所有。

---

## 默认样式示例

![默认样式示例](默认样式示例图.jpg)

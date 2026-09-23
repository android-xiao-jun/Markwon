# markwon-block（块级渲染）

把 Markdown **按 Block 拆成独立 View** 渲染的模块 —— 文本 / 代码 / 图片 / 分隔线各有专责视图，
流式追加（`appendMarkdown`）时只对尾部做差分刷新、前缀块整体复用，是 SSE / LLM 打字机场景的推荐渲染形态。

与 `markwon-core`（单 `TextView` + Spannable）的本质区别：

| | `markwon-core` | `markwon-block`（本模块） |
| --- | --- | --- |
| 渲染形态 | 一个 `TextView`，全文档一段 Spannable | 每个 Block 一个 View（文本/代码/图片/分隔线） |
| 流式追加 | 整段 `setText` 重建 Spannable + StaticLayout | 前缀块复用，仅新尾部参与重排 |
| 代码块 | span 内联样式 | 独立 `CodeBlockView`（复制按钮 / 横向滚动成套） |
| 图片 | `ImageSpan` 内联 | 独立 `ImageBlockView`，占位/加载/失败各自处理 |
| 适用 | 静态文档、长文展示 | 聊天流式、AI 回复、需要单块操作的场景 |

---

## 1. 模块组件

| 组件 | 路径 | 职责 |
| --- | --- | --- |
| `MarkdownTextBlockView` | `io.noties.markwon.block.view` | 唯一对外可见的 View：`renderMarkdown` 整段 / `appendMarkdown` 增量 |
| `MdTheme` | `io.noties.markwon.block.render` | 渲染主题（正文/链接/代码块/表格/光标颜色等），内置 `light()` / `dark()` / `subscribed()` |
| `MarkdownConfig` | `io.noties.markwon.block.render` | 渲染开关（代码块描边 / header 背景 / 表格表头 / 深度思考区 / 订阅色等） |
| `MarkwonFactory` | `io.noties.markwon.block.render` | 自定义解析管线（返回 `null` 走内置：core 全能力 + 表格 + 块间距） |
| `BlockViewFactory` | `io.noties.markwon.block.view` | 自定义「块 → View」映射，接管代码/图片/分隔线视图 |
| `MarkdownRenderer` | `io.noties.markwon.block.render` | 内部引擎：parse → 块视图树 → 差分复用 |
| `MarkdownBlockAssembler` | `io.noties.markwon.block.render` | `appendMarkdown` 增量装配器（公共前缀复用 / 新后缀追加） |

块类型视图：`AbsBlockView`（文本基底）/ `CodeBlockView` / `ImageBlockView` / `SeparatorBlockView`，
容器视图回收由 `ViewRecycler` 负责。

---

## 2. 代码引入

```gradle
repositories { maven { url 'https://jitpack.io' } }

dependencies {
    // 自动带上 core + ext-tables
    implementation 'com.github.android-xiao-jun.markwon-ext:block:b7730ffa9f'
}
```

仓库内调试用项目依赖：

```gradle
implementation project(':markwon-block')
```

---

## 3. 快速使用

### 3.1 布局引入

```xml
<io.noties.markwon.block.view.MarkdownTextBlockView
    android:id="@+id/ai_markdown_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

### 3.2 整段渲染（静态内容）

```kotlin
val markdownView = findViewById<MarkdownTextBlockView>(R.id.ai_markdown_view)

markdownView.renderMarkdown("# Hello\n\n本文支持 **加粗**、`行内代码`、表格、```代码块```") { url ->
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
```

### 3.3 流式增量（SSE 打字机，核心能力）

```kotlin
// SSE 每个 chunk 到达后调用一次；streaming=true 让新字符淡入 + 光标保持
markdownView.appendMarkdown(chunkText, linkClickListener, true)

// 流式结束后如需回到整段形态：再 renderMarkdown(fullText, linkClickListener)
```

增量只对尾部差分：已渲染的文本块复用同一个 View，仅新增字符参与重排，避免全量 `setText`。

### 3.4 链接点击

`OnLinkClick` 是 SAM 接口（`void onClick(@NonNull String url)`），Java / Kotlin 均可 lambda 实现：

```java
markdownView.renderMarkdown(content, url -> { /* 打开链接 / 拦截 */ });
```

### 3.5 图片加载（markwon-image 默认，可切 markwon-image-glide）

`ImageBlockView` 复用 markwon 原框架的异步加载管线（`AsyncDrawable` + `AsyncDrawableLoader`）：

- **未设置 loader**：渲染为可点击占位块（🖼 + alt + url），点击回调业务方；
- **默认加载**（markwon-image）：`AsyncDrawableLoaderBuilder` 内置 data-uri / network scheme handler
  与 SVG / GIF / DefaultMediaDecoder，开箱即用（HTTP 图片需自备 okhttp 网络栈，见依赖说明）：

```java
// Application 里设一次即可全局生效（独立行图片 ImageBlockView 与行内图片共用此 loader）
ImageBlockView.setDefaultAsyncDrawableLoader(ImageBlockView.defaultMarkwonLoader());
```

- **动态切换 markwon-image-glide**：把 `GlideImagesPlugin` 装配进 Markwon 后，取出 loader 传入
  （无需改块视图，运行时替换 loader 即切换）：

```java
Markwon markwon = Markwon.builder(context)
        .usePlugin(GlideImagesPlugin.create(Glide.with(context)))
        .build();
ImageBlockView.setDefaultAsyncDrawableLoader(
        markwon.configuration().asyncDrawableLoader());
```

- 加载中显示圆角灰底（`getResources().getDisplayMetrics().density` 自适应），完成后替换为图片并按容器宽等比伸缩；
  失败时保持灰底（可配 `ImagesPlugin.Builder.placeholderProvider / errorHandler` 加载占位与失败样式）。
- **行内图片**：同一 loader 下，行内 `![](url)` 也会生成 `AsyncDrawableSpan` 真正加载
  （由 `AsyncDrawableScheduler` 在 setText 后 attach 触发，加载完成后自动收敛行高）；
  未注册 loader 时行内图片回退灰底占位块。

### 3.6 外部交互回调（复制 / 图片点击）

除链接外，还有两个交互点可被外部接管，均支持「全局默认 + 实例覆盖」两级注册：

| 回调 | 注册方式 | 语义 |
| --- | --- | --- |
| 代码块复制 `CodeBlockCopyListener` | `CodeBlockView.setDefaultCopyClickListener(...)` / `setOnCopyClickListener(...)` | 返回 `true`：外部处理复制，内部只把按钮切「已复制」+ 定时复位；返回 `false`：拦截本次点击（不复制、不改状态）；**未设置**：本地 `LinkHandler.copyToClipboard` + 按钮状态 |
| 图片占位点击 `OnImageClick` | `ImageBlockView.setDefaultOnImageClick(...)` / `setOnImageClick(...)` | `void onClick(url, alt)`；未设置时占位块点击无动作 |

```java
// 全局默认（Application 里设一次；RecyclerView 复用场景建议走这里）
CodeBlockView.setDefaultCopyClickListener((code, language) -> {
    if (blocked) return false;            // 拦截
    myCopy(code, language);               // 外部接管复制
    return true;                          // 内部只更新按钮状态
});
ImageBlockView.setDefaultOnImageClick((url, alt) -> openViewer(url, alt));
```

> 提示：`MarkdownTextBlockView` 是容器，实例级 setter 需要在拿到底层视图后调用
> （自定义 `BlockViewFactory` 创建 `CodeBlockView` / `ImageBlockView` 时顺手设置）；
> 不依赖底层视图时直接用全局默认即可。

---

## 4. 主题样式配置

### 4.1 全局默认（推荐：Application 里设一次）

```kotlin
class App : Application() {
    override fun onCreate() {
        MarkdownTextBlockView.setDefaultTheme(MdTheme.light())      // 或 dark() / subscribed()
        MarkdownTextBlockView.setDefaultConfig(MarkdownConfig.Builder().build())
        // MarkwonFactory 默认 null = 内置管线；需要自定义解析时：
        // MarkdownTextBlockView.setDefaultMarkwonFactory { context, theme, config -> ... }
    }
}
```

### 4.2 单实例覆盖

```kotlin
markdownView.setTheme(MdTheme.dark())
markdownView.setConfig(MarkdownConfig.Builder()
    .codeBlockHeaderBackground(false)   // 关闭代码块语言栏背景
    .build())
markdownView.setMarkdownFactory { context, theme, config -> /* 自定义 Markwon */ }
```

### 4.3 `MdTheme.light()` 默认值（一眼看懂观感）

| 项 | 默认值 | 说明 |
| --- | --- | --- |
| 正文文字 | `#1F2329` | 近黑 |
| 次要文字 | `#646A73` | 列表符号 / 图片占位文案 |
| 链接 | `#3370FF` | 品牌蓝 |
| 代码背景 / header / 描边 | `#F2F3F5` / `#E8EAED` / `#D9DDE3` | 代码块三段灰 |
| 表格表头 / 边框 | `#E9EFFB` / `#D9DDE3` | |
| 打字机光标 | `#3370FF` | |
| 行内代码文字 | `#C7384A` | |
| 淡入（alphaFade） | `true` | 流式新字符渐显 |

完整显式快照见 `app-sample` 的 [DefaultTheme 第九节](../app-sample/README.md)（chat-demo 同款观感，改默认值只看那一处）。

### 4.4 `MarkdownConfig` 主要开关

| 项 | 默认 | 说明 |
| --- | --- | --- |
| `streaming` | 由 API 控制 | `appendMarkdown(..., true)` 时为 true（打字机状态） |
| `codeStyle` / `codeBlockStroke` / `codeBlockHeaderBackground` | true | 代码块描边与语言栏 |
| `tableHeader` | true | 表格表头强调 |
| `failOrInterrupt` | false | 失败/打断态标记 |
| `deepThinkArea` / `deepResearchArea` / `subscribedColor` | false | 豆包订阅主题相关，默认关 |

---

## 5. 与 `appendMarkdown`（markwon-core 流式 API）的关系

`appendMarkdown` 在 core 中是对单 `TextView` 的整段增量重建（`setText` 触发 StaticLayout 全量重排）；
`markwon-block` 的 `appendMarkdown` 是**块级差分** —— 被追加文本经过 `MarkdownBlockAssembler` 后，
公共前缀对应的 View 原样复用，只有新增后缀触发新块（或当前块）的渲染，重排范围大幅收敛。

一条 AI 消息的典型用法：

```kotlin
// 解码工具调用/思考用独立区域；正文 Markdown 交给本 View
markdownView.renderMarkdown(fullContent) { url -> openUrl(url) }   // 首次（或回看历史）
markdownView.appendMarkdown(delta)     // 流式中：每个 chunk

// 追加过程中请保持 streaming=true 开启，否则不会有打字机视觉
```

---

## 6. 案例

- [chat-demo](../chat-demo/README.md)：AI 聊天 App，`MarkdownTextBlockView` 分块渲染 + `appendMarkdown` 打字机，完整工程可运行。
- [app-sample](../app-sample/README.md)：`DefaultTheme` 第九节为 `MdTheme` / `MarkdownConfig` 的显式快照（含 `applyBlockDefaults()` 一键注入）。
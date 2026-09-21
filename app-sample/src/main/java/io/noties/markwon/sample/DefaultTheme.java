package io.noties.markwon.sample;

import android.content.Context;
import android.text.style.BackgroundColorSpan;
import android.text.style.UnderlineSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.core.CodeBlockCopyTheme;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.core.scroll.CodeBlockScrollPlugin;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.ext.tables.TableTheme;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.simple.ext.SimpleExtPlugin;
import io.noties.markwon.syntax.Prism4jTheme;
import io.noties.markwon.syntax.Prism4jThemeDefault;
import io.noties.markwon.utils.Dip;

/**
 * Markwon <b>框架默认值</b>的「显式快照 + 唯一修改入口」。
 *
 * <p>这个类<b>不改变任何外观</b> —— 下面每个值都等于「什么都不配置」时框架自己用的默认值。
 * 它存在的意义是：Markwon 的默认样式不在一处，而是靠一串哨兵值（{@code 0} / {@code -1} /
 * {@code null}）分散在三个对象里运行时回退出来的，想改一个配色得翻源码猜。这里把它们
 * <b>抄出来、显式写回</b>，于是「看默认值」和「改默认值」都只在这一个文件里完成。
 *
 * <h3>默认值从哪来</h3>
 * <table border="1">
 *     <tr><th>对象</th><th>由谁创建</th><th>里面有默认值的东西</th></tr>
 *     <tr><td>{@link MarkwonTheme}</td>
 *         <td>{@code MarkwonBuilderImpl} → {@code MarkwonTheme.builderWithDefaults(context)}</td>
 *         <td>默认值分散在 <b>3 层</b>：① Builder 字段字面量（{@code 0 / -1 / null / false}）；
 *             ② {@code builderWithDefaults} 补的 <b>6 个 dp 尺寸</b>；
 *             ③ 构造函数与 getter 里的兜底（{@code resolvePx} 的 dp 默认、{@code != 0} 的色值默认、
 *             {@code 文字色 × alpha} 的动态色、{@code null} 的字体兜底）。
 *             <b>光看 MarkwonTheme 的字段声明看不出真实默认值</b> —— 第 ③ 层要读构造函数。</td></tr>
 *     <tr><td>{@link TableTheme}</td>
 *         <td>{@code TableTheme.buildWithDefaults(context)}</td>
 *         <td>3 个尺寸（cell padding 4dp / border 1dp / 列宽上限 200dp），颜色全是动态的</td></tr>
 *     <tr><td>{@link Prism4jThemeDefault}</td>
 *         <td>自己 new（{@code #syntaxTheme()}）</td>
 *         <td>代码块底色 {@code #F5F2F0}、文字色 {@code #DD000000}、8 组 token 颜色</td></tr>
 * </table>
 *
 * <h3>⚠️ 一、动态默认值（抄不出来，故意留空）</h3>
 * 下面这些默认值是「当前<b>文字色</b> × 某个 alpha」在运行时算出来的，没有常量可写，
 * 所以本类<b>不设置</b>它们（= 保持框架默认）。想改成固定色，取消注释填值即可：
 * <table border="1">
 *     <tr><th>配置项</th><th>默认</th><th>alpha 常量</th></tr>
 *     <tr><td>引用竖条 {@code blockQuoteColor}</td><td>文字色 × 25%</td><td>{@code BLOCK_QUOTE_DEF_COLOR_ALPHA = 25}</td></tr>
 *     <tr><td>行内代码底 {@code codeBackgroundColor}</td><td>文字色 × 25%</td><td>{@code CODE_DEF_BACKGROUND_COLOR_ALPHA = 25}</td></tr>
 *     <tr><td>代码块底 {@code codeBlockBackgroundColor}</td><td>跟随行内代码底</td><td>—</td></tr>
 *     <tr><td>标题下划线 {@code headingBreakColor}</td><td>文字色 × 75%</td><td>{@code HEADING_DEF_BREAK_COLOR_ALPHA = 75}</td></tr>
 *     <tr><td>分隔线 {@code thematicBreakColor}</td><td>文字色 × 25%</td><td>{@code THEMATIC_BREAK_DEF_ALPHA = 25}</td></tr>
 *     <tr><td>列表符号 {@code listItemColor}</td><td>跟随文字色</td><td>—</td></tr>
 *     <tr><td>链接 {@code linkColor}</td><td>TextView 的 {@code textColorLink}</td><td>—</td></tr>
 *     <tr><td>代码文字 {@code codeTextColor}</td><td>跟随文字色</td><td>—</td></tr>
 *     <tr><td>滚动条 {@code codeBlockScrollbarTrackColor} / {@code ThumbColor}</td>
 *         <td>{@code 0} = <b>不绘制</b>（连页脚那一行都不预留）</td><td>—</td></tr>
 *     <tr><td>代码块内缩 {@code codeBlockPadding}</td><td>{@code 0}（不留白）</td><td>—</td></tr>
 *     <tr><td>语言栏高度 {@code codeBlockHeaderHeight}</td>
 *         <td><b>不配置 = 不显示</b>（不预留高度、不画标签）；配置 &gt; 0 时取
 *             {@code max(配置值, 一行文字高)} —— 高度不够展示文字就按文字高度撑开</td><td>—</td></tr>
 *     <tr><td>滚动条高度 {@code codeBlockScrollbarHeight} / 表格 {@code tableScrollbarHeight}</td>
 *         <td>{@code 0}（<b>不显示滚动条</b>）</td><td>—</td></tr>
 *     <tr><td>表格滚动条的两色 {@code tableScrollbarTrackColor} / {@code ThumbColor}</td>
 *         <td>{@code 0} = <b>不绘制</b>（最后一行的滚动条留白也不会撑出来）</td><td>—</td></tr>
 *     <tr><td>表格边框 {@code tableBorderColor}</td><td>文字色 × 75%</td><td>{@code TABLE_BORDER_DEF_ALPHA = 75}</td></tr>
 *     <tr><td>表格奇数行底 {@code tableOddRowBackgroundColor}</td><td><b>{@code #16000000}</b>，见下</td><td>{@code TABLE_ODD_ROW_DEF_ALPHA = 22}</td></tr>
 * </table>
 *
 * <h4>⚠️ 表格奇数行底：注释说的和实际跑的不一样</h4>
 * {@code TableTheme} 的注释写的是「paint.color × 22%」，但 {@code TableRowSpan} 画行背景用的是它<b>自己 new 的
 * {@code Paint}</b>（初始 color = 0），而且那一步<b>没有</b>从行的文本 paint 播种（边框那一步才 {@code paint.set(p)}）。
 * 所以实际默认值是 {@code ColorUtils.applyAlpha(0, 22) = 0x16000000}（纯黑 22/255 ≈ 8.6%），
 * 与「文字色」无关。行为稳定的是<b>边框</b>（有 {@code paint.set(p)}）→ 文字色 × 75%。
 * 想固定斑马纹色，用 {@link #tableTheme(Context)} 里注释掉的那行。
 *
 * <h4>⚠️ 三处常量写不出来的默认</h4>
 * <ul>
 *     <li>{@code codeTypeface} / {@code codeBlockTypeface}：默认 {@code null} → {@code applyCodeTextStyle}
 *         兜底 {@code Typeface.MONOSPACE}；</li>
 *     <li>{@code headingTypeface}：默认 {@code null} → {@code applyHeadingTextStyle} 兜底
 *         {@code paint.setFakeBoldText(true)}（标题伪粗体，这就是「标题加粗」这个开关的实现）；</li>
 *     <li>{@code headingTextSizeMultipliers}：默认 {@code null} → 兜底 {@code HEADING_SIZES}
 *         （值见下面的 {@code HEADING_TEXT_SIZE_MULTIPLIERS}）。</li>
 * </ul>
 *
 * <h3>⚠️ 二、正文字色不归 Markwon 管</h3>
 * Markwon <b>没有</b> {@code textColor} 这个概念 —— 正文颜色完全由承载它的 {@code TextView}
 * 决定（见 {@code res/layout/activity_main.xml}）。所以<b>不要</b>在本类里加 {@code TEXT} 常量，
 * 加了也没有任何消费方，只会变成一个看着像配置实则无效的死常量。
 *
 * <h3>⚠️ 三、注册顺序</h3>
 * {@code MarkwonBuilderImpl#build()} 按注册顺序累加 {@code configureTheme}，<b>后注册的覆盖先注册的</b>。
 * {@code SyntaxHighlightPlugin} 会把语法主题的 {@code background()} / {@code textColor()} 写进
 * {@code codeBlockBackgroundColor} / {@code codeBlockTextColor}，所以 {@link #markwonPlugin(Context)}
 * 必须注册在它<b>之后</b>：
 * <pre>
 * .usePlugin(TablePlugin.create(DefaultTheme.tableTheme(this)))
 * .usePlugin(SyntaxHighlightPlugin.create(prism4j, DefaultTheme.syntaxTheme(), "java"))
 * .usePlugin(DefaultTheme.markwonPlugin(this))        // ← 最后
 * </pre>
 *
 * <h3>⚠️ 四、复制按钮的配置挂在「插件」上，不在 MarkwonTheme 里</h3>
 * 代码块 header 行右侧的「复制」按钮是 {@code CodeBlockScrollPlugin} 自己的能力，所以它的样式
 * （{@link #codeBlockCopyTheme(Context)}）和行为（{@code onCodeBlockCopy}）都配置在<b>那个插件实例</b>上，
 * 见 {@link #codeBlockScrollPlugin(Context)}。<b>不要在 {@link #markwonPlugin(Context)} 里找它</b> ——
 * 它刻意不经过全局 {@code MarkwonTheme}：一个插件的私有功能不该从库的每个角落都能配。
 *
 * <p>它同样只在<b>横向滚动</b>代码块（存在 header 行）时才可能存在。
 *
 * @since 4.6.3
 */
public final class DefaultTheme {

    // =====================================================================
    // 一、MarkwonTheme —— 有字面默认值的尺寸
    //     （来源：MarkwonTheme.builderWithDefaults(Context)，单位 dp）
    // =====================================================================

    /** 代码块左侧留白（{@code LeadingMarginSpan} 的 leading margin）。默认 8dp。 */
    private static final int CODE_BLOCK_MARGIN_DP = 8;

    /** 块间距：引用缩进 / 列表缩进 / 列表符号宽度的计算基准。默认 24dp。 */
    private static final int BLOCK_MARGIN_DP = 24;

    /** 引用左边竖条的宽度。默认 4dp（BlockQuoteSpan 绘制）。 */
    private static final int BLOCK_QUOTE_WIDTH_DP = 4;

    /** 有序列表编号的描边宽度。默认 1dp（OrderedListItemSpan 绘制）。 */
    private static final int BULLET_LIST_ITEM_STROKE_WIDTH_DP = 1;

    /** 标题下方横线的高度。默认 1dp；{@code 0} = 不画，但只有横向滚动代码块才有 header 行，与此无关。 */
    private static final int HEADING_BREAK_HEIGHT_DP = 1;

    /** 分隔线（{@code ---}）的粗细。默认 4dp（ThematicBreakSpan 绘制）。 */
    private static final int THEMATIC_BREAK_HEIGHT_DP = 4;

    // =====================================================================
    // 二、MarkwonTheme —— 4.6.3 新增的装饰尺寸
    //     框架默认全是「不配置」：padding / 滚动条高度 = 0，语言栏高度 = 不配置（不显示）。
    //     下面这些值是 app-sample <b>显式选择</b>的观感值，不是框架默认。
    // =====================================================================

    /**
     * 代码块内容内缩 + 页脚（滚动条行）底部留白。
     * <b>框架默认 0</b>（不配置 = 不留白，代码紧贴卡片边缘）；示例给 12dp。
     */
    private static final int CODE_BLOCK_PADDING_DP = 12;

    /**
     * 代码块语言栏高度。<b>框架默认不配置 = 不显示</b>（那一行不预留高度，语言标签也不画）；
     * 示例显式给 32dp 才会出现。仅<b>横向滚动</b>代码块有这一行。
     *
     * <p>配置成正值后，框架会再取 {@code max(配置值, 一行文字高)} —— 配置得太矮时按文字
     * 高度撑开，标签永远不会被自己的行裁掉。另外<b>没有语言</b>（info string 为空，含缩进
     * 代码块）时这一行同样不占高度，即使配了高度也不会留出一条空白。
     *
     * <p>源码里显式判 {@code 0} 的原因：标签是在一个「零高度」的行里居中的 —— 不判就会
     * 画到代码块外面去（上半截悬空、下半截被下一行背景盖掉）。
     */
    private static final int CODE_BLOCK_HEADER_HEIGHT_DP = 32;

    /**
     * 语言栏的<b>字号与文字色不再单独配置</b>：这两个属性是 {@code codeBlockTextSize} /
     * {@code codeBlockTextColor} 的重复定义，已从 {@code MarkwonTheme} 移除 ——
     * 语言标签现在直接沿用代码块的文字样式（见 {@code applyCodeBlockHeaderStyle}）。
     */

    /** 行内代码左右内缩（背景块内的文字留白）。默认 4dp。 */
    private static final int CODE_HORIZONTAL_PADDING_DP = 0;

    // 代码块底部滚动条的高度 / 两色见第五节 —— 表格用的是同一组常量，两处观感才一致。

    /** 行内代码背景圆角。默认 0（直角）。 */
    private static final int CODE_BACKGROUND_RADIUS = 4;

    /** 代码块背景圆角。默认 0（直角）。 */
    private static final int CODE_BLOCK_BACKGROUND_RADIUS = 4;

    /** 无序列表符号最大宽度。默认 0 = 不限，取 {@code min(blockMargin, 行高) / 2}。 */
    private static final int BULLET_WIDTH = 0;

    /** 行内代码字号。默认 0 = 不指定，按文字号 × 0.87（{@code CODE_DEF_TEXT_SIZE_RATIO}）。 */
    private static final int CODE_TEXT_SIZE = 0;

    /** 代码块字号。默认 0 = 跟随行内代码字号。 */
    private static final int CODE_BLOCK_TEXT_SIZE = 0;

    /**
     * 标题字号倍数，对应 {@code h1..h6}，基准是 TextView 的字号。
     * <p>这是 HTML 规范的默认梯度，Markwon 内部 {@code HEADING_SIZES} 就是这一组
     * （{@code 2 / 1.5 / 1.17 / 1 / .83 / .67}）—— 注意 h5、h6 比正文<b>小</b>。
     */
    private static final float[] HEADING_TEXT_SIZE_MULTIPLIERS =
            {2F, 1.5F, 1.17F, 1F, .83F, .67F};

    // =====================================================================
    // 三、MarkwonTheme —— 有字面默认值的颜色
    // =====================================================================

    /**
     * 横向滚动条轨道颜色 / 滑块颜色见第五节（代码块与表格共用同一组常量）。
     */

    // NB: 语言栏没有独立底色 —— 它本来就是代码块的一部分，露出
    //     `codeBlockBackgroundColor` 即可；`codeBlockHeaderBackgroundColor` 是重复定义，已移除。

    // =====================================================================
    // 四、语法高亮主题（Prism4jThemeDefault）
    // =====================================================================

    /**
     * 代码块底色。{@link Prism4jThemeDefault#create(int)} 的默认值是 {@code #F5F2F0}，
     * 会由 {@code SyntaxHighlightPlugin} 写进 {@code codeBlockBackgroundColor}。
     */
    @ColorInt
    private static final int CODE_BLOCK_BACKGROUND_COLOR = 0;

    /**
     * 代码块文字色。值取自 {@code Prism4jThemeDefault#textColor()} —— 那里<b>写死</b>返回
     * {@code 0xDD000000}，构造参数只能改底色。
     *
     * <p>⚠️ 这<b>不是</b> {@code MarkwonTheme} 的默认值：MarkwonTheme 里 {@code codeBlockTextColor}
     * 的字段默认是 {@code 0}，会依次回退到 {@code codeTextColor} → 不覆盖（= 跟随文字色）。
     * {@code 0xDD000000} 是 {@code SyntaxHighlightPlugin} 把语法主题的 {@code textColor()} 写进去的结果。
     *
     * <p>因为语法主题改不了文字色，这里在 {@link #markwonPlugin(Context)} 里显式覆盖了一遍
     * （该插件注册在 {@code SyntaxHighlightPlugin} 之后 → 以本类的值为准，值与默认相同）。
     * 换句话说：这个常量是把「语法主题里写死的默认值」提升成一个可改的旋钮。
     */
    @ColorInt
    private static final int CODE_BLOCK_TEXT_COLOR = 0;

    // =====================================================================
    // 五、横向滚动条（代码块 + 表格共用同一套样式）
    //     4.6.3 新增。框架里两处各自有一套参数（MarkwonTheme#codeBlockScrollbar* 与
    //     TableTheme#tableScrollbar*），这里用**同一组常量**喂给两边 ——
    //     「滚动条长得一样」是靠同一个值保证的，不是巧合。
    // =====================================================================

    /**
     * 滚动条高度（实际画出来的是它的 {@code 0.3} 倍粗的一条，和代码块一致）。
     *
     * <p><b>框架默认 0</b> = <b>不显示滚动条</b>：不画。示例给 14dp，配合下面的滑块色才会出现。
     *
     * <p><b>两边都不占行高</b>：代码块把这条压在<b>页脚行</b>里；表格没有页脚行可给 ——
     * 一旦预留，最后一行就会比上面所有行都高，所以表格的滚动条是<b>覆盖</b>在卡片底边框内侧的，
     * 一行的高度完全由内容决定。
     *
     * <p>滚动条整体是「可选装饰」，判据两处一致：<b>高度 &gt; 0</b> 且 <b>轨道/滑块至少有一个颜色</b>。
     */
    private static final int SCROLLBAR_HEIGHT_DP = 14;

    /**
     * 滚动条轨道颜色。{@code 0} = <b>不配置</b> = 不绘制轨道。
     */
    @ColorInt
    private static final int SCROLLBAR_TRACK_COLOR = 0;

    /**
     * 滚动条滑块颜色。{@code 0} = <b>不配置</b> = 不绘制滑块。
     * <p>示例给了一个值，好让滚动条看得见；改成 {@code 0} 即回到「什么都不画」。
     */
    @ColorInt
    private static final int SCROLLBAR_THUMB_COLOR = 0xFFCACACA;

    // =====================================================================
    // 六、GFM 表格（TableTheme）
    //     （来源：TableTheme.buildWithDefaults(Context)）
    // =====================================================================

    /** 单元格内边距（Markwon 只支持对称 padding）。默认 4dp。 */
    private static final int TABLE_CELL_PADDING_DP = 4;

    /** 表格边框宽度。默认 1dp。 */
    private static final int TABLE_BORDER_WIDTH_DP = 1;

    /** 单列最大宽度，超出则换行。默认 200dp；{@code <= 0} = 不限。 */
    private static final int TABLE_MAX_COLUMN_WIDTH_DP = 200;

    /** 表格外圈圆角。默认 0（直角）。 */
    private static final int TABLE_CORNER_RADIUS = 4;

    /**
     * 表格能否横向滚动。默认 {@code false}（宽度约束在视口内，列等宽）。
     *
     * <p>打开后：列宽按内容+ {@link #TABLE_MAX_COLUMN_WIDTH_DP} 上限计算，总宽超出视口即可
     * 横向滚动 —— <b>整块表格区域拖拽都能滚</b>（含单元格、边框、滚动条、以及短行右侧的空白），
     * 纵向手势仍然交回外层 {@code ScrollView}。
     *
     * <p>⚠️ 与代码块不同，这里<b>不</b>需要宿主再设 {@code TableAwareMovementMethod}：
     * 拖拽由插件自己装的 {@code OnTouchListener}（经 {@code GestureRouter} 分发）负责；
     * 若宿主确实设了那个 MovementMethod，单击（未拖动）会被回放给它，单元格内链接照常可点。
     */
    public static final boolean TABLE_SCROLL_ENABLED = true;

    // =====================================================================
    // 七、代码块「复制」按钮（CodeBlockCopyTheme）
    //     框架默认 null = 没有这个按钮（opt-in）。示例给了一个文字版按钮。
    //     ⚠️ 这份样式是「插件」的配置，不是 MarkwonTheme 的 —— 见 #codeBlockScrollPlugin()
    // =====================================================================

    /**
     * 代码块 header 行右侧「复制」按钮开关。
     *
     * <p><b>框架默认关闭</b>：插件上的 {@code codeBlockCopyTheme(...)} 不配置就是 {@code null}，
     * 和滚动条一样属于「不配置就没有这个属性」。
     *
     * <p>按钮由 <b>header 行</b>承载，所以只在 {@link #CODE_BLOCK_SCROLLABLE} 打开（代码块可
     * 滚动）时才存在。另外：只要按钮是开的，<b>没配过</b>的 header 行高度会自动兜底成一行
     * 文字高（显式写 {@code 0} 仍然是「没有这一行」，按钮也随之消失）。
     */
    public static final boolean CODE_BLOCK_COPY_ENABLED = true;

    /**
     * 复制按钮的文案。{@code null} = 用图标（{@link CodeBlockCopyTheme.Builder#text}，
     * 再兜底到框架内置的「双纸片」矢量图）。
     */
    @Nullable
    private static final String CODE_BLOCK_COPY_TEXT = "复制";

    /**
     * 代码块「复制」按钮按下后临时（约 1.2s）显示的文案。{@code null} = 按钮不留任何变化。
     *
     * <p>注意它确认的是<b>按下</b>而不是「复制成功」—— 库不碰剪贴板，压根不知道宿主复制成没成。
     * 想报告真实结果（或干脆不要按钮反馈），把这里留 {@code null}，在宿主自己的
     * {@code CodeBlockScrollPlugin#onCodeBlockCopy} 里说（Toast / Snackbar / 埋点）。
     */
    @Nullable
    private static final String CODE_BLOCK_COPY_SUCCESS_TEXT = "已复制";

    // =====================================================================
    // 八、插件开关（不是 MarkwonTheme 的字段，靠「注册哪个插件 / 传什么参数」控制）
    // =====================================================================

    /**
     * 代码块横向滚动开关：注册 {@link CodeBlockScrollPlugin} 后开启。
     * <p>开启后代码块<b>永不折行</b>，可左右拖动；同时才会出现语言栏与底部滚动条
     * （{@link #CODE_BLOCK_HEADER_HEIGHT_DP} 那批配置也只有这时生效）。
     *
     * <p>⚠️ {@code MarkwonTheme} 里 {@code codeBlockScrollable} 的字段默认是 <b>{@code false}</b>，
     * 这个 {@code true} 是 app-sample 有意覆盖的（要演示滚动能力），<b>不是框架默认值</b>。
     * 改成 {@code false} 即回到默认；此时 {@code codeBlockScrollPlugin()} 也不该再注册。
     */
    public static final boolean CODE_BLOCK_SCROLLABLE = true;

    /** LaTeX 公式插件总开关。框架里没有这个插件，自然也没有默认值。 */
    public static final boolean LATEX_ENABLED = true;

    /** 行内公式 {@code $x$} 开关。默认<b>关</b>（必须显式打开）。 */
    public static final boolean LATEX_INLINE_ENABLED = true;

    /** 块级公式 {@code $$x$$} 开关。默认开。 */
    public static final boolean LATEX_BLOCK_ENABLED = true;

    /** 是否允许单个 {@code $} 作为行内公式定界符（即 {@code $x$}）。默认 {@code false}。 */
    public static final boolean LATEX_ALLOW_SINGLE_DOLLAR_INLINE = false;

    /** 是否用旧版（不依赖 {@code \( \)} 之类的宽松）块级公式解析。默认 {@code false}。 */
    public static final boolean LATEX_BLOCKS_LEGACY = false;

    /** 公式渲染字号，sp。取样例用的 16sp。 */
    public static final float LATEX_TEXT_SIZE_SP = 16F;

    /**
     * 示例自定义扩展 {@code ==高亮==} 的背景色。
     * <p>这不是框架默认值 —— {@code SimpleExtPlugin} 只提供扩展机制，颜色由调用方给。
     */
    @ColorInt
    public static final int MARK_HIGHLIGHT_COLOR = 0xFFFFF176;

    private DefaultTheme() {
    }

    // =====================================================================
    // 工厂
    // =====================================================================

    /**
     * {@link MarkwonTheme} 插件：把上面第一~五节的值显式写回去。
     *
     * <p><b>必须注册在 {@code SyntaxHighlightPlugin} 之后</b>（理由见类注释）。
     */
    @NonNull
    public static AbstractMarkwonPlugin markwonPlugin(@NonNull final Context context) {
        return new AbstractMarkwonPlugin() {
            @Override
            public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                builder
                        // ---------- 尺寸（第一、二节）----------
                        .codeBlockMargin(dp(context, CODE_BLOCK_MARGIN_DP))
                        .blockMargin(dp(context, BLOCK_MARGIN_DP))
                        .blockQuoteWidth(dp(context, BLOCK_QUOTE_WIDTH_DP))
                        .bulletListItemStrokeWidth(dp(context, BULLET_LIST_ITEM_STROKE_WIDTH_DP))
                        .headingBreakHeight(dp(context, HEADING_BREAK_HEIGHT_DP))
                        .thematicBreakHeight(dp(context, THEMATIC_BREAK_HEIGHT_DP))
                        .codeBlockPadding(dp(context, CODE_BLOCK_PADDING_DP))
                        .codeBlockHeaderHeight(dp(context, CODE_BLOCK_HEADER_HEIGHT_DP))
                        .codeHorizontalPadding(dp(context, CODE_HORIZONTAL_PADDING_DP))
                        .codeBackgroundRadius(dp(context, CODE_BACKGROUND_RADIUS))
                        .codeBlockBackgroundRadius(dp(context, CODE_BLOCK_BACKGROUND_RADIUS))
                        .bulletWidth(BULLET_WIDTH)
                        .codeTextSize(CODE_TEXT_SIZE)
                        .codeBlockTextSize(CODE_BLOCK_TEXT_SIZE)
                        // ---------- 颜色（第三、四节）----------
                        // 语言栏没有自己的文字样式/底色，直接复用代码块的这两项
                        // （复制按钮的样式不在这里 —— 它是 CodeBlockScrollPlugin 自己的配置，
                        //   见 #codeBlockScrollPlugin()，不经过全局 MarkwonTheme）
                        .codeBlockTextColor(CODE_BLOCK_TEXT_COLOR)
                        // ---------- 横向滚动条（第五节，与表格共用同一组值）----------
                        // 「不配置就没有这个属性」：高度 0 或两色都是 0 = 不画、不预留。
                        .codeBlockScrollbarHeight(dp(context, SCROLLBAR_HEIGHT_DP))
                        .codeBlockScrollbarTrackColor(SCROLLBAR_TRACK_COLOR)
                        .codeBlockScrollbarThumbColor(SCROLLBAR_THUMB_COLOR)
                        // ---------- 字号梯度 / 开关 ----------
                        .headingTextSizeMultipliers(HEADING_TEXT_SIZE_MULTIPLIERS)
                        // 链接默认带下划线（框架默认 true）
                        .isLinkUnderlined(true);

                // ↓ 以下是「动态默认」+「null 兜底」，本类一律不设置（= 保持框架默认）。
                //    想固定成具体值，取消注释并填值：
                // .blockQuoteColor(0x40000000)          // 默认 = 文字色 × 25%
                // .codeBackgroundColor(0x40000000)      // 默认 = 文字色 × 25%
                // .codeBlockBackgroundColor(0x40000000) // 默认 = 行内代码底
                // .headingBreakColor(0xBFFFFFFF)        // 默认 = 文字色 × 75%
                // .thematicBreakColor(0x40000000)       // 默认 = 文字色 × 25%
                // .listItemColor(0xFF000000)            // 默认 = 文字色
                // .linkColor(0xFF0000EE)                // 默认 = TextView 的 textColorLink
                // .codeTextColor(0xFF000000)            // 默认 = 文字色
                // .codeTypeface(Typeface.MONOSPACE)      // 默认 null → 兜底 MONOSPACE
                // .codeBlockTypeface(Typeface.MONOSPACE) // 默认 = codeTypeface
                // .headingTypeface(Typeface.DEFAULT_BOLD) // 默认 null → 兜底「伪粗体」
            }
        };
    }

    /**
     * 语法高亮主题：Arco / 豆包那套已经去掉，回到框架自带的 {@link Prism4jThemeDefault}。     *
     * <p>它决定代码块的底色（{@link #CODE_BLOCK_BACKGROUND_COLOR}）和 8 组 token 颜色：
     * <table border="1">
     *     <tr><th>token</th><th>颜色</th><th>观感</th></tr>
     *     <tr><td>{@code comment / prolog / doctype / cdata}</td><td>{@code #708090}</td><td>slate 灰</td></tr>
     *     <tr><td>{@code punctuation}</td><td>{@code #999999}</td><td>灰</td></tr>
     *     <tr><td>{@code property / tag / boolean / number / constant / symbol / deleted}</td><td>{@code #990055}</td><td>酒红</td></tr>
     *     <tr><td>{@code selector / attr-name / string / char / builtin / inserted}</td><td>{@code #669900}</td><td>橄榄绿</td></tr>
     *     <tr><td>{@code operator / entity / url}</td><td>{@code #9A6E3A}</td><td>棕</td></tr>
     *     <tr><td>{@code atrule / attr-value / keyword}</td><td>{@code #0077AA}</td><td>蓝</td></tr>
     *     <tr><td>{@code function / class-name}</td><td>{@code #DD4A68}</td><td>洋红</td></tr>
     *     <tr><td>{@code regex / important / variable}</td><td>{@code #EE9900}</td><td>橙</td></tr>
     * </table>
     * 另外 {@code namespace} 会在此基础上乘 0.7 alpha，{@code important / bold} 加粗，
     * {@code italic} 斜体，css 的 {@code string} 额外叠一层 50% 白底。
     *
     * <p><b>想改 token 颜色</b>：不能在本模块建 {@code Prism4jThemeBase} 子类 ——
     * 它的 {@code ColorHashMap.add(...)} 是 {@code protected}，跨包调用会被 Java 的
     * protected 访问规则挡掉（必须同包，这也是 {@code Prism4jThemeDefault} /
     * {@code Prism4jThemeArco} 都住在 {@code io.noties.markwon.syntax} 里的原因）。
     * 两条路：① 直接在 {@code markwon-syntax-highlight} 模块里加一个同包主题类；
     * ② 在本包实现 {@code Prism4jTheme} 接口（只有 3 个方法），自己 apply
     * {@code ForegroundColorSpan}。
     */
    @NonNull
    public static Prism4jTheme syntaxTheme() {
        return Prism4jThemeDefault.create(CODE_BLOCK_BACKGROUND_COLOR);
    }

    /**
     * GFM 表格主题：显式写回 {@link TableTheme} 的三个尺寸 + 圆角 + 滚动开关 + 底部滚动条，
     * 颜色保持默认（表头/偶数行无底色，奇数行 = 文字色 × 22%，边框 = 文字色 × 75%）。
     *
     * <p>底部滚动条用的是第五节的共用常量 —— 和代码块底部那条<b>同一个高度、同一个滑块色</b>，
     * 画法也一致（先铺轨道再压滑块，滑块长度按「视口 / 内容」比例，位置取滚动比例）。
     * 区别只在<b>位置</b>：代码块把它压在页脚行里，表格没有页脚行可给，于是覆盖在卡片底边框内侧
     * —— 表格最后一行的高度完全由内容决定，不会因为滚动条变高。
     * 不配这两项就没有滚动条（表格照样能拖，只是看不到当前位置）。
     *
     * <p>表格比视口宽时，还会在<b>可视区四条边</b>上补一圈「卡片外框」（内容自己的左右边线会随内容
     * 滚出屏幕，外框不滚）—— 所以拖动过程中左右两侧都不会缺边。圆角此时也<b>跟着可视区走</b>：
     * 圆角行的背景只铺可见那一段，角落在卡片边上，超出视口的部分被裁掉。
     */
    @NonNull
    public static TableTheme tableTheme(@NonNull Context context) {
        return TableTheme.buildWithDefaults(context)
                .tableCellPadding(dp(context, TABLE_CELL_PADDING_DP))
                .tableBorderWidth(dp(context, TABLE_BORDER_WIDTH_DP))
                .tableMaxColumnWidth(dp(context, TABLE_MAX_COLUMN_WIDTH_DP))
                .tableCornerRadius(dp(context, TABLE_CORNER_RADIUS))
                .tableScrollEnabled(TABLE_SCROLL_ENABLED)
                .tableScrollbarHeight(dp(context, SCROLLBAR_HEIGHT_DP))
                .tableScrollbarTrackColor(SCROLLBAR_TRACK_COLOR)
                .tableScrollbarThumbColor(SCROLLBAR_THUMB_COLOR)
                // ↓ 默认不设置。想固定成具体色，取消注释并填值：
                // .tableBorderColor(0xBF000000)          // 默认 = 文字色 × 75%
                // .tableOddRowBackgroundColor(0x38000000) // 默认 = 文字色 × 22%（斑马纹）
                // .tableEvenRowBackgroundColor(0x00FFFFFF) // 默认 = 无
                // .tableHeaderRowBackgroundColor(0x0F000000) // 默认 = 无
                .build();
    }

    /**
     * 代码块横向滚动插件。仅当 {@link #CODE_BLOCK_SCROLLABLE} 为 true 时注册。
     *
     * <p>它做四件事：把 {@code codeBlockScrollable} 置 true；在 {@code beforeSetText} 时往每行
     * {@code CodeBlockLineSpan} 注入 viewport（不注入就滚不动）；把复制按钮的<b>样式</b>
     * 交给 {@code CodeBlockSpanFactory}；接管落在<b>复制按钮</b>上的点击并回调宿主。
     *
     * <p>⚠️ 复制按钮是<b>本插件自己的配置</b>（样式见 {@link #codeBlockCopyTheme(Context)}，
     * 行为见 {@code onCodeBlockCopy}），<b>不经过全局 {@code MarkwonTheme}</b> ——
     * 所以别去 {@link #markwonPlugin(Context)} 里找它。
     *
     * <p>⚠️ <b>库不写剪贴板</b>：必须自己接 {@code onCodeBlockCopy} 才能真正复制，
     * 见 {@code MainActivity#createMarkwon}。不接的话按钮按下没反应。
     */
    @NonNull
    public static CodeBlockScrollPlugin codeBlockScrollPlugin(@NonNull final Context context) {
        return CodeBlockScrollPlugin.create()
                // 样式由插件自己持有并直接交给 span factory，不塞进 MarkwonTheme
                .codeBlockCopyTheme(codeBlockCopyTheme(context));
    }

    /**
     * 代码块「复制」按钮的样式对象。
     *
     * <p>和 {@link TableTheme} 一样，这是一个<b>独立的主题对象</b>，而不是散在
     * {@code MarkwonTheme.Builder} 上的六个 setter。更进一步：它连 {@code MarkwonTheme} 都不进 ——
     * 复制按钮是 {@code CodeBlockScrollPlugin} 自己的能力，样式就由插件的
     * {@code codeBlockCopyTheme(...)} 收下（见 {@link #codeBlockScrollPlugin(Context)}）。
     *
     * @return {@link #CODE_BLOCK_COPY_ENABLED} 为 false 时返回 {@code null}（= 没有按钮）
     */
    @Nullable
    public static CodeBlockCopyTheme codeBlockCopyTheme(@NonNull final Context context) {
        if (!CODE_BLOCK_COPY_ENABLED) {
            return null;
        }
        return CodeBlockCopyTheme.builder()
                .enabled(true)
                .text(CODE_BLOCK_COPY_TEXT)
                .successText(CODE_BLOCK_COPY_SUCCESS_TEXT)
                // ↓ 默认不设置。想微调，取消注释并填值：
//                .icon(AppCompatResources.getDrawable(context, R.drawable.ic_msg_pop_copy_txt)) // 图标优先于文字
//                .textSize(sp(context, 12))            // 默认 = 跟随语言栏（codeBlockTextSize → codeTextSize）
//                 .textColor(0xFF3B82F6)            // 默认 = 跟随语言栏文字色
                .build();
    }

    /**
     * LaTeX 公式插件。{@code blocksEnabled} 框架默认就是 true，{@code inlinesEnabled}
     * 默认是 false，这里按 {@link #LATEX_INLINE_ENABLED} 决定。
     */
    @NonNull
    public static JLatexMathPlugin latexPlugin(@NonNull Context context) {
        return JLatexMathPlugin.create(sp(context, LATEX_TEXT_SIZE_SP), config -> config
                .inlinesEnabled(LATEX_INLINE_ENABLED)
                .blocksEnabled(LATEX_BLOCK_ENABLED)
                .allowInlinesSingleDollar(LATEX_ALLOW_SINGLE_DOLLAR_INLINE)
                .blocksLegacy(LATEX_BLOCKS_LEGACY));
    }

    /**
     * 任务列表插件。
     * <p>框架默认（{@code TaskListPlugin.create(Context)}）：复选框的填充色与描边色取
     * context 主题的 {@code android:textColorLink}，对勾色取 {@code android:colorBackground}。
     * 想固定颜色就换成 {@code create(checkedFill, outline, checkMark)} 三参版本。
     */
    @NonNull
    public static TaskListPlugin taskListPlugin(@NonNull Context context) {
        return TaskListPlugin.create(context);
    }

    /**
     * 示例自定义扩展：{@code ==高亮==} 与 {@code ++下划线++}。
     * 这两个<b>不是框架自带</b>的语法，是 {@code SimpleExtPlugin} 的用法演示，
     * 高亮色见 {@link #MARK_HIGHLIGHT_COLOR}。
     */
    @NonNull
    public static SimpleExtPlugin simpleExtPlugin() {
        return SimpleExtPlugin.create(plugin -> {
            plugin.addExtension(2, '=', (configuration, props) ->
                    new BackgroundColorSpan(MARK_HIGHLIGHT_COLOR));
            plugin.addExtension(2, '+', (configuration, props) ->
                    new UnderlineSpan());
        });
    }

    // =====================================================================
    // 单位换算
    // =====================================================================

    private static int dp(@NonNull Context context, int dp) {
        return Dip.create(context).toPx(dp);
    }

    private static int sp(@NonNull Context context, float sp) {
        return Math.round(sp * context.getResources().getDisplayMetrics().scaledDensity);
    }
}

package com.juhe.hotnews

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class NewsSource(
    val id: String,
    val name: String,
    val url: String,
    val type: String,
    val scope: String,
    val enabled: Boolean = true
)

data class NewsItem(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val source: String,
    val scope: String,
    val publishedAt: String,
    val favorite: Boolean = false,
    val read: Boolean = false
) {
    val script: String
        get() = buildString {
            append(title)
            if (summary.isNotBlank()) append("。").append(summary)
            append(" 来源：").append(source)
            if (publishedAt.isNotBlank()) append("，时间：").append(publishedAt)
        }
}

data class AiSettings(
    val endpoint: String = "https://token-plan-sgp.xiaomimimo.com",
    val apiKey: String = "",
    val model: String = "mimo-v2.5-pro",
    val prompt: String = "你是一位冷静、犀利但负责任的中文新闻评论员。请基于新闻稿给出100字以内锐评，指出公共价值、潜在影响和需要继续观察的点，避免未经证实的断言。"
)

data class VoiceSettings(
    val mode: String = "remote",
    val endpoint: String = "https://token-plan-sgp.xiaomimimo.com",
    val apiKey: String = "",
    val model: String = "mimo-v2.5-tts",
    val voice: String = "mimo_default"
)

private enum class ApiAuthMode {
    BEARER,
    API_KEY
}

data class HotTopic(
    val title: String,
    val keywords: List<String>,
    val items: List<NewsItem>
) {
    val sourceCount: Int get() = items.map { it.source }.distinct().size
    val score: Int get() = items.size * 10 + sourceCount * 6
}

data class DailyReportArchive(
    val id: String,
    val title: String,
    val createdAt: String,
    val content: String
)

data class SourceDiagnostic(
    val sourceId: String,
    val sourceName: String,
    val checkedAt: String,
    val success: Boolean,
    val itemCount: Int,
    val message: String
)

private data class DialogActionItem(
    val label: String,
    val icon: String,
    val accent: Int? = null,
    val action: () -> Unit
)

data class FetchResult(
    val items: List<NewsItem>,
    val diagnostics: List<SourceDiagnostic>
)

private val topicStopWords = setOf(
    "中国", "新闻", "报道", "记者", "表示", "发布", "举行", "进行", "今天", "今年",
    "一个", "一种", "一场", "这个", "这些", "相关", "工作", "发展", "推进", "持续",
    "央视", "央视网", "中国日报", "china", "daily"
)

private const val NEWS_CACHE_LIMIT = 240
private const val REPORT_ITEM_LIMIT = 80
private const val LOG_TAG = "JuheHotNews"

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val ink = Color.rgb(18, 16, 13)
    private val inkSoft = Color.rgb(56, 52, 47)
    private val muted = Color.rgb(116, 111, 103)
    private val paper = Color.rgb(250, 248, 242)
    private val panel = Color.rgb(255, 253, 248)
    private val line = Color.rgb(222, 216, 203)
    private val red = Color.rgb(200, 37, 43)
    private val redDeep = Color.rgb(132, 25, 29)
    private val jade = Color.rgb(13, 125, 105)
    private val cobalt = Color.rgb(32, 89, 183)
    private val gold = Color.rgb(201, 148, 47)
    private val mist = Color.rgb(238, 243, 237)
    private val serif = Typeface.SERIF
    private val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var store: AppStore
    private lateinit var repo: NewsRepository
    private lateinit var root: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var tickerTitle: TextView
    private lateinit var tickerSubtitle: TextView
    private lateinit var tickerScore: TextView
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activeTab = "news"
    private var items: List<NewsItem> = emptyList()
    private var selected: NewsItem? = null
    private var lastCritique: String = ""
    private var keywordFilter: String = ""
    private var feedMode: String = "all"
    private var scopeFilter: String = "all"
    private var playQueue: List<NewsItem> = emptyList()
    private var playQueueIndex: Int = 0
    private var isQueueSpeaking: Boolean = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            refreshNews(silent = true)
            refreshHandler.postDelayed(this, 10 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(this)
        store.ensureDefaults()
        repo = NewsRepository()
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        tts = TextToSpeech(this, this)
        items = store.cachedNews()
        keywordFilter = store.keywordFilter()
        feedMode = store.feedMode()
        scopeFilter = store.scopeFilter()
        restoreReadableStartupFilters()
        selected = visibleItems().firstOrNull()
        buildShell()
        if (items.isNotEmpty()) {
            status.text = "已加载本地缓存 ${items.size} 条，正在刷新..."
        }
        showNews()
        refreshNews()
        refreshHandler.postDelayed(refreshTick, 10 * 60 * 1000L)
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(refreshTick)
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.CHINA
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.startsWith("queue-news-") == true) {
                        runOnUiThread {
                            playQueueIndex += 1
                            speakNextInQueue()
                        }
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    if (utteranceId?.startsWith("queue-news-") == true) {
                        runOnUiThread {
                            playQueueIndex += 1
                            speakNextInQueue()
                        }
                    }
                }
            })
        }
    }

    private fun buildShell() {
        root = PaperBackgroundLayout(this, paper, line, gold).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            background = rounded(paper, 0, line, 1)
        }
        val topbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(FrameLayout(this).apply {
            addView(View(context).apply {
                background = rounded(Color.argb(52, 200, 37, 43), 12)
            }, FrameLayout.LayoutParams(dp(39), dp(39)).apply {
                leftMargin = dp(3)
                topMargin = dp(3)
            })
            addView(TextView(context).apply {
                text = "聚"
                gravity = Gravity.CENTER
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = serifBold
                background = rounded(Color.rgb(255, 250, 242), 12, line, 1)
                includeFontPadding = false
            }, FrameLayout.LayoutParams(dp(39), dp(39)))
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            setMargins(0, 0, dp(10), 0)
        })
        val headerCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerCopy.addView(TextView(this).apply {
            text = "聚合热闻"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = serifBold
            includeFontPadding = false
        })
        headerCopy.addView(TextView(this).apply {
            text = "Domestic News Desk"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = condensedBold
            letterSpacing = 0.06f
            setPadding(0, dp(5), 0, 0)
        })
        brand.addView(headerCopy)
        topbar.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        topbar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "实时状态，每十分钟刷新"
            background = rounded(Color.WHITE, 999, line, 1)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(FrameLayout(context).apply {
                background = rounded(Color.argb(34, 13, 125, 105), 999)
                addView(View(context).apply {
                    background = rounded(jade, 999)
                }, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                setMargins(0, 0, dp(7), 0)
            })
            addView(TextView(context).apply {
                text = "LIVE 10m"
                setTextColor(inkSoft)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = condensedBold
                includeFontPadding = false
            })
        })
        header.addView(topbar)
        val ticker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(16), 0, 0)
        }
        ticker.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tickerTitle = TextView(context).apply {
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                typeface = serifBold
                includeFontPadding = false
                setLineSpacing(0f, 1.02f)
            }
            addView(tickerTitle)
            tickerSubtitle = TextView(context).apply {
                setTextColor(red)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = serifBold
                includeFontPadding = false
                setPadding(0, dp(3), 0, 0)
            }
            addView(tickerSubtitle)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val scoreCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(255, 250, 240), 16, ink, 1)
        }
        tickerScore = TextView(this).apply {
            setTextColor(red)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = condensedBold
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        scoreCard.addView(tickerScore)
        scoreCard.addView(TextView(this).apply {
            text = "当前可见"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = condensedBold
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, dp(5), 0, 0)
        })
        ticker.addView(scoreCard, LinearLayout.LayoutParams(dp(72), -2).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        header.addView(ticker)
        status = TextView(this).apply {
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setSingleLine(true)
        }
        root.addView(header)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(20))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(8), dp(9), dp(8))
            background = rounded(Color.argb(230, 255, 253, 248), 25, line, 1)
            elevation = dp(6).toFloat()
        }
        root.addView(tabBar, LinearLayout.LayoutParams(-1, dp(66)).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        setContentView(root)
        root.requestApplyInsets()
        updateHeaderSummary()
        renderTabs()
    }

    private fun restoreReadableStartupFilters() {
        if (items.isEmpty() || visibleItems().isNotEmpty()) return
        keywordFilter = ""
        feedMode = "all"
        scopeFilter = "all"
        store.saveKeywordFilter(keywordFilter)
        store.saveFeedMode(feedMode)
        store.saveScopeFilter(scopeFilter)
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        tabBar.orientation = LinearLayout.HORIZONTAL
        listOf(
            "news" to "新闻",
            "hot" to "热点",
            "briefing" to "简报",
            "sources" to "范围",
            "settings" to "设置"
        ).forEach { (id, label) ->
                val active = activeTab == id
                tabBar.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    contentDescription = "标签$label"
                    isClickable = true
                    isFocusable = true
                    setPadding(0, dp(4), 0, dp(4))
                    background = rounded(
                        if (active) Color.argb(18, 200, 37, 43) else Color.TRANSPARENT,
                        18,
                        if (active) Color.argb(48, 200, 37, 43) else null,
                        if (active) 1 else 0
                    )
                    addView(TabIconView(context, id, if (active) redDeep else muted), LinearLayout.LayoutParams(dp(18), dp(18)))
                    addView(TextView(context).apply {
                        text = label
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                        typeface = condensedBold
                        setTextColor(if (active) redDeep else muted)
                        setPadding(0, dp(5), 0, 0)
                    }, LinearLayout.LayoutParams(-2, -2))
                    setOnClickListener {
                        activeTab = id
                        renderTabs()
                        when (id) {
                            "news" -> showNews()
                            "hot" -> showHotTopics()
                            "briefing" -> showBriefing()
                            "sources" -> showSources()
                            else -> showSettings()
                        }
                    }
                }, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                })
            }
    }

    private fun showNews() {
        activeTab = "news"
        content.removeAllViews()
        renderTabs()
        renderFilter()
        renderFeedMode()
        renderScopeFilter()
        renderVisibleBatchActions()
        selected?.let { renderDetail(it) }
        renderNewsList()
    }

    private fun showHotTopics() {
        activeTab = "hot"
        content.removeAllViews()
        renderTabs()
        content.setPadding(dp(14), dp(14), dp(14), dp(20))
        val topics = hotTopics()
        content.addView(hotTools(topics))
        if (items.isEmpty()) {
            content.addView(note("暂无新闻。点击“刷新”后可自动聚合热点话题。"))
            return
        }
        if (topics.isEmpty()) {
            content.addView(note("当前列表暂未形成可聚合热点，换个筛选词或刷新后再看。"))
            return
        }
        topics.forEachIndexed { index, topic ->
            content.addView(topicCard(topic, index + 1))
        }
    }

    private fun showBriefing() {
        activeTab = "briefing"
        content.removeAllViews()
        renderTabs()
        content.setPadding(dp(14), dp(14), dp(14), dp(20))
        val dailyReport = dailyReportText()
        content.addView(reportCard(dailyReport))
        renderDailyArchives()
    }

    private fun hotTools(topics: List<HotTopic>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(13), 0)
            background = rounded(Color.rgb(255, 250, 242), 16, line, 1)
            addView(StrokeIconView(context, "pulse", muted), LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                setMargins(0, 0, dp(10), 0)
            })
            addView(TextView(context).apply {
                text = "热点聚合基于当前筛选池"
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = condensedBold
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(roundIconButton("分享热点", "share") {
            shareText(hotTopicsText(topics), "分享热点摘要")
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            setMargins(dp(10), 0, 0, 0)
        })
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        }
    }

    private fun topicCard(topic: HotTopic, rank: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.argb(224, 255, 253, 248), 24, line, 1)
        elevation = dp(2).toFloat()
        isClickable = true
        isFocusable = true
        contentDescription = "热点话题 ${topic.title}"
        setOnClickListener { showTopicActions(topic, rank) }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(context).apply {
            text = topic.title
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = serifBold
            setLineSpacing(0f, 1.04f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(FrameLayout(context).apply {
            addView(View(context).apply {
                background = rounded(Color.argb(32, 18, 16, 13), 17)
            }, FrameLayout.LayoutParams(dp(54), dp(54)).apply {
                leftMargin = dp(4)
                topMargin = dp(4)
            })
            addView(TextView(context).apply {
                text = topic.score.toString()
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = condensedBold
                includeFontPadding = false
                background = rounded(heatColor(rank), 17)
            }, FrameLayout.LayoutParams(dp(54), dp(54)))
        }, LinearLayout.LayoutParams(dp(58), dp(58)).apply {
            setMargins(dp(12), 0, 0, 0)
        })
        addView(head)
        topic.items.take(3).forEach { item ->
            addView(TextView(context).apply {
                text = "• ${item.title}"
                setTextColor(inkSoft)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = serif
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(2), dp(10), 0, 0)
                includeFontPadding = false
            })
        }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        }
    }

    private fun heatColor(rank: Int): Int = when (rank) {
        1 -> red
        2 -> jade
        3 -> cobalt
        else -> gold
    }

    private fun showTopicActions(topic: HotTopic, rank: Int) {
        val representative = topic.items.firstOrNull()
        styledActionDialog(
            title = topic.title,
            items = listOf(
                DialogActionItem("查看代表稿", "briefing") {
                    representative?.let {
                        selected = updateItemState(it.id, read = true)
                        lastCritique = ""
                        showNews()
                    } ?: toast("暂无代表稿")
                },
                DialogActionItem("播报代表稿", "speaker") {
                    representative?.let {
                        selected = updateItemState(it.id, read = true)
                        speakSelected()
                    } ?: toast("暂无代表稿")
                },
                DialogActionItem("复制话题", "save") {
                    copyText("hot_topic", hotTopicText(topic, rank), "话题摘要已复制")
                },
                DialogActionItem("分享话题", "share") {
                    shareText(hotTopicText(topic, rank), "分享话题摘要")
                }
            )
        ).show()
    }

    private fun reportCard(report: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.argb(224, 255, 253, 248), 24, line, 1)
        elevation = dp(2).toFloat()
        isClickable = true
        isFocusable = true
        setOnClickListener { showReportActions(report) }
        addView(TextView(context).apply {
            text = "聚合热闻今日日报"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = serifBold
            includeFontPadding = false
        })
        addView(metaBadges(listOf("${visibleItems().ifEmpty { items }.take(REPORT_ITEM_LIMIT).size} 条样本", reportScopeLabel(), "自动生成")), LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(10), 0, dp(10))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(context).apply {
                background = rounded(red, 999)
            }, LinearLayout.LayoutParams(dp(4), -1).apply {
                setMargins(0, 0, dp(12), 0)
            })
            addView(TextView(context).apply {
                text = reportPreview(report)
                contentDescription = "新闻日报内容"
                setTextColor(inkSoft)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = serif
                setLineSpacing(0f, 1.28f)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(13), 0, 0)
        }
        actions.addView(iconPillButton("复制日报", "briefing") { copyText("daily_report", report, "日报已复制") }, pillWrapParams())
        actions.addView(iconPillButton("播报日报", "speaker", ghost = true) { speakText(report, "今日日报") }, pillWrapParams(0))
        addView(actions)
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        }
    }

    private fun metaBadges(values: List<String>): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            values.forEachIndexed { index, value ->
                addView(TextView(context).apply {
                    text = value
                    setTextColor(if (index == 0) redDeep else muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = condensedBold
                    includeFontPadding = false
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = rounded(if (index == 0) Color.rgb(255, 247, 244) else Color.rgb(255, 250, 242), 999, line, 1)
                }, LinearLayout.LayoutParams(-2, -2).apply {
                    setMargins(0, 0, dp(7), 0)
                })
            }
        })
    }

    private fun reportScopeLabel(): String = when {
        scopeFilter != "all" -> "$scopeFilter 范围"
        feedMode != "all" -> feedModeLabel()
        else -> "全部范围"
    }

    private fun reportPreview(report: String): String {
        if (report.startsWith("暂无可生成日报")) return report
        val lines = report.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val overview = lines.firstOrNull { it.startsWith("当前新闻池") || it.startsWith("暂无明显") }
            ?.removePrefix("当前新闻池")
            ?.trim()
            ?.take(28)
            ?: "已形成可读样本。"
        val topics = hotTopics().take(2).joinToString("、") { it.title.take(12) }
        val focus = visibleItems().ifEmpty { items }.take(1).firstOrNull()?.title?.take(20).orEmpty()
        return buildString {
            append("一、今日概览：").append(overview.ifBlank { "已形成可读样本。" })
            append("\n二、热点话题：").append(topics.ifBlank { "暂未形成明显聚合热点。" })
            append("\n三、稍后关注：").append(focus.ifBlank { "等待下一轮刷新和多来源复核。" })
        }
    }

    private fun showReportActions(report: String) {
        val briefing = briefingText()
        styledActionDialog(
            title = "聚合热闻今日日报",
            items = listOf(
                DialogActionItem("分享日报", "share") {
                    shareText(report, "分享新闻日报")
                },
                DialogActionItem("归档日报", "save") {
                    archiveDailyReport(report)
                },
                DialogActionItem("复制简报", "briefing") {
                    copyText("briefing", briefing, "简报已复制")
                },
                DialogActionItem("播报简报", "speaker") {
                    speakText(briefing, "简报")
                },
                DialogActionItem("连续播报", "bolt") {
                    speakQueue()
                },
                DialogActionItem("停止播报", "close", redDeep) {
                    stopSpeaking()
                }
            )
        ).show()
    }

    private fun archiveDailyReport(report: String) {
        if (report.isBlank() || report.startsWith("暂无可生成日报")) {
            toast("暂无可归档日报")
            return
        }
        val createdAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(java.util.Date())
        val title = "日报 $createdAt"
        store.saveDailyReportArchive(
            DailyReportArchive(
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = createdAt,
                content = report
            )
        )
        status.text = "已归档：$title"
        showBriefing()
    }

    private fun renderDailyArchives() {
        val archives = store.dailyReportArchives()
        content.addView(archiveCard(archives))
    }

    private fun archiveCard(archives: List<DailyReportArchive>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(Color.argb(224, 255, 253, 248), 24, line, 1)
        elevation = dp(2).toFloat()
        addView(TextView(context).apply {
            text = "日报归档"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = serifBold
            includeFontPadding = false
        })
        if (archives.isEmpty()) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(Color.rgb(255, 250, 242), 16, line, 1)
                addView(TextView(context).apply {
                    text = "暂无归档日报"
                    setTextColor(inkSoft)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = serifBold
                    includeFontPadding = false
                })
                addView(TextView(context).apply {
                    text = "点击今日日报操作区可保存当前日报稿。"
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = serif
                    setLineSpacing(0f, 1.12f)
                    setPadding(0, dp(6), 0, 0)
                    includeFontPadding = false
                })
            }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12)
            })
            return@apply
        }
        archives.take(4).forEach { archive ->
            addView(archiveRow(archive), LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(12), 0, 0)
            })
        }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        }
    }

    private fun archiveRow(archive: DailyReportArchive): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(10), dp(10))
        background = rounded(Color.rgb(255, 250, 242), 14, line, 1)
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(context).apply {
            text = archive.title
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = serifBold
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        })
        copy.addView(TextView(context).apply {
            text = archiveSummary(archive)
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = serif
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
            includeFontPadding = false
        })
        addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        addView(iconMiniButton("打开", "briefing") { showArchiveActions(archive) }, LinearLayout.LayoutParams(dp(88), dp(34)).apply {
            setMargins(dp(10), 0, 0, 0)
        })
    }

    private fun archiveSummary(archive: DailyReportArchive): String {
        val lines = archive.content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("聚合热闻") && !it.startsWith("更新时间") && !it.startsWith("样本范围") }
            .take(3)
            .toList()
        return lines.joinToString(" / ").ifBlank { "概览 / 热点 / 重点新闻 / 稍后关注" }
    }

    private fun showArchiveActions(archive: DailyReportArchive) {
        styledActionDialog(
            title = archive.title,
            items = listOf(
                DialogActionItem("复制", "briefing") {
                    copyText("archived_daily_report", archive.content, "归档日报已复制")
                },
                DialogActionItem("分享", "share") {
                    shareText(archive.content, "分享归档日报")
                },
                DialogActionItem("播报", "speaker") {
                    speakText(archive.content, archive.title)
                },
                DialogActionItem("删除", "close", redDeep) {
                    store.deleteDailyReportArchive(archive.id)
                    status.text = "已删除归档：${archive.title}"
                    showBriefing()
                }
            )
        ).show()
    }

    private fun renderFilter() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(13), 0)
            background = rounded(Color.argb(199, 255, 255, 255), 16, line, 1)
            elevation = dp(1).toFloat()
        }
        shell.addView(StrokeIconView(this, "search", muted), LinearLayout.LayoutParams(dp(18), dp(18)).apply {
            setMargins(0, 0, dp(10), 0)
        })
        val input = EditText(this).apply {
            hint = "标题 / 摘要 / 来源 / 范围"
            setText(keywordFilter)
            setTextColor(ink)
            setHintTextColor(muted)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            contentDescription = "关键词筛选输入框"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = condensedBold
            background = null
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    keywordFilter = text.toString().trim()
                    store.saveKeywordFilter(keywordFilter)
                    selected = visibleItems().firstOrNull()
                    showReadableTab()
                    true
                } else {
                    false
                }
            }
        }
        shell.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(shell, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(roundIconButton("刷新", "refresh") {
            refreshNews()
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        content.addView(row)
    }

    private fun renderFeedMode() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(1), dp(2), dp(1), dp(12))
        }
        listOf("all" to "全部", "favorite" to "收藏", "unread" to "未读").forEach { (mode, label) ->
            row.addView(chipView(label, feedMode == mode) {
                    feedMode = mode
                    store.saveFeedMode(mode)
                    selected = visibleItems().firstOrNull()
                    showReadableTab()
            }, LinearLayout.LayoutParams(-2, dp(34)).apply {
                setMargins(0, 0, dp(7), 0)
            })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun renderScopeFilter() {
        val scopes = scopeOptions()
        if (scopes.size <= 1) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(1), 0, dp(1), dp(12))
        }
        scopes.forEach { scopeName ->
            val active = scopeFilter == scopeName
            val label = if (scopeName == "all") "全部范围" else scopeName
            row.addView(chipView(label, active) {
                    scopeFilter = scopeName
                    store.saveScopeFilter(scopeFilter)
                    selected = visibleItems().firstOrNull()
                    showReadableTab()
            }, LinearLayout.LayoutParams(-2, dp(34)).apply {
                setMargins(0, 0, dp(7), 0)
            })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun scopeOptions(): List<String> {
        val fromItems = items.map { it.scope.ifBlank { "综合" } }
        val fromSources = store.sources().map { it.scope.ifBlank { "综合" } }
        return listOf("all") + (fromItems + fromSources).distinct().sorted()
    }

    private fun showReadableTab() {
        when (activeTab) {
            "briefing" -> showBriefing()
            "hot" -> showHotTopics()
            else -> showNews()
        }
    }

    private fun renderVisibleBatchActions() {
        val visible = visibleItems()
        val unreadCount = visible.count { !it.read }
        val favoriteCount = visible.count { it.favorite }
        updateHeaderSummary()
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(12))
        }
        stats.addView(statCard(visible.size.toString(), "当前可见"), LinearLayout.LayoutParams(0, dp(62), 1f).apply {
            setMargins(0, 0, dp(4), 0)
        })
        stats.addView(statCard(unreadCount.toString(), "未读"), LinearLayout.LayoutParams(0, dp(62), 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        stats.addView(statCard(favoriteCount.toString(), "收藏"), LinearLayout.LayoutParams(0, dp(62), 1f).apply {
            setMargins(dp(4), 0, 0, 0)
        })
        content.addView(stats)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(14))
        }
        row.addView(iconMiniButton("可见已读", "check") { updateVisibleReadState(read = true) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(0, 0, dp(4), 0)
        })
        row.addView(iconMiniButton("可见未读", "briefing") { updateVisibleReadState(read = false) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        row.addView(iconMiniButton("复制标题", "briefing") {
            val text = visibleHeadlinesText()
            copyText("visible_headlines", text, "可见标题已复制")
            if (text.isNotBlank()) status.text = "已复制当前可见 ${visibleItems().size} 条标题"
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(4), 0, 0, 0)
        })
        content.addView(row)
    }

    private fun renderNewsList() {
        val visible = visibleItems()
        if (items.isEmpty()) {
            content.addView(note("暂无新闻。点击“刷新”从已启用范围抓取。"))
            return
        }
        if (visible.isEmpty()) {
            val reason = listOfNotNull(
                if (scopeFilter != "all") "范围“$scopeFilter”" else null,
                if (keywordFilter.isNotBlank()) "关键词“$keywordFilter”" else null,
                when (feedMode) {
                    "favorite" -> "收藏视图"
                    "unread" -> "未读视图"
                    else -> null
                }
            ).joinToString("、").ifBlank { "当前条件" }
            content.addView(note("没有匹配$reason 的新闻，调整范围、视图或清空筛选后可查看其他稿件。"))
            return
        }
        visible.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(Color.argb(230, 255, 253, 248), 22, line, 1)
                elevation = dp(2).toFloat()
            }
            row.addView(TextView(this).apply {
                text = (index + 1).toString()
                gravity = Gravity.CENTER
                setTextColor(panel)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                background = rounded(red, 13, null, 0)
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                setMargins(0, 0, dp(12), 0)
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = item.title
                    setTextColor(ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                    typeface = serifBold
                    setLineSpacing(0f, 1.18f)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                val time = item.publishedAt.substringAfter(" ", item.publishedAt).take(5).ifBlank { "未知" }
                addView(metaBadges(listOf(item.source, item.scope.ifBlank { "综合" }, time)), LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, dp(8), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener {
                selected = updateItemState(item.id, read = true)
                lastCritique = ""
                showNews()
            }
            row.setOnLongClickListener {
                selected = updateItemState(item.id, read = true)
                speakSelected()
                true
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(10))
            })
        }
    }

    private fun renderDetail(item: NewsItem) {
        content.addView(leadCard {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(StrokeIconView(context, "bolt", red), LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    setMargins(0, 0, dp(8), 0)
                })
                addView(TextView(context).apply {
                    text = "当前新闻稿"
                    setTextColor(red)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = condensedBold
                    includeFontPadding = false
                })
            })
            addView(TextView(context).apply {
                text = item.title
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = serifBold
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(12), 0, dp(8))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                val preview = item.summary.ifBlank { item.script }
                text = if (preview.length > 92) "${preview.take(92)}..." else preview
                setTextColor(inkSoft)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = serif
                setLineSpacing(0f, 1.42f)
            })
            if (lastCritique.isNotBlank()) {
                addView(TextView(context).apply {
                    text = "锐评：$lastCritique"
                    setTextColor(inkSoft)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    typeface = serif
                    setPadding(0, dp(12), 0, 0)
                })
            }
            val mainActions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, 0)
            }
            mainActions.addView(iconPillButton("锐评", "edit") { critiqueSelected() }, pillWrapParams())
            mainActions.addView(iconPillButton("播报", "speaker", ghost = true) { speakSelected() }, pillWrapParams())
            mainActions.addView(iconPillButton("分享", "share", ghost = true) { shareSelected() }, pillWrapParams(0))
            addView(mainActions)
        })
    }

    private fun refreshNews(silent: Boolean = false) {
        if (!silent) status.text = "正在抓取已启用新闻范围..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repo.fetchWithDiagnostics(store.sources().filter { it.enabled }) }
            }.onSuccess { result ->
                store.saveSourceDiagnostics(result.diagnostics)
                val fetched = result.items
                if (fetched.isEmpty()) {
                    val cached = store.cachedNews()
                    if (cached.isNotEmpty()) {
                        items = cached
                        selected = visibleItems().firstOrNull()
                        status.text = "本次未抓到新闻，已显示本地缓存 ${items.size} 条；可在范围页查看来源诊断"
                    } else {
                        status.text = "本次未抓到新闻；可在范围页查看来源诊断"
                    }
                    when (activeTab) {
                        "briefing" -> showBriefing()
                        "hot" -> showHotTopics()
                        "sources" -> showSources()
                        "news" -> showNews()
                    }
                    return@onSuccess
                }
                items = mergeLocalState(fetched.distinctBy { it.id }.sortedByDescending { it.publishedAt }).take(NEWS_CACHE_LIMIT)
                store.saveNewsCache(items)
                selected = visibleItems().firstOrNull()
                val okCount = result.diagnostics.count { it.success }
                val failCount = result.diagnostics.count { !it.success }
                status.text = "已汇总 ${items.size} 条，来源成功 $okCount 个、失败 $failCount 个；前台每 10 分钟自动更新"
                updateHeaderSummary()
                when (activeTab) {
                    "briefing" -> showBriefing()
                    "hot" -> showHotTopics()
                    "sources" -> showSources()
                    "news" -> showNews()
                }
            }.onFailure {
                if (!silent) {
                    val cached = store.cachedNews()
                    if (cached.isNotEmpty()) {
                        items = cached
                        selected = visibleItems().firstOrNull()
                        status.text = "抓取失败，已显示本地缓存 ${items.size} 条：${it.message ?: "未知错误"}"
                    } else {
                        status.text = "抓取失败：${it.message ?: "未知错误"}"
                    }
                    updateHeaderSummary()
                    when (activeTab) {
                        "briefing" -> showBriefing()
                        "hot" -> showHotTopics()
                        "sources" -> showSources()
                        "news" -> showNews()
                    }
                }
            }
        }
    }

    private fun visibleItems(): List<NewsItem> {
        val key = keywordFilter.trim()
        val modeFiltered = when (feedMode) {
            "favorite" -> items.filter { it.favorite }
            "unread" -> items.filter { !it.read }
            else -> items
        }
        val scopeFiltered = if (scopeFilter == "all") modeFiltered else modeFiltered.filter {
            it.scope.ifBlank { "综合" } == scopeFilter
        }
        if (key.isBlank()) return scopeFiltered
        return scopeFiltered.filter {
            it.title.contains(key, ignoreCase = true) ||
                it.summary.contains(key, ignoreCase = true) ||
                it.source.contains(key, ignoreCase = true) ||
            it.scope.contains(key, ignoreCase = true)
        }
    }

    private fun briefingText(): String {
        val list = visibleItems().ifEmpty { items }.take(REPORT_ITEM_LIMIT)
        if (list.isEmpty()) {
            return "暂无可生成简报的新闻。请先刷新，或检查抓取范围是否启用。"
        }
        val scopes = list.groupingBy { it.scope.ifBlank { "综合" } }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString("，") { "${it.key}${it.value}条" }
        val sources = list.groupingBy { it.source.ifBlank { "未知来源" } }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(4)
            .joinToString("，") { "${it.key}${it.value}条" }
        val newest = list.firstOrNull()?.publishedAt?.ifBlank { "未知时间" } ?: "未知时间"
        val headlines = list.take(12).mapIndexed { index, item ->
            "${index + 1}. ${item.title}（${item.source} / ${item.scope.ifBlank { "综合" }}）"
        }.joinToString("\n")
        return buildString {
            appendLine("聚合热闻简报")
            appendLine("更新时间：$newest")
            appendLine("汇总规模：${list.size} 条；范围分布：$scopes；主要来源：$sources。")
            appendLine()
            appendLine("重点新闻：")
            append(headlines)
            appendLine()
            appendLine()
            append("提示：简报基于当前列表、筛选词和全部/收藏/未读视图自动生成。需要深度观点时，可进入新闻详情调用大模型锐评。")
        }
    }

    private fun dailyReportText(): String {
        val list = visibleItems().ifEmpty { items }.take(REPORT_ITEM_LIMIT)
        if (list.isEmpty()) {
            return "暂无可生成日报的新闻。请先刷新，或检查抓取范围是否启用。"
        }
        val topics = hotTopics().take(5)
        val newest = list.firstOrNull()?.publishedAt?.ifBlank { "未知时间" } ?: "未知时间"
        val scopes = list.groupingBy { it.scope.ifBlank { "综合" } }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString("，") { "${it.key}${it.value}条" }
        val sources = list.groupingBy { it.source.ifBlank { "未知来源" } }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(5)
            .joinToString("，") { "${it.key}${it.value}条" }
        val focusItems = list.take(8)
        val watchItems = list.drop(8).take(4)
        return buildString {
            appendLine("聚合热闻今日日报")
            appendLine("更新时间：$newest")
            appendLine("样本范围：${list.size} 条新闻；范围分布：$scopes；主要来源：$sources。")
            appendLine()
            appendLine("一、今日概览")
            appendLine("当前新闻池以${scopes.ifBlank { "综合" }}为主，建议优先关注多来源重复出现的话题，以及发布时间较新的连续更新。")
            appendLine()
            appendLine("二、热点话题")
            if (topics.isEmpty()) {
                appendLine("暂无明显聚合热点，当前更适合按单条新闻浏览。")
            } else {
                topics.forEachIndexed { index, topic ->
                    appendLine("${index + 1}. ${topic.title}：${topic.items.size} 条报道，${topic.sourceCount} 个来源，热度 ${topic.score}。")
                }
            }
            appendLine()
            appendLine("三、重点新闻")
            focusItems.forEachIndexed { index, item ->
                appendLine("${index + 1}. ${item.title}（${item.source} / ${item.scope.ifBlank { "综合" }}）")
            }
            appendLine()
            appendLine("四、稍后关注")
            if (watchItems.isEmpty()) {
                appendLine("暂无更多延伸条目，可等待下一轮刷新。")
            } else {
                watchItems.forEachIndexed { index, item ->
                    appendLine("${index + 1}. ${item.title}（${item.source}）")
                }
            }
            appendLine()
            append("说明：本日报由端侧规则基于当前筛选、收藏/未读视图和热点聚合结果生成，不替代媒体原文与人工研判。")
        }.trim()
    }

    private fun hotTopics(): List<HotTopic> {
        val list = visibleItems().ifEmpty { items }.take(REPORT_ITEM_LIMIT)
        if (list.isEmpty()) return emptyList()
        val buckets = linkedMapOf<String, MutableList<NewsItem>>()
        list.forEach { item ->
            val key = topicKey(item.title)
            if (key.isNotBlank()) buckets.getOrPut(key) { mutableListOf() }.add(item)
        }
        val topics = buckets.values
            .mapNotNull { bucket ->
                val keywords = topicKeywords(bucket)
                val title = keywords.take(3).joinToString(" / ").ifBlank { bucket.first().title.take(24) }
                HotTopic(title = title, keywords = keywords, items = bucket)
            }
            .sortedWith(compareByDescending<HotTopic> { it.score }.thenByDescending { it.items.size })
        val multiItemTopics = topics.filter { it.items.size >= 2 || it.sourceCount >= 2 }
        return (multiItemTopics.ifEmpty { topics }).take(10)
    }

    private fun topicKey(title: String): String {
        return titleKeywords(title).take(3).joinToString("|")
    }

    private fun topicKeywords(items: List<NewsItem>): List<String> {
        return items.flatMap { titleKeywords(it.title) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
            .map { it.key }
            .take(8)
    }

    private fun titleKeywords(title: String): List<String> {
        val cleaned = title
            .replace(Regex("[，。！？、：；（）()《》“”\"'\\[\\]【】|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val tokens = Regex("[\\u4e00-\\u9fa5]{2,}|[A-Za-z0-9]{2,}").findAll(cleaned)
            .map { it.value.lowercase(Locale.ROOT) }
            .flatMap { token ->
                if (token.length in 2..6 || token.any { it in 'a'..'z' || it in '0'..'9' }) {
                    listOf(token)
                } else {
                    token.windowed(4, 2, partialWindows = false)
                }
            }
            .filterNot { it in topicStopWords }
            .distinct()
            .toList()
        return tokens.ifEmpty { listOf(cleaned.take(8)) }.filter { it.isNotBlank() }
    }

    private fun hotTopicsText(topics: List<HotTopic>): String {
        if (topics.isEmpty()) return "暂无可分享热点。"
        return buildString {
            appendLine("聚合热闻热点摘要")
            topics.take(10).forEachIndexed { index, topic ->
                appendLine(hotTopicText(topic, index + 1))
                if (index != topics.lastIndex) appendLine()
            }
        }.trim()
    }

    private fun hotTopicText(topic: HotTopic, rank: Int): String {
        val sources = topic.items.map { it.source }.distinct().take(5).joinToString("、")
        val headlines = topic.items.take(3).joinToString("；") { it.title }
        return "热点$rank：${topic.title}\n热度 ${topic.score}，${topic.items.size} 条报道，${topic.sourceCount} 个来源：$sources。\n代表标题：$headlines"
    }

    private fun mergeLocalState(fetched: List<NewsItem>): List<NewsItem> {
        val cached = items.associateBy { it.id }
        return fetched.map { fresh ->
            cached[fresh.id]?.let { old -> fresh.copy(favorite = old.favorite, read = old.read) } ?: fresh
        }
    }

    private fun updateItemState(id: String, favorite: Boolean? = null, read: Boolean? = null): NewsItem? {
        var updated: NewsItem? = null
        items = items.map {
            if (it.id == id) {
                it.copy(
                    favorite = favorite ?: it.favorite,
                    read = read ?: it.read
                ).also { item -> updated = item }
            } else {
                it
            }
        }
        store.saveNewsCache(items)
        return updated
    }

    private fun updateVisibleReadState(read: Boolean) {
        val visibleIds = visibleItems().map { it.id }.toSet()
        if (visibleIds.isEmpty()) {
            toast("当前没有可批量操作的稿件")
            return
        }
        items = items.map {
            if (it.id in visibleIds) it.copy(read = read) else it
        }
        store.saveNewsCache(items)
        selected = visibleItems().firstOrNull()
        status.text = "已将当前可见 ${visibleIds.size} 条标记为${if (read) "已读" else "未读"}"
        showNews()
    }

    private fun visibleHeadlinesText(): String {
        val visible = visibleItems()
        if (visible.isEmpty()) return ""
        return buildString {
            appendLine("聚合热闻可见标题清单")
            appendLine("范围：${if (scopeFilter == "all") "全部范围" else scopeFilter}；视图：${feedModeLabel()}；关键词：${keywordFilter.ifBlank { "无" }}。")
            visible.take(80).forEachIndexed { index, item ->
                appendLine("${index + 1}. ${item.title}（${item.source} / ${item.scope.ifBlank { "综合" }} / ${item.publishedAt.ifBlank { "未知时间" }}）")
            }
            if (visible.size > 80) appendLine("另有 ${visible.size - 80} 条未列出。")
        }.trim()
    }

    private fun feedModeLabel(): String = when (feedMode) {
        "favorite" -> "收藏"
        "unread" -> "未读"
        else -> "全部"
    }

    private fun stopSpeaking() {
        isQueueSpeaking = false
        playQueue = emptyList()
        playQueueIndex = 0
        tts?.stop()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        status.text = "播报已停止"
    }

    private fun critiqueSelected() {
        val item = selected ?: return toast("请先选择一条新闻")
        val settings = store.aiSettings()
        if (settings.apiKey.isBlank()) {
            toast("请先在设置里填写新闻锐评大模型 API Key")
            return
        }
        status.text = "正在请求大模型锐评..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AiClient.critique(settings, item.script) }
            }.onSuccess {
                lastCritique = it
                status.text = "锐评已生成"
                showNews()
            }.onFailure {
                status.text = "锐评失败：${it.message ?: "未知错误"}"
            }
        }
    }

    private fun speakSelected() {
        val item = selected ?: return toast("请先选择一条新闻")
        val text = if (lastCritique.isBlank()) item.script else "${item.script}。以下是锐评：$lastCritique"
        updateItemState(item.id, read = true)?.let { selected = it }
        speakText(text, "当前新闻")
    }

    private fun speakText(text: String, label: String) {
        val voice = store.voiceSettings()
        isQueueSpeaking = false
        playQueue = emptyList()
        playQueueIndex = 0
        if (voice.mode == "remote") {
            if (voice.apiKey.isBlank()) {
                Log.w(LOG_TAG, "Remote TTS skipped: missing API key, fallback to local TTS")
                speakWithLocalTts(text, label, "语音模型未配置 API Key，已使用系统 TTS 播报：$label")
                return
            }
            status.text = "正在生成远程语音..."
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { AiClient.speech(this@MainActivity, voice, text.take(3500)) }
                }.onSuccess { file ->
                    Log.i(LOG_TAG, "Remote TTS succeeded label=$label file=${file.name} bytes=${file.length()}")
                    playAudio(file)
                    status.text = "正在播放远程语音：$label"
                }.onFailure {
                    Log.w(LOG_TAG, "Remote TTS failed, fallback to local TTS endpoint=${redactedEndpoint(voice.endpoint)} model=${voice.model}", it)
                    speakWithLocalTts(text, label, "远程语音不可用，已使用系统 TTS 播报：$label")
                }
            }
        } else {
            speakWithLocalTts(text, label, "正在使用系统 TTS 播报：$label")
        }
    }

    private fun speakWithLocalTts(text: String, label: String, statusText: String) {
        val available = tts?.isLanguageAvailable(Locale.CHINA) ?: TextToSpeech.LANG_MISSING_DATA
        if (available < TextToSpeech.LANG_AVAILABLE) {
            status.text = "系统 TTS 不可用，无法播报：$label"
            Log.w(LOG_TAG, "Local TTS unavailable for fallback label=$label code=$available")
            toast("系统中文语音不可用")
            return
        }
        tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "news-${System.currentTimeMillis()}")
        status.text = statusText
        Log.i(LOG_TAG, "Local TTS playback started label=$label")
    }

    private fun speakQueue() {
        val voice = store.voiceSettings()
        if (voice.mode != "local") {
            toast("连续播报当前使用 Android 本地 TTS，请在设置中切回本地 TTS")
            return
        }
        val list = visibleItems().ifEmpty { items }.take(20)
        if (list.isEmpty()) return toast("暂无可播报新闻")
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        playQueue = list
        playQueueIndex = 0
        isQueueSpeaking = true
        speakNextInQueue()
    }

    private fun speakNextInQueue() {
        if (!isQueueSpeaking) return
        val item = playQueue.getOrNull(playQueueIndex)
        if (item == null) {
            isQueueSpeaking = false
            playQueue = emptyList()
            playQueueIndex = 0
            status.text = "连续播报已完成"
            showActiveReadableTab()
            return
        }
        updateItemState(item.id, read = true)
        val text = "第 ${playQueueIndex + 1} 条，${item.script}".take(3500)
        status.text = "连续播报 ${playQueueIndex + 1}/${playQueue.size}：${item.title.take(18)}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "queue-news-${System.currentTimeMillis()}-$playQueueIndex")
        showActiveReadableTab()
    }

    private fun showActiveReadableTab() {
        when (activeTab) {
            "briefing" -> showBriefing()
            "hot" -> showHotTopics()
        }
    }

    private fun playAudio(file: File) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, Uri.fromFile(file))
            setOnCompletionListener { it.release() }
            prepare()
            start()
        }
        Log.i(LOG_TAG, "Remote audio playback started file=${file.name} bytes=${file.length()}")
    }

    private fun selectedText(): String {
        val item = selected ?: return ""
        return if (lastCritique.isBlank()) item.script else "${item.script}\n\n锐评：$lastCritique"
    }

    private fun copySelected() {
        val text = selectedText()
        if (text.isBlank()) return toast("请先选择一条新闻")
        copyText("news", text, "新闻稿已复制")
    }

    private fun shareSelected() {
        val text = selectedText()
        if (text.isBlank()) return toast("请先选择一条新闻")
        shareText(text, "分享新闻稿")
    }

    private fun copyText(label: String, text: String, message: String) {
        if (text.isBlank()) return toast("暂无可复制内容")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(message)
    }

    private fun shareText(text: String, title: String) {
        if (text.isBlank()) return toast("暂无可分享内容")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, title))
    }

    private fun openOriginal(item: NewsItem) {
        if (item.url.isBlank()) return toast("这条新闻没有原文链接")
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
        }.onFailure {
            toast("没有可打开原文的应用")
        }
    }

    private fun showSources() {
        activeTab = "sources"
        content.removeAllViews()
        renderTabs()
        content.setPadding(dp(14), dp(14), dp(14), dp(20))
        val diagnostics = store.sourceDiagnostics().associateBy { it.sourceId }
        store.sources().forEach { source ->
            content.addView(sourceCard(source, diagnostics[source.id]))
        }
        content.addView(sourceTools())
    }

    private fun sourceTools(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(12))
        addView(iconMiniButton("新增范围", "edit") { editSource(null) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(0, 0, dp(4), 0)
        })
        addView(iconMiniButton("导入配置", "save") { importSourcesDialog() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        addView(iconMiniButton("导出配置", "share") { exportSources() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(4), 0, 0, 0)
        })
    }

    private fun sourceCard(source: NewsSource, diagnostic: SourceDiagnostic?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(Color.argb(224, 255, 253, 248), 24, line, 1)
        elevation = dp(2).toFloat()
        isClickable = true
        isFocusable = true
        contentDescription = "范围${source.name}"
        setOnClickListener { showSourceActions(source) }
        setOnLongClickListener {
            showSourceManagementActions()
            true
        }
        val success = diagnostic?.success
        val dotColor = when {
            source.enabled.not() -> muted
            success == false -> red
            success == true -> jade
            else -> gold
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(FrameLayout(context).apply {
            addView(View(context).apply {
                background = rounded(Color.argb(26, Color.red(dotColor), Color.green(dotColor), Color.blue(dotColor)), 999)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            addView(View(context).apply {
                background = rounded(dotColor, 999)
            }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
            setMargins(0, 0, dp(10), 0)
        })
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(context).apply {
            text = source.name
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = serifBold
            includeFontPadding = false
        })
        copy.addView(TextView(context).apply {
            val state = when {
                !source.enabled -> "停用"
                diagnostic == null -> "尚未检测"
                diagnostic.success -> "成功 ${diagnostic.itemCount} 条"
                else -> "失败"
            }
            val time = diagnostic?.checkedAt?.substringAfter(" ", diagnostic.checkedAt)?.take(5).orEmpty()
            text = "${source.scope} · ${source.type.uppercase(Locale.ROOT)} · $state${time.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = condensed
            setPadding(0, dp(5), 0, 0)
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        })
        head.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(actionButton("测试") { testSource(source) }, LinearLayout.LayoutParams(dp(72), dp(34)))
        addView(head)
        addView(TextView(context).apply {
            text = diagnostic?.message?.takeIf { diagnostic.success.not() }
                ?: source.url.takeIf { it.isNotBlank() }
                ?: "范围页用诊断状态做第一层扫读，编辑/停用/删除收进操作区。"
            setTextColor(inkSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = serif
            setLineSpacing(0f, 1.18f)
            setPadding(0, dp(12), 0, dp(2))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(10))
        }
    }

    private fun showSourceActions(source: NewsSource) {
        styledActionDialog(
            title = source.name,
            items = listOf(
                DialogActionItem("编辑范围", "edit") { editSource(source) },
                DialogActionItem("测试抓取", "refresh") { testSource(source) },
                DialogActionItem(if (source.enabled) "停用范围" else "启用范围", if (source.enabled) "close" else "check") {
                    store.saveSources(store.sources().map {
                        if (it.id == source.id) it.copy(enabled = !it.enabled) else it
                    })
                    showSources()
                },
                DialogActionItem("删除范围", "close", redDeep) {
                    store.saveSources(store.sources().filterNot { it.id == source.id })
                    showSources()
                },
                DialogActionItem("范围管理", "save") { showSourceManagementActions() }
            )
        ).show()
    }

    private fun showSourceManagementActions() {
        styledActionDialog(
            title = "范围管理",
            items = listOf(
                DialogActionItem("新增范围", "edit") { editSource(null) },
                DialogActionItem("导入配置", "save") { importSourcesDialog() },
                DialogActionItem("导出配置", "share") { exportSources() },
                DialogActionItem("恢复默认", "refresh") {
                    store.resetDefaultSources()
                    status.text = "已恢复默认抓取范围"
                    showSources()
                }
            )
        ).show()
    }

    private fun testSource(source: NewsSource) {
        status.text = "正在测试 ${source.name}..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repo.fetchWithDiagnostics(listOf(source)) }
            }.onSuccess { result ->
                store.saveSourceDiagnostics(result.diagnostics)
                val diagnostic = result.diagnostics.firstOrNull()
                status.text = if (diagnostic?.success == true) {
                    "${source.name} 测试成功，抓到 ${diagnostic.itemCount} 条"
                } else {
                    "${source.name} 测试失败：${diagnostic?.message ?: "未知错误"}"
                }
                showSources()
            }.onFailure {
                status.text = "${source.name} 测试失败：${it.message ?: "未知错误"}"
            }
        }
    }

    private fun exportSources() {
        val text = store.exportSourcesJson()
        copyText("news_sources", text, "抓取范围配置已复制")
        status.text = "已导出 ${store.sources().size} 个抓取范围到剪贴板"
    }

    private fun importSourcesDialog() {
        val input = edit("粘贴导出的 JSON 配置", "", multi = true)
        val merge = CheckBox(this).apply {
            text = "合并到现有范围（同 URL 或同 ID 会覆盖旧配置）"
            isChecked = true
            setTextColor(inkSoft)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(dialogInputBlock("JSON", input))
            addView(dialogToggleBlock("Import Mode", merge), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            })
        }
        styledFormDialog(
            title = "导入抓取范围",
            body = body,
            primaryLabel = "导入",
            secondaryLabel = "取消"
        ) { dialog ->
            val imported = runCatching { parseSourcesJson(input.text.toString()) }
                .onFailure { toast(it.message ?: "配置解析失败") }
                .getOrNull() ?: return@styledFormDialog
            if (imported.isEmpty()) {
                toast("配置中没有可导入的范围")
                return@styledFormDialog
            }
            val finalSources = if (merge.isChecked) {
                val importedIds = imported.map { it.id }.toSet()
                val importedUrls = imported.map { it.url }.toSet()
                store.sources().filterNot { it.id in importedIds || it.url in importedUrls } + imported
            } else {
                imported
            }
            store.saveSources(finalSources)
            dialog.dismiss()
            status.text = "已导入 ${imported.size} 个抓取范围，当前共 ${finalSources.size} 个"
            showSources()
        }.show()
    }

    private fun parseSourcesJson(text: String): List<NewsSource> {
        val raw = text.trim()
        if (raw.isBlank()) error("请先粘贴 JSON 配置")
        val arr = if (raw.startsWith("{")) {
            JSONObject(raw).optJSONArray("sources") ?: error("JSON 对象中缺少 sources 数组")
        } else {
            JSONArray(raw)
        }
        val usedIds = mutableSetOf<String>()
        val usedUrls = mutableSetOf<String>()
        return (0 until arr.length()).map { index ->
            val o = arr.optJSONObject(index) ?: error("第 ${index + 1} 项不是对象")
            val originalId = o.optString("id").trim()
            val id = if (originalId.isBlank() || originalId in usedIds) UUID.randomUUID().toString() else originalId
            usedIds.add(id)
            val source = NewsSource(
                id = id,
                name = o.optString("name").ifBlank { "导入来源" },
                url = o.optString("url").trim(),
                type = o.optString("type", "rss").trim().lowercase(Locale.ROOT),
                scope = o.optString("scope").ifBlank { "综合" },
                enabled = o.optBoolean("enabled", true)
            )
            validateSource(source)?.let { error("第 ${index + 1} 项无效：$it") }
            if (!usedUrls.add(source.url)) error("第 ${index + 1} 项无效：URL 重复")
            source
        }
    }

    private fun editSource(existing: NewsSource?) {
        val name = edit("名称", existing?.name.orEmpty())
        val scopeName = edit("范围标签，如 国内/国际/财经", existing?.scope ?: "综合")
        val type = edit("类型：rss 或 cctv_jsonp", existing?.type ?: "rss")
        val url = edit("URL", existing?.url.orEmpty())
        val enabled = CheckBox(this).apply {
            text = "启用这个抓取范围"
            isChecked = existing?.enabled ?: true
            setTextColor(inkSoft)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(
                dialogInputBlock("Name", name),
                dialogInputBlock("Scope", scopeName),
                dialogInputBlock("Type", type),
                dialogInputBlock("URL", url),
                dialogToggleBlock("Status", enabled)
            ).forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = dp(10)
                })
            }
        }
        styledFormDialog(
            title = if (existing == null) "新增抓取范围" else "编辑抓取范围",
            body = body,
            primaryLabel = "保存",
            secondaryLabel = "取消"
        ) { dialog ->
            val source = NewsSource(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.text.toString().ifBlank { "自定义来源" },
                scope = scopeName.text.toString().ifBlank { "综合" },
                type = type.text.toString().trim().lowercase(Locale.ROOT).ifBlank { "rss" },
                url = url.text.toString().trim(),
                enabled = enabled.isChecked
            )
            if (source.url.isBlank()) {
                toast("URL 不能为空")
                return@styledFormDialog
            }
            validateSource(source)?.let {
                toast(it)
                return@styledFormDialog
            }
            val duplicate = store.sources().any { it.id != source.id && it.url == source.url }
            if (duplicate) {
                toast("这个 URL 已存在")
                return@styledFormDialog
            }
            val sources = store.sources().filterNot { it.id == source.id } + source
            store.saveSources(sources)
            dialog.dismiss()
            showSources()
        }.show()
    }

    private fun validateSource(source: NewsSource): String? {
        if (source.url.isBlank()) return "URL 不能为空"
        if (!source.url.startsWith("https://") && !source.url.startsWith("http://")) return "URL 必须以 http:// 或 https:// 开头"
        if (source.type !in setOf("rss", "cctv_jsonp")) return "类型只能是 rss 或 cctv_jsonp"
        return null
    }

    private fun showSettings() {
        activeTab = "settings"
        content.removeAllViews()
        renderTabs()
        content.setPadding(dp(14), dp(14), dp(14), dp(20))
        val ai = store.aiSettings()
        content.addView(settingCard(
            title = "新闻锐评大模型",
            desc = "OpenAI-compatible Chat Completions，自检直接使用当前配置。",
            fields = listOf(
                "Endpoint" to ai.endpoint,
                "Model" to ai.model
            ),
            primary = "保存",
            secondary = "测试锐评",
            primaryAction = { editAiSettingsDialog(ai) },
            secondaryAction = { testAiSettings(store.aiSettings()) }
        ))

        val voice = store.voiceSettings()
        content.addView(settingCard(
            title = "语音播报",
            desc = "默认使用 MiMo Chat Audio；远程不可用时自动回退 Android 本地 TTS。",
            fields = listOf(
                "Mode" to if (voice.mode == "local") "Android 本地 TTS" else "远程语音模型",
                "Voice" to if (voice.mode == "local") "系统中文语音包" else "${voice.voice} / ${voice.model}"
            ),
            primary = "保存",
            secondary = "测试语音",
            primaryAction = { editVoiceSettingsDialog(voice) },
            secondaryAction = { testVoiceSettings(store.voiceSettings()) }
        ))
    }

    private fun editAiSettingsDialog(settings: AiSettings) {
        val aiEndpoint = edit("Chat Completions Endpoint", settings.endpoint)
        val aiModel = edit("模型", settings.model)
        val aiKey = edit("API Key", settings.apiKey, password = true)
        val aiPrompt = edit("锐评提示词", settings.prompt, multi = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(
                dialogInputBlock("Endpoint", aiEndpoint),
                dialogInputBlock("Model", aiModel),
                dialogInputBlock("API Key", aiKey),
                dialogInputBlock("Prompt", aiPrompt)
            ).forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = dp(10)
                })
            }
        }
        val dialog = styledFormDialog(
            title = "新闻锐评大模型",
            body = box,
            primaryLabel = "保存",
            secondaryLabel = "取消"
        ) { dialogRef ->
                store.saveAiSettings(AiSettings(aiEndpoint.text.toString(), aiKey.text.toString(), aiModel.text.toString(), aiPrompt.text.toString()))
                toast("已保存锐评设置")
                dialogRef.dismiss()
                showSettings()
        }
        dialog.show()
    }

    private fun editVoiceSettingsDialog(settings: VoiceSettings) {
        val localMode = CheckBox(this).apply {
            text = "使用 Android 本地 TTS（取消勾选则使用远程语音模型）"
            isChecked = settings.mode == "local"
            setTextColor(inkSoft)
        }
        val voiceEndpoint = edit("Speech Endpoint", settings.endpoint)
        val voiceModel = edit("语音模型", settings.model)
        val voiceName = edit("音色", settings.voice)
        val voiceKey = edit("API Key", settings.apiKey, password = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(dialogToggleBlock("Playback", localMode), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
            listOf(
                dialogInputBlock("Endpoint", voiceEndpoint),
                dialogInputBlock("Model", voiceModel),
                dialogInputBlock("Voice", voiceName),
                dialogInputBlock("API Key", voiceKey)
            ).forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = dp(10)
                })
            }
        }
        val dialog = styledFormDialog(
            title = "语音播报",
            body = box,
            primaryLabel = "保存",
            secondaryLabel = "取消"
        ) { dialogRef ->
                store.saveVoiceSettings(
                    VoiceSettings(
                        mode = if (localMode.isChecked) "local" else "remote",
                        endpoint = voiceEndpoint.text.toString(),
                        apiKey = voiceKey.text.toString(),
                        model = voiceModel.text.toString(),
                        voice = voiceName.text.toString()
                    )
                )
                toast("已保存语音设置")
                dialogRef.dismiss()
                showSettings()
        }
        dialog.show()
    }

    private fun styledFormDialog(
        title: String,
        body: View,
        primaryLabel: String,
        secondaryLabel: String,
        onPrimary: (AlertDialog) -> Unit
    ): AlertDialog {
        val shell = FrameLayout(this).apply {
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.argb(248, 255, 253, 248), 24, line, 1)
            elevation = dp(10).toFloat()
            addView(TextView(context).apply {
                text = title
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = serifBold
                includeFontPadding = false
            })
            addView(body, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(14)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(16)
            })
        }
        shell.addView(card)
        val dialog = AlertDialog.Builder(this)
            .setView(shell)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.38f)
        }
        val actions = card.getChildAt(card.childCount - 1) as LinearLayout
        actions.addView(iconPillButton(secondaryLabel, "close", ghost = true, strongGhost = true) {
            dialog.dismiss()
        }, pillWrapParams())
        actions.addView(iconPillButton(primaryLabel, "save") {
            onPrimary(dialog)
        }, pillWrapParams(0))
        return dialog
    }

    private fun styledActionDialog(title: String, items: List<DialogActionItem>): AlertDialog {
        val shell = FrameLayout(this).apply {
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.argb(248, 255, 253, 248), 24, line, 1)
            elevation = dp(10).toFloat()
            addView(TextView(context).apply {
                text = title
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = serifBold
                includeFontPadding = false
            })
            addView(list, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(14)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(16)
            })
        }
        shell.addView(card)
        val dialog = AlertDialog.Builder(this)
            .setView(shell)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.38f)
        }
        items.forEachIndexed { index, item ->
            list.addView(dialogActionRow(item) {
                dialog.dismiss()
                item.action()
            }, LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(8)
            })
        }
        val actions = card.getChildAt(card.childCount - 1) as LinearLayout
        actions.addView(iconPillButton("取消", "close", ghost = true, strongGhost = true) {
            dialog.dismiss()
        }, pillWrapParams(0))
        return dialog
    }

    private fun dialogInputBlock(label: String, input: EditText): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(Color.rgb(255, 250, 242), 16, line, 1)
        addView(TextView(context).apply {
            text = label
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = condensedBold
            includeFontPadding = false
        })
        input.background = null
        input.setPadding(0, 0, 0, 0)
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        input.typeface = serif
        addView(input, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
    }

    private fun dialogToggleBlock(label: String, toggle: CheckBox): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(Color.rgb(255, 250, 242), 16, line, 1)
        addView(TextView(context).apply {
            text = label
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = condensedBold
            includeFontPadding = false
        })
        toggle.buttonTintList = ColorStateList.valueOf(redDeep)
        toggle.setTextColor(inkSoft)
        toggle.typeface = serif
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        toggle.includeFontPadding = false
        toggle.setPadding(0, 0, 0, 0)
        addView(toggle, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
    }

    private fun dialogActionRow(item: DialogActionItem, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(Color.rgb(255, 250, 242), 16, line, 1)
        addView(StrokeIconView(context, item.icon, item.accent ?: inkSoft), LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            setMargins(0, 0, dp(10), 0)
        })
        addView(TextView(context).apply {
            text = item.label
            setTextColor(item.accent ?: ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = serifBold
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "进入"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = condensed
            includeFontPadding = false
        })
        setOnClickListener { action() }
    }

    private fun settingCard(
        title: String,
        desc: String,
        fields: List<Pair<String, String>>,
        primary: String,
        secondary: String,
        primaryAction: () -> Unit,
        secondaryAction: () -> Unit
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.argb(224, 255, 253, 248), 24, line, 1)
        elevation = dp(2).toFloat()
        addView(TextView(context).apply {
            text = title
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = serifBold
            includeFontPadding = false
        })
        addView(TextView(context).apply {
            text = desc
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = serif
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(8), 0, dp(12))
        })
        fields.forEachIndexed { index, (label, value) ->
            addView(settingField(label, value), LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(10)
            })
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val primaryIcon = if (primary.contains("保存")) "save" else "check"
        val secondaryIcon = if (secondary.contains("语音")) "speaker" else "check"
        actions.addView(iconPillButton(primary, primaryIcon, ghost = false, action = primaryAction), pillWrapParams())
        actions.addView(iconPillButton(secondary, secondaryIcon, ghost = true, strongGhost = true, action = secondaryAction), pillWrapParams(0))
        addView(actions)
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        }
    }

    private fun settingField(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(Color.rgb(255, 250, 242), 16, line, 1)
        addView(TextView(context).apply {
            text = label
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = condensedBold
            includeFontPadding = false
        })
        addView(TextView(context).apply {
            text = value.ifBlank { "未配置" }
            setTextColor(inkSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = serif
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(7), 0, 0)
        })
    }

    private fun testAiSettings(settings: AiSettings) {
        validateAiSettings(settings)?.let {
            status.text = "锐评接口自检未开始：$it"
            Log.w(LOG_TAG, "AI settings self-test skipped: $it")
            toast(it)
            return
        }
        Log.i(LOG_TAG, "AI settings self-test started endpoint=${redactedEndpoint(settings.endpoint)} model=${settings.model}")
        status.text = "正在自检新闻锐评接口..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AiClient.critique(settings, "测试新闻稿：某地发布便民服务新举措，请给出20字以内简短锐评。")
                }
            }.onSuccess {
                Log.i(LOG_TAG, "AI settings self-test succeeded chars=${it.length}")
                status.text = "锐评接口自检成功：${it.ifBlank { "返回为空" }.take(80)}"
                toast("锐评接口自检成功")
            }.onFailure {
                Log.w(LOG_TAG, "AI settings self-test failed endpoint=${redactedEndpoint(settings.endpoint)} model=${settings.model}", it)
                status.text = "锐评接口自检失败：${it.message ?: "未知错误"}"
                toast("锐评接口自检失败")
            }
        }
    }

    private fun testVoiceSettings(settings: VoiceSettings) {
        if (settings.mode == "local") {
            val available = tts?.isLanguageAvailable(Locale.CHINA) ?: TextToSpeech.LANG_MISSING_DATA
            if (available < TextToSpeech.LANG_AVAILABLE) {
                status.text = "本地 TTS 自检失败：系统中文语音不可用"
                Log.w(LOG_TAG, "Local TTS self-test failed: zh-CN unavailable code=$available")
                toast("系统中文语音不可用")
                return
            }
            tts?.speak("聚合热闻本地语音自检成功。", TextToSpeech.QUEUE_FLUSH, null, "voice-test-${System.currentTimeMillis()}")
            status.text = "本地 TTS 自检成功，正在播放测试语音"
            Log.i(LOG_TAG, "Local TTS self-test succeeded")
            return
        }
        validateVoiceSettings(settings, requireApiKey = false)?.let {
            status.text = "远程语音自检未开始：$it"
            Log.w(LOG_TAG, "Remote voice self-test skipped: $it")
            toast(it)
            return
        }
        if (settings.apiKey.isBlank()) {
            Log.w(LOG_TAG, "Remote voice self-test skipped: missing API key, fallback to local TTS")
            speakWithLocalTts(
                "聚合热闻远程语音接口未配置 API Key，已回退系统语音播报。",
                "远程语音自检回退",
                "远程语音接口未配置 API Key，已回退系统 TTS"
            )
            toast("远程语音未配置 API Key，已回退系统 TTS")
            return
        }
        Log.i(LOG_TAG, "Remote voice self-test started endpoint=${redactedEndpoint(settings.endpoint)} model=${settings.model} voice=${settings.voice}")
        status.text = "正在自检远程语音接口..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AiClient.speech(this@MainActivity, settings, "聚合热闻远程语音接口自检。") }
            }.onSuccess { file ->
                Log.i(LOG_TAG, "Remote voice self-test succeeded file=${file.name} bytes=${file.length()}")
                playAudio(file)
                status.text = "远程语音接口自检成功，正在播放测试音频"
                toast("远程语音自检成功")
            }.onFailure {
                Log.w(LOG_TAG, "Remote voice self-test failed endpoint=${redactedEndpoint(settings.endpoint)} model=${settings.model} voice=${settings.voice}", it)
                speakWithLocalTts(
                    "聚合热闻远程语音接口不可用，已回退系统语音播报。",
                    "远程语音自检回退",
                    "远程语音接口自检失败，已回退系统 TTS"
                )
                toast("远程语音不可用，已回退系统 TTS")
            }
        }
    }

    private fun redactedEndpoint(endpoint: String): String =
        runCatching { URL(endpoint).host }.getOrDefault(endpoint).ifBlank { "blank" }

    private fun validateAiSettings(settings: AiSettings): String? {
        validateEndpoint(settings.endpoint, "锐评 Endpoint")?.let { return it }
        if (settings.model.isBlank()) return "锐评模型不能为空"
        if (settings.apiKey.isBlank()) return "锐评 API Key 不能为空"
        if (settings.prompt.isBlank()) return "锐评提示词不能为空"
        return null
    }

    private fun validateVoiceSettings(settings: VoiceSettings, requireApiKey: Boolean = true): String? {
        validateEndpoint(settings.endpoint, "语音 Endpoint")?.let { return it }
        if (settings.model.isBlank()) return "语音模型不能为空"
        if (settings.voice.isBlank()) return "音色不能为空"
        if (requireApiKey && settings.apiKey.isBlank()) return "语音 API Key 不能为空"
        return null
    }

    private fun validateEndpoint(value: String, label: String): String? {
        val endpoint = value.trim()
        if (endpoint.isBlank()) return "$label 不能为空"
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) return "$label 必须以 http:// 或 https:// 开头"
        return null
    }

    private fun updateHeaderSummary() {
        if (!::tickerTitle.isInitialized || !::tickerSubtitle.isInitialized || !::tickerScore.isInitialized) return
        val visible = visibleItems()
        val diagnostics = store.sourceDiagnostics()
        val okCount = diagnostics.count { it.success }.takeIf { diagnostics.isNotEmpty() }
            ?: store.sources().count { it.enabled }
        tickerTitle.text = "${items.size} 条稿件"
        tickerSubtitle.text = "${okCount} 个来源在线"
        tickerScore.text = visible.size.toString()
    }

    private fun sectionTitle(textValue: String) = TextView(this).apply {
        text = textValue
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(20), dp(4), dp(9))
        includeFontPadding = false
    }

    private fun note(textValue: String) = TextView(this).apply {
        text = textValue
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = serif
        setLineSpacing(0f, 1.12f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(mist, 16, line, 1)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(4), 0, dp(10))
        }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = label
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        setPadding(dp(6), 0, dp(6), 0)
        setTextColor(inkSoft)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = condensedBold
        stateListAnimator = null
        elevation = 0f
        background = InsetDrawable(rounded(Color.rgb(255, 254, 250), 14, line, 1), dp(3), 0, dp(3), 0)
        setOnClickListener { action() }
    }

    private fun pillWrapParams(endMarginDp: Int = 8): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-2, dp(38)).apply {
            if (endMarginDp > 0) setMargins(0, 0, dp(endMarginDp), 0)
        }

    private fun pillButton(label: String, ghost: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = label
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(if (ghost) muted else inkSoft)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        elevation = 0f
        background = InsetDrawable(
            rounded(if (ghost) Color.argb(174, 255, 253, 248) else Color.WHITE, 999, line, 1),
            dp(3),
            0,
            dp(3),
            0
        )
        setOnClickListener { action() }
    }

    private fun iconPillButton(label: String, icon: String, ghost: Boolean = false, strongGhost: Boolean = false, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = label
        stateListAnimator = null
        elevation = 0f
        setPadding(dp(10), 0, dp(10), 0)
        val fg = if (ghost && !strongGhost) muted else inkSoft
        background = rounded(
            when {
                ghost && strongGhost -> Color.WHITE
                ghost -> Color.argb(174, 255, 253, 248)
                else -> Color.WHITE
            },
            999,
            line,
            1
        )
        addView(StrokeIconView(context, icon, fg), LinearLayout.LayoutParams(dp(14), dp(14)).apply {
            setMargins(0, 0, dp(5), 0)
        })
        addView(TextView(context).apply {
            text = label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = condensedBold
            includeFontPadding = false
        })
        setOnClickListener { action() }
    }

    private fun iconButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = label
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        setTextColor(inkSoft)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        elevation = 0f
        background = rounded(Color.argb(235, 255, 253, 248), 16, line, 1)
        setOnClickListener { action() }
    }

    private fun roundIconButton(label: String, icon: String, action: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = label
        background = rounded(Color.argb(235, 255, 253, 248), 16, line, 1)
        elevation = 0f
        stateListAnimator = null
        addView(StrokeIconView(context, icon, inkSoft), LinearLayout.LayoutParams(dp(17), dp(17)))
        setOnClickListener { action() }
    }

    private fun iconMiniButton(label: String, icon: String, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = label
        background = rounded(Color.WHITE, 14, line, 1)
        elevation = 0f
        stateListAnimator = null
        setPadding(dp(10), 0, dp(10), 0)
        addView(StrokeIconView(context, icon, inkSoft), LinearLayout.LayoutParams(dp(14), dp(14)).apply {
            setMargins(0, 0, dp(5), 0)
        })
        addView(TextView(context).apply {
            text = label
            setTextColor(inkSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = condensedBold
            includeFontPadding = false
        })
        setOnClickListener { action() }
    }

    private fun statCard(value: String, label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = rounded(Color.argb(194, 255, 255, 255), 16, line, 1)
        addView(TextView(context).apply {
            text = value
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = condensedBold
            includeFontPadding = false
        })
        addView(TextView(context).apply {
            text = label
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = condensed
            setPadding(0, dp(4), 0, 0)
            includeFontPadding = false
        })
    }

    private fun card(build: LinearLayout.() -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = rounded(panel, 20, line, 1)
        build()
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(7), 0, dp(11))
        }
    }

    private fun leadCard(build: LinearLayout.() -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.argb(240, 255, 253, 248), 24, line, 1)
        build()
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(12))
        }
    }

    private fun edit(hint: String, value: String, password: Boolean = false, multi: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(ink)
        setHintTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = rounded(panel, 14, line, 1)
        setPadding(dp(12), 0, dp(12), 0)
        setSingleLine(!multi)
        minLines = if (multi) 4 else 1
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
    }

    private fun chipView(label: String, active: Boolean, action: () -> Unit): View = TextView(this).apply {
        text = label
        contentDescription = label
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        includeFontPadding = false
        setPadding(dp(12), 0, dp(12), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = if (active) condensedBold else condensed
        setTextColor(if (active) redDeep else muted)
        background = rounded(
            if (active) Color.argb(20, 200, 37, 43) else Color.argb(184, 255, 255, 255),
            999,
            if (active) Color.argb(72, 200, 37, 43) else line,
            1
        )
        setOnClickListener { action() }
    }

    private fun Button.styleChip(active: Boolean) {
        minHeight = 0
        minWidth = 0
        stateListAnimator = null
        elevation = 0f
        setPadding(dp(8), 0, dp(8), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = if (active) condensedBold else condensed
        setTextColor(if (active) red else muted)
        background = rounded(if (active) Color.rgb(255, 247, 244) else panel, 18, if (active) Color.rgb(232, 190, 185) else line, 1)
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

private class TabIconView(context: Context, private val icon: String, color: Int) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.85f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 24f
        val left = (width - 24f * scale) / 2f
        val top = (height - 24f * scale) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        when (icon) {
            "news" -> drawNews(canvas)
            "hot" -> drawHot(canvas)
            "briefing" -> drawBriefing(canvas)
            "sources" -> drawSources(canvas)
            else -> drawSettings(canvas)
        }
        canvas.restore()
    }

    private fun drawNews(canvas: Canvas) {
        val path = Path().apply {
            moveTo(4f, 22f)
            lineTo(4f, 4.2f)
            quadTo(4f, 2.2f, 6.1f, 2.2f)
            lineTo(18f, 2.2f)
            lineTo(18f, 22f)
        }
        canvas.drawPath(path, stroke)
        canvas.drawLine(8f, 6.5f, 14f, 6.5f, stroke)
        canvas.drawLine(8f, 10.5f, 16f, 10.5f, stroke)
        canvas.drawLine(8f, 14.5f, 13.5f, 14.5f, stroke)
    }

    private fun drawHot(canvas: Canvas) {
        val path = Path().apply {
            moveTo(8.6f, 14.4f)
            cubicTo(7.7f, 17.3f, 9.5f, 21.8f, 12.1f, 22f)
            cubicTo(16.2f, 21.8f, 19f, 18.8f, 19f, 15f)
            cubicTo(19f, 10.1f, 14f, 7f, 14f, 2.2f)
            cubicTo(10.6f, 4.5f, 9f, 7.5f, 9f, 11f)
            cubicTo(9f, 12.2f, 9.2f, 13.2f, 9.8f, 14f)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawBriefing(canvas: Canvas) {
        box.set(6f, 3f, 18f, 21f)
        canvas.drawRect(box, stroke)
        canvas.drawLine(9f, 7f, 15f, 7f, stroke)
        canvas.drawLine(9f, 11f, 15f, 11f, stroke)
        canvas.drawLine(9f, 15f, 12.5f, 15f, stroke)
    }

    private fun drawSources(canvas: Canvas) {
        canvas.drawLine(6f, 20f, 6f, 10f, stroke)
        canvas.drawLine(12f, 20f, 12f, 14f, stroke)
        canvas.drawLine(18f, 20f, 18f, 4f, stroke)
    }

    private fun drawSettings(canvas: Canvas) {
        box.set(9f, 9f, 15f, 15f)
        canvas.drawOval(box, stroke)
        box.set(5.3f, 5.3f, 18.7f, 18.7f)
        canvas.drawOval(box, stroke)
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val inner = 8.7f
            val outer = 10.8f
            val x1 = 12f + kotlin.math.cos(angle).toFloat() * inner
            val y1 = 12f + kotlin.math.sin(angle).toFloat() * inner
            val x2 = 12f + kotlin.math.cos(angle).toFloat() * outer
            val y2 = 12f + kotlin.math.sin(angle).toFloat() * outer
            canvas.drawLine(x1, y1, x2, y2, stroke)
        }
        canvas.drawCircle(12f, 12f, 1.3f, fill)
    }
}

private class StrokeIconView(context: Context, private val icon: String, color: Int) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 24f
        val left = (width - 24f * scale) / 2f
        val top = (height - 24f * scale) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        when (icon) {
            "close" -> drawClose(canvas)
            "edit" -> drawEdit(canvas)
            "bolt" -> drawBolt(canvas)
            "speaker" -> drawSpeaker(canvas)
            "save" -> drawSave(canvas)
            "check" -> drawCheck(canvas)
            "briefing" -> drawBriefing(canvas)
            "refresh" -> drawRefresh(canvas)
            "share" -> drawShare(canvas)
            "pulse" -> drawPulse(canvas)
            else -> drawSearch(canvas)
        }
        canvas.restore()
    }

    private fun drawSearch(canvas: Canvas) {
        box.set(4f, 4f, 18f, 18f)
        canvas.drawOval(box, stroke)
        canvas.drawLine(16.5f, 16.5f, 20f, 20f, stroke)
    }

    private fun drawRefresh(canvas: Canvas) {
        val p1 = Path().apply {
            moveTo(20f, 10f)
            cubicTo(18f, 5f, 12f, 3.5f, 7.5f, 6f)
            cubicTo(4.8f, 7.4f, 3f, 10f, 3f, 13f)
        }
        val p2 = Path().apply {
            moveTo(4f, 14f)
            cubicTo(6f, 19f, 12f, 20.5f, 16.5f, 18f)
            cubicTo(19.2f, 16.6f, 21f, 14f, 21f, 11f)
        }
        canvas.drawPath(p1, stroke)
        canvas.drawPath(p2, stroke)
        canvas.drawLine(18f, 3f, 18f, 8f, stroke)
        canvas.drawLine(18f, 8f, 13f, 8f, stroke)
        canvas.drawLine(6f, 21f, 6f, 16f, stroke)
        canvas.drawLine(6f, 16f, 11f, 16f, stroke)
    }

    private fun drawShare(canvas: Canvas) {
        box.set(16f, 3f, 21f, 8f)
        canvas.drawOval(box, stroke)
        box.set(3f, 10f, 8f, 15f)
        canvas.drawOval(box, stroke)
        box.set(16f, 16f, 21f, 21f)
        canvas.drawOval(box, stroke)
        canvas.drawLine(8f, 12.5f, 16f, 7f, stroke)
        canvas.drawLine(8f, 12.5f, 16f, 17f, stroke)
    }

    private fun drawPulse(canvas: Canvas) {
        val path = Path().apply {
            moveTo(3f, 12f)
            lineTo(7f, 12f)
            lineTo(10f, 20f)
            lineTo(14f, 4f)
            lineTo(17f, 12f)
            lineTo(21f, 12f)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawBolt(canvas: Canvas) {
        val path = Path().apply {
            moveTo(13f, 2f)
            lineTo(4f, 13f)
            lineTo(11f, 13f)
            lineTo(10f, 22f)
            lineTo(20f, 10f)
            lineTo(13f, 10f)
            close()
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawEdit(canvas: Canvas) {
        val path = Path().apply {
            moveTo(5f, 18f)
            lineTo(6.5f, 20.5f)
            lineTo(9f, 19f)
            lineTo(18.5f, 9.5f)
            lineTo(15.5f, 6.5f)
            lineTo(6f, 16f)
            close()
        }
        canvas.drawPath(path, stroke)
        canvas.drawLine(14.8f, 5.2f, 18.8f, 9.2f, stroke)
    }

    private fun drawSpeaker(canvas: Canvas) {
        val path = Path().apply {
            moveTo(11f, 5f)
            lineTo(6f, 9f)
            lineTo(3f, 9f)
            lineTo(3f, 15f)
            lineTo(6f, 15f)
            lineTo(11f, 19f)
            close()
        }
        canvas.drawPath(path, stroke)
        canvas.drawArc(RectF(12f, 8f, 18f, 16f), -45f, 90f, false, stroke)
    }

    private fun drawSave(canvas: Canvas) {
        box.set(4f, 3f, 20f, 21f)
        canvas.drawRoundRect(box, 2f, 2f, stroke)
        canvas.drawLine(8f, 3f, 8f, 8f, stroke)
        canvas.drawLine(8f, 8f, 16f, 8f, stroke)
        canvas.drawLine(8f, 21f, 8f, 14f, stroke)
        canvas.drawLine(16f, 21f, 16f, 14f, stroke)
        canvas.drawLine(8f, 14f, 16f, 14f, stroke)
    }

    private fun drawBriefing(canvas: Canvas) {
        box.set(6f, 3f, 18f, 21f)
        canvas.drawRect(box, stroke)
        canvas.drawLine(9f, 7f, 15f, 7f, stroke)
        canvas.drawLine(9f, 11f, 15f, 11f, stroke)
        canvas.drawLine(9f, 15f, 12.5f, 15f, stroke)
    }

    private fun drawCheck(canvas: Canvas) {
        canvas.drawLine(5f, 12f, 10f, 17f, stroke)
        canvas.drawLine(10f, 17f, 19f, 7f, stroke)
    }

    private fun drawClose(canvas: Canvas) {
        canvas.drawLine(6f, 6f, 18f, 18f, stroke)
        canvas.drawLine(18f, 6f, 6f, 18f, stroke)
    }
}

class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("juhe_hotnews", Context.MODE_PRIVATE)

    fun ensureDefaults() {
        if (prefs.getString("sources", null) == null) {
            saveSources(defaultSources())
        } else {
            saveSources(sources())
        }
    }

    fun sources(): List<NewsSource> {
        val arr = JSONArray(prefs.getString("sources", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NewsSource(
                id = o.optString("id"),
                name = o.optString("name"),
                url = o.optString("url"),
                type = o.optString("type"),
                scope = o.optString("scope"),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }

    fun saveSources(sources: List<NewsSource>) {
        val arr = JSONArray()
        sources.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("url", it.url)
                put("type", it.type)
                put("scope", it.scope)
                put("enabled", it.enabled)
            })
        }
        val validIds = sources.map { it.id }.toSet()
        val keptDiagnostics = sourceDiagnostics().filter { it.sourceId in validIds }
        prefs.edit()
            .putString("sources", arr.toString())
            .putString("source_diagnostics", sourceDiagnosticsJson(keptDiagnostics))
            .apply()
    }

    fun exportSourcesJson(): String {
        val arr = JSONArray()
        sources().forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("url", it.url)
                put("type", it.type)
                put("scope", it.scope)
                put("enabled", it.enabled)
            })
        }
        return JSONObject()
            .put("version", 1)
            .put("app", "juhe-hotnews")
            .put("sources", arr)
            .toString(2)
    }

    fun cachedNews(): List<NewsItem> {
        val arr = JSONArray(prefs.getString("news_cache", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NewsItem(
                id = o.optString("id"),
                title = o.optString("title"),
                summary = o.optString("summary"),
                url = o.optString("url"),
                source = o.optString("source"),
                scope = o.optString("scope"),
                publishedAt = o.optString("publishedAt"),
                favorite = o.optBoolean("favorite", false),
                read = o.optBoolean("read", false)
            )
        }
    }

    fun saveNewsCache(items: List<NewsItem>) {
        val arr = JSONArray()
        items.take(NEWS_CACHE_LIMIT).forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("summary", it.summary)
                put("url", it.url)
                put("source", it.source)
                put("scope", it.scope)
                put("publishedAt", it.publishedAt)
                put("favorite", it.favorite)
                put("read", it.read)
            })
        }
        prefs.edit().putString("news_cache", arr.toString()).apply()
    }

    fun dailyReportArchives(): List<DailyReportArchive> {
        val arr = JSONArray(prefs.getString("daily_report_archives", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DailyReportArchive(
                id = o.optString("id"),
                title = o.optString("title"),
                createdAt = o.optString("createdAt"),
                content = o.optString("content")
            )
        }
    }

    fun saveDailyReportArchive(archive: DailyReportArchive) {
        val archives = (listOf(archive) + dailyReportArchives().filterNot { it.id == archive.id }).take(14)
        saveDailyReportArchives(archives)
    }

    fun deleteDailyReportArchive(id: String) {
        saveDailyReportArchives(dailyReportArchives().filterNot { it.id == id })
    }

    private fun saveDailyReportArchives(archives: List<DailyReportArchive>) {
        val arr = JSONArray()
        archives.take(14).forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("createdAt", it.createdAt)
                put("content", it.content)
            })
        }
        prefs.edit().putString("daily_report_archives", arr.toString()).apply()
    }

    fun sourceDiagnostics(): List<SourceDiagnostic> {
        val arr = JSONArray(prefs.getString("source_diagnostics", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SourceDiagnostic(
                sourceId = o.optString("sourceId"),
                sourceName = o.optString("sourceName"),
                checkedAt = o.optString("checkedAt"),
                success = o.optBoolean("success"),
                itemCount = o.optInt("itemCount"),
                message = o.optString("message")
            )
        }
    }

    fun saveSourceDiagnostics(diagnostics: List<SourceDiagnostic>) {
        val incomingIds = diagnostics.map { it.sourceId }.toSet()
        val merged = diagnostics + sourceDiagnostics().filterNot { it.sourceId in incomingIds }
        prefs.edit().putString("source_diagnostics", sourceDiagnosticsJson(merged.take(80))).apply()
    }

    private fun sourceDiagnosticsJson(diagnostics: List<SourceDiagnostic>): String {
        val arr = JSONArray()
        diagnostics.forEach {
            arr.put(JSONObject().apply {
                put("sourceId", it.sourceId)
                put("sourceName", it.sourceName)
                put("checkedAt", it.checkedAt)
                put("success", it.success)
                put("itemCount", it.itemCount)
                put("message", it.message)
            })
        }
        return arr.toString()
    }

    fun keywordFilter(): String = prefs.getString("keyword_filter", "") ?: ""

    fun saveKeywordFilter(value: String) {
        prefs.edit().putString("keyword_filter", value).apply()
    }

    fun feedMode(): String = prefs.getString("feed_mode", "all") ?: "all"

    fun saveFeedMode(value: String) {
        prefs.edit().putString("feed_mode", value).apply()
    }

    fun scopeFilter(): String = prefs.getString("scope_filter", "all") ?: "all"

    fun saveScopeFilter(value: String) {
        prefs.edit().putString("scope_filter", value).apply()
    }

    fun aiSettings() = AiSettings(
        endpoint = prefs.getString("ai_endpoint", null) ?: AiSettings().endpoint,
        apiKey = prefs.getString("ai_key", "") ?: "",
        model = prefs.getString("ai_model", null) ?: AiSettings().model,
        prompt = prefs.getString("ai_prompt", null) ?: AiSettings().prompt
    )

    fun saveAiSettings(settings: AiSettings) {
        prefs.edit()
            .putString("ai_endpoint", settings.endpoint)
            .putString("ai_key", settings.apiKey)
            .putString("ai_model", settings.model)
            .putString("ai_prompt", settings.prompt)
            .apply()
    }

    fun voiceSettings() = VoiceSettings(
        mode = prefs.getString("voice_mode", null) ?: VoiceSettings().mode,
        endpoint = prefs.getString("voice_endpoint", null) ?: VoiceSettings().endpoint,
        apiKey = prefs.getString("voice_key", "") ?: "",
        model = prefs.getString("voice_model", null) ?: VoiceSettings().model,
        voice = prefs.getString("voice_name", null) ?: VoiceSettings().voice
    )

    fun saveVoiceSettings(settings: VoiceSettings) {
        prefs.edit()
            .putString("voice_mode", settings.mode)
            .putString("voice_endpoint", settings.endpoint)
            .putString("voice_key", settings.apiKey)
            .putString("voice_model", settings.model)
            .putString("voice_name", settings.voice)
            .apply()
    }

    fun resetDefaultSources() {
        saveSources(defaultSources())
    }

    private fun defaultSources() = listOf(
        NewsSource("cctv-news", "央视网新闻", "https://news.cctv.com/2019/07/gaiban/cmsdatainterface/page/news_1.jsonp", "cctv_jsonp", "综合"),
        NewsSource("cctv-china", "央视网国内", "https://news.cctv.com/2019/07/gaiban/cmsdatainterface/page/china_1.jsonp", "cctv_jsonp", "国内"),
        NewsSource("cctv-world", "央视网国际", "https://news.cctv.com/2019/07/gaiban/cmsdatainterface/page/world_1.jsonp", "cctv_jsonp", "国际"),
        NewsSource("china-daily-cn", "中国日报 China", "https://www.chinadaily.com.cn/rss/china_rss.xml", "rss", "国内")
    )
}

class NewsRepository {
    fun fetch(sources: List<NewsSource>): List<NewsItem> {
        val result = fetchWithDiagnostics(sources)
        if (result.items.isEmpty()) {
            val errors = result.diagnostics.filterNot { it.success }.joinToString("；") { "${it.sourceName}: ${it.message}" }
            if (errors.isNotBlank()) error(errors)
        }
        return result.items
    }

    fun fetchWithDiagnostics(sources: List<NewsSource>): FetchResult {
        val checkedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(java.util.Date())
        val allItems = mutableListOf<NewsItem>()
        val diagnostics = sources.map { source ->
            runCatching {
                val fetched = when (source.type.lowercase(Locale.ROOT)) {
                    "cctv_jsonp" -> fetchCctvJsonp(source)
                    else -> fetchRss(source)
                }
                allItems += fetched
                SourceDiagnostic(
                    sourceId = source.id,
                    sourceName = source.name,
                    checkedAt = checkedAt,
                    success = true,
                    itemCount = fetched.size,
                    message = "抓取正常"
                )
            }.getOrElse {
                SourceDiagnostic(
                    sourceId = source.id,
                    sourceName = source.name,
                    checkedAt = checkedAt,
                    success = false,
                    itemCount = 0,
                    message = it.message ?: it.javaClass.simpleName
                )
            }
        }
        return FetchResult(allItems, diagnostics)
    }

    private fun fetchCctvJsonp(source: NewsSource): List<NewsItem> {
        val raw = httpGet(source.url)
        val jsonText = raw.substringAfter("(", raw).substringBeforeLast(")")
        val list = JSONObject(jsonText).getJSONObject("data").getJSONArray("list")
        return (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            val title = o.optString("title").trim()
            NewsItem(
                id = stableId(o.optString("url", title)),
                title = title,
                summary = o.optString("brief").trim(),
                url = o.optString("url"),
                source = source.name,
                scope = source.scope,
                publishedAt = o.optString("focus_date")
            )
        }.filter { it.title.isNotBlank() }
    }

    private fun fetchRss(source: NewsSource): List<NewsItem> {
        val parser = Xml.newPullParser()
        parser.setInput(httpGet(source.url).byteInputStream(), "UTF-8")
        val items = mutableListOf<NewsItem>()
        var inItem = false
        var tag = ""
        var title = ""
        var link = ""
        var desc = ""
        var date = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name ?: ""
                    if (tag.equals("item", true) || tag.equals("entry", true)) inItem = true
                }
                XmlPullParser.TEXT -> if (inItem) {
                    val text = parser.text?.trim().orEmpty()
                    when (tag.lowercase(Locale.ROOT)) {
                        "title" -> title += text
                        "link" -> link += text
                        "description", "summary" -> desc += text
                        "pubdate", "published", "updated" -> date += text
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name ?: ""
                    if (name.equals("item", true) || name.equals("entry", true)) {
                        if (title.isNotBlank()) {
                            items += NewsItem(stableId(link.ifBlank { title }), title, stripHtml(desc), link, source.name, source.scope, date)
                        }
                        inItem = false
                        tag = ""
                        title = ""
                        link = ""
                        desc = ""
                        date = ""
                    }
                }
            }
            event = parser.next()
        }
        return items
    }
}

object AiClient {
    fun critique(settings: AiSettings, script: String): String {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", settings.prompt))
                put(JSONObject().put("role", "user").put("content", script))
            })
            put("temperature", 0.7)
        }
        val raw = httpPostJson(chatCompletionsEndpoint(settings.endpoint), settings.apiKey, body)
        return JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content")
            .trim()
    }

    fun speech(context: Context, settings: VoiceSettings, text: String): File {
        if (usesChatAudio(settings)) {
            return speechFromChatAudio(context, settings, text)
        }
        val body = JSONObject().apply {
            put("model", settings.model)
            put("input", text)
            put("voice", settings.voice)
            put("response_format", "mp3")
        }
        val conn = (URL(settings.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setAuthHeader(settings.endpoint, settings.apiKey)
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
            error("HTTP ${conn.responseCode}: $err")
        }
        val file = File(context.cacheDir, "news-speech-${System.currentTimeMillis()}.mp3")
        conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        return file
    }

    private fun speechFromChatAudio(context: Context, settings: VoiceSettings, text: String): File {
        val format = "wav"
        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "请用清晰、克制、自然的中文新闻播报语气朗读。")
                })
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                })
            })
            put("audio", JSONObject().apply {
                put("format", format)
                put("voice", settings.voice.ifBlank { "mimo_default" })
            })
        }
        val raw = httpPostJson(chatCompletionsEndpoint(settings.endpoint), settings.apiKey, body)
        val message = JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
        val audio = message.optJSONObject("audio") ?: error("响应中缺少 audio 字段")
        val data = audio.optString("data").ifBlank { error("响应中缺少音频数据") }
        val file = File(context.cacheDir, "news-speech-${System.currentTimeMillis()}.$format")
        file.writeBytes(Base64.decode(data, Base64.DEFAULT))
        return file
    }

    private fun usesChatAudio(settings: VoiceSettings): Boolean {
        val endpoint = settings.endpoint.lowercase(Locale.ROOT)
        val model = settings.model.lowercase(Locale.ROOT)
        return "xiaomimimo.com" in endpoint || model.startsWith("mimo-") || endpoint.contains("/chat/completions")
    }
}

fun httpGet(url: String): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 20000
        setRequestProperty("User-Agent", "JuheHotNews/0.1 Android")
    }
    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
    return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
}

fun httpPostJson(url: String, apiKey: String, body: JSONObject): String {
    val payload = body.toString().toByteArray(Charsets.UTF_8)
    return runCatching {
        postJsonOnce(url, apiKey, payload)
    }.getOrElse { first ->
        val retryUrl = mimoTokenPlanRetryUrl(url) ?: throw first
        Log.w(LOG_TAG, "Retrying MiMo Token Plan request with resolved ALB endpoint after ${first.javaClass.simpleName}")
        runCatching {
            postJsonOnce(retryUrl, apiKey, payload, hostOverride = URL(url).host)
        }.getOrElse { second ->
            second.addSuppressed(first)
            throw second
        }
    }
}

private fun postJsonOnce(url: String, apiKey: String, payload: ByteArray, hostOverride: String? = null): String {
    if (hostOverride != null) {
        return rawHttpsPostJson(URL(url), hostOverride, apiKey, payload)
    }
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 20000
        readTimeout = 60000
        doOutput = true
        setAuthHeader(url, apiKey)
        setRequestProperty("Content-Type", "application/json")
    }
    conn.outputStream.use { it.write(payload) }
    if (conn.responseCode !in 200..299) {
        val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
        error("HTTP ${conn.responseCode}: $err")
    }
    return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
}

private fun rawHttpsPostJson(url: URL, host: String, apiKey: String, payload: ByteArray): String {
    require(url.protocol == "https") { "MiMo Token Plan retry only supports https" }
    val port = if (url.port > 0) url.port else 443
    val path = buildString {
        append(url.path.ifBlank { "/" })
        if (!url.query.isNullOrBlank()) append("?").append(url.query)
    }
    val rawSocket = Socket().apply {
        connect(InetSocketAddress(url.host, port), 20000)
        soTimeout = 60000
    }
    val sslSocket = (SSLContext.getDefault().socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket).apply {
        sslParameters = sslParameters.apply {
            serverNames = listOf(SNIHostName(host))
        }
        startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
            close()
            error("TLS hostname verification failed for $host")
        }
    }
    sslSocket.use { socket ->
        val authHeader = if (authMode(host, apiKey) == ApiAuthMode.API_KEY) {
            "api-key: $apiKey"
        } else {
            "Authorization: Bearer $apiKey"
        }
        val head = buildString {
            append("POST $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Content-Type: application/json\r\n")
            append("Accept: application/json\r\n")
            append("$authHeader\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        val output = socket.outputStream
        output.write(head)
        output.write(payload)
        output.flush()
        val response = ByteArrayOutputStream()
        socket.inputStream.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                response.write(buf, 0, n)
            }
        }
        return parseHttpResponse(response.toByteArray())
    }
}

private fun parseHttpResponse(response: ByteArray): String {
    val sep = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    val splitAt = response.indexOf(sep)
    if (splitAt < 0) error("Invalid HTTP response")
    val headerText = response.copyOfRange(0, splitAt).toString(Charsets.ISO_8859_1)
    val bodyBytes = response.copyOfRange(splitAt + sep.size, response.size)
    val statusLine = headerText.lineSequence().firstOrNull().orEmpty()
    val status = Regex("HTTP/\\d(?:\\.\\d)?\\s+(\\d+)").find(statusLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: error("Invalid HTTP status: $statusLine")
    val chunked = headerText.lineSequence().any {
        it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.contains("chunked", ignoreCase = true)
    }
    val body = if (chunked) decodeChunked(bodyBytes) else bodyBytes
    val text = body.toString(Charsets.UTF_8)
    if (status !in 200..299) error("HTTP $status: $text")
    return text
}

private fun ByteArray.indexOf(pattern: ByteArray): Int {
    outer@ for (i in 0..(size - pattern.size)) {
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) continue@outer
        }
        return i
    }
    return -1
}

private fun decodeChunked(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var pos = 0
    while (pos < bytes.size) {
        val lineEnd = bytes.indexOfCrlf(pos)
        if (lineEnd < 0) break
        val sizeText = bytes.copyOfRange(pos, lineEnd).toString(Charsets.ISO_8859_1).substringBefore(";").trim()
        val chunkSize = sizeText.toIntOrNull(16) ?: error("Invalid chunk size: $sizeText")
        pos = lineEnd + 2
        if (chunkSize == 0) break
        if (pos + chunkSize > bytes.size) error("Invalid chunked response")
        out.write(bytes, pos, chunkSize)
        pos += chunkSize + 2
    }
    return out.toByteArray()
}

private fun ByteArray.indexOfCrlf(start: Int): Int {
    for (i in start until size - 1) {
        if (this[i] == '\r'.code.toByte() && this[i + 1] == '\n'.code.toByte()) return i
    }
    return -1
}

private fun mimoTokenPlanRetryUrl(url: String): String? {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return null
    if (!parsed.host.equals("token-plan-sgp.xiaomimimo.com", ignoreCase = true)) return null
    val target = TOKEN_PLAN_ALB_ENDPOINTS.firstOrNull { host ->
        runCatching {
            val address = InetAddress.getByName(host).hostAddress.orEmpty()
            address.isNotBlank() && !address.startsWith("198.18.")
        }.getOrDefault(false)
    } ?: return null
    val port = if (parsed.port > 0) ":${parsed.port}" else ""
    val query = parsed.query?.let { "?$it" }.orEmpty()
    return "${parsed.protocol}://$target$port${parsed.path}$query"
}

private val TOKEN_PLAN_ALB_ENDPOINTS = listOf(
    "8.222.143.90",
    "47.84.2.69",
    "47.245.105.117",
    "47.84.235.191",
    "47.237.8.234",
    "47.236.158.11",
    "47.236.158.71",
    "8.222.147.102",
    "mimo-pri-alisgp.alb.xiaomi.com"
)

private fun chatCompletionsEndpoint(endpoint: String): String {
    val trimmed = endpoint.trim().removeSuffix("/")
    if (trimmed.endsWith("/chat/completions")) return trimmed
    val base = if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
    return "$base/chat/completions"
}

private fun HttpURLConnection.setAuthHeader(url: String, apiKey: String) {
    if (authMode(url, apiKey) == ApiAuthMode.API_KEY) {
        setRequestProperty("api-key", apiKey)
    } else {
        setRequestProperty("Authorization", "Bearer $apiKey")
    }
}

private fun authMode(url: String, apiKey: String): ApiAuthMode {
    val lowerUrl = url.lowercase(Locale.ROOT)
    return if ("xiaomimimo.com" in lowerUrl || apiKey.startsWith("tp-")) {
        ApiAuthMode.API_KEY
    } else {
        ApiAuthMode.BEARER
    }
}

fun stripHtml(value: String): String = value
    .replace(Regex("<[^>]*>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .trim()

fun stableId(value: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private class PaperBackgroundLayout(
    context: Context,
    private val paperColor: Int,
    lineColor: Int,
    goldColor: Int
) : LinearLayout(context) {
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperColor
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(18, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))
        strokeWidth = 1f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(
                Color.argb(58, Color.red(goldColor), Color.green(goldColor), Color.blue(goldColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)
        canvas.save()
        canvas.translate(width * 0.85f, height * 0.03f)
        canvas.scale(width * 0.35f, width * 0.35f)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()

        val step = dpLocal(24)
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
        super.onDraw(canvas)
    }

    private fun dpLocal(value: Int): Float = value * resources.displayMetrics.density
}

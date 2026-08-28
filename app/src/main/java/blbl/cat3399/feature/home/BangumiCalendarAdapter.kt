package blbl.cat3399.feature.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.BangumiCalendarDay
import blbl.cat3399.core.model.BangumiCalendarItem
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ItemBangumiCalendarHeaderBinding
import blbl.cat3399.databinding.ItemBangumiFollowBinding
import java.util.Locale

/**
 * Bangumi 星期视图多类型适配器:
 * - Header 行:周一~周日分组标题(跨整行,不可聚焦)
 * - 卡片行:番剧卡片(复用 item_bangumi_follow 布局,聚焦可点击)
 */
class BangumiCalendarAdapter(
    private val onClick: (position: Int, item: BangumiCalendarItem) -> Unit,
    /** 长按复制源标题回调(item 为原始数据,非截断显示标题) */
    private val onLongClick: (BangumiCalendarItem) -> Unit = {},
    /** 是否显示分集信息(仅 TV动画 true;剧场动画/日剧/电影 false) */
    private val showEpisodeText: Boolean = true,
    /** 是否显示流派 tag(动画 true;日剧/电影 false) */
    private val showTags: Boolean = true,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val day: BangumiCalendarDay) : Row
        data class Card(val item: BangumiCalendarItem) : Row
    }

    private val rows = ArrayList<Row>()

    init {
        setHasStableIds(true)
    }

    fun submit(days: List<BangumiCalendarDay>) {
        rows.clear()
        for (day in days) {
            rows += Row.Header(day)
            day.items.forEach { rows += Row.Card(it) }
        }
        notifyDataSetChanged()
    }

    /** 该 position 是否为分组标题行(跨整行、不可聚焦) */
    fun isHeaderPosition(position: Int): Boolean {
        return position in rows.indices && rows[position] is Row.Header
    }

    /** 第一个可聚焦的卡片位置;无卡片时返回 null */
    fun firstCardPosition(): Int? {
        for (i in rows.indices) {
            if (rows[i] is Row.Card) return i
        }
        return null
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Card -> TYPE_CARD
        }
    }

    override fun getItemId(position: Int): Long {
        return when (val row = rows[position]) {
            is Row.Header -> -(row.day.weekdayId.toLong() + 1_000L)
            is Row.Card -> row.item.id
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context).cloneInUserScale(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVh(ItemBangumiCalendarHeaderBinding.inflate(inflater, parent, false))
            else -> CardVh(ItemBangumiFollowBinding.inflate(inflater, parent, false), showEpisodeText, showTags, onLongClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVh).bind(row.day)
            is Row.Card -> (holder as CardVh).bind(row.item, onClick)
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderVh(private val binding: ItemBangumiCalendarHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(day: BangumiCalendarDay) {
            // 年份模式:自定义 header(如 "2026 年 12 部");季度模式:默认统计 "68 部 · 放送 07-04 起"
            val meta =
                if (!day.headerTextOverride.isNullOrBlank()) {
                    day.headerTextOverride
                } else {
                    buildList {
                        add("${day.items.size} 部")
                        day.items.firstOrNull()?.airDate?.takeIf { it.length >= 10 }?.let { add("放送 ${it.substring(5)} 起") }
                    }.joinToString(" · ")
                }
            binding.tvMeta.text = meta
            binding.tvMeta.isVisible = meta.isNotBlank()
        }
    }

    class CardVh(
        private val binding: ItemBangumiFollowBinding,
        private val showEpisodeText: Boolean,
        private val showTags: Boolean,
        private val onLongClick: (BangumiCalendarItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BangumiCalendarItem, onClick: (position: Int, item: BangumiCalendarItem) -> Unit) {
            // 显示标题:name_cn 为空且原名含" - "(主标题-副标题)时只显示主标题,卡片更简洁
            // (复制/搜索仍用未截断的 searchKeyword 源标题)
            val displayTitle =
                if (item.nameCn.isBlank() && item.name.contains(" - ")) item.name.substringBefore(" - ").trim()
                else item.searchKeyword
            binding.tvTitle.text = displayTitle

            // 评分徽章:右上角;>=7 分金色醒目,<7 分半透明低调
            val score = item.score
            if (score != null) {
                binding.tvAccessBadgeText.visibility = View.VISIBLE
                binding.tvAccessBadgeText.text = String.format(Locale.US, "%.1f", score)
                binding.tvAccessBadgeText.setBackgroundResource(
                    if (score >= 7.0) R.drawable.bg_score_badge_highlight else R.drawable.bg_score_badge_normal,
                )
            } else {
                binding.tvAccessBadgeText.visibility = View.GONE
            }

            // 流派标签:左上角,最多 2 个(日剧/电影不显示)
            val tags = if (showTags) item.tags else emptyList()
            if (tags.isEmpty()) {
                binding.llTags.visibility = View.GONE
                binding.tvTag1.visibility = View.GONE
                binding.tvTag2.visibility = View.GONE
            } else {
                binding.llTags.visibility = View.VISIBLE
                binding.tvTag1.visibility = View.VISIBLE
                binding.tvTag1.text = tags.getOrNull(0).orEmpty()
                val tag2 = tags.getOrNull(1)
                if (tag2.isNullOrEmpty()) {
                    binding.tvTag2.visibility = View.GONE
                } else {
                    binding.tvTag2.visibility = View.VISIBLE
                    binding.tvTag2.text = tag2
                }
            }

            // 分集信息:封面左下角(6/12集 或 12集;当季有进度,历史仅总话数;剧场动画/电影不显示)
            if (showEpisodeText) {
                val episodeText = item.episodeText
                if (episodeText.isNullOrEmpty()) {
                    binding.tvEpisodeText.isVisible = false
                } else {
                    binding.tvEpisodeText.isVisible = true
                    binding.tvEpisodeText.text = episodeText
                }
            } else {
                binding.tvEpisodeText.isVisible = false
            }
            binding.tvSubtitle.isVisible = false

            ImageLoader.loadInto(binding.ivCover, ImageUrl.poster(item.coverUrl))
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onClick(pos, item)
            }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CARD = 1
    }
}

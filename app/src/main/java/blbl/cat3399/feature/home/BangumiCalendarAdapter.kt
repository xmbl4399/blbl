package blbl.cat3399.feature.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.BangumiCalendarDay
import blbl.cat3399.core.model.BangumiCalendarItem
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ItemBangumiCalendarHeaderBinding
import blbl.cat3399.databinding.ItemBangumiFollowBinding

/**
 * Bangumi 星期视图多类型适配器:
 * - Header 行:周一~周日分组标题(跨整行,不可聚焦)
 * - 卡片行:番剧卡片(复用 item_bangumi_follow 布局,聚焦可点击)
 */
class BangumiCalendarAdapter(
    private val onClick: (item: BangumiCalendarItem) -> Unit,
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
            else -> CardVh(ItemBangumiFollowBinding.inflate(inflater, parent, false))
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
            binding.tvWeekday.text = day.weekdayCn
            val meta = buildList {
                add("${day.items.size} 部")
                day.items.firstOrNull()?.airDate?.takeIf { it.length >= 10 }?.let { add("放送 ${it.substring(5)} 起") }
            }.joinToString(" · ")
            binding.tvMeta.text = meta
            binding.tvMeta.isVisible = meta.isNotBlank()
        }
    }

    class CardVh(private val binding: ItemBangumiFollowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BangumiCalendarItem, onClick: (BangumiCalendarItem) -> Unit) {
            binding.tvTitle.text = item.searchKeyword
            binding.tvAccessBadgeText.isVisible = false

            val metaParts =
                buildList {
                    item.airDate?.takeIf { it.length >= 10 }?.let { add("放送 ${it.substring(5)}") }
                    item.score?.let { add("评分 ${"%.1f".format(it)}") }
                    item.rank?.let { add("No.${it}") }
                }
            val subtitle = metaParts.joinToString(" · ")
            binding.tvSubtitle.text = subtitle
            binding.tvSubtitle.isVisible = subtitle.isNotBlank()

            ImageLoader.loadInto(binding.ivCover, ImageUrl.poster(item.coverUrl))
            binding.root.setOnClickListener {
                onClick(item)
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CARD = 1
    }
}

package com.kealart.bfdiscount

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DiscountItem(val ean: String, val article: String, val codeIndex: Int, val name: String) {
    val discount: String get() = when (codeIndex) {
        0 -> "70%"; 1 -> "50%"; 2 -> "80%"; 3 -> "Flat ₹99"; 4 -> "90%"; 5 -> "Buy 3 Get 1"
        else -> "No discount"
    }
    val hasDiscount: Boolean get() = codeIndex in 0..5
}
data class PrefixResult(val items: List<DiscountItem>, val total: Int)

class DataRepository private constructor(
    private val byEan: HashMap<String, DiscountItem>,
    private val byArticle: HashMap<String, DiscountItem>,
    private val eanSorted: List<Pair<String, DiscountItem>>,
    private val articleSorted: List<Pair<String, DiscountItem>>
) {
    fun exact(number: String): DiscountItem? {
        val key = normalize(number)
        return byEan[key] ?: byArticle[key]
    }
    fun prefix(query: String, byArticleNumber: Boolean): PrefixResult {
        val q = normalize(query)
        if (q.length < 3) return PrefixResult(emptyList(), 0)
        val source = if (byArticleNumber) articleSorted else eanSorted
        var low = 0; var high = source.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (source[mid].first < q) low = mid + 1 else high = mid
        }
        val found = ArrayList<DiscountItem>(40)
        var total = 0; var i = low
        while (i < source.size && source[i].first.startsWith(q)) {
            if (found.size < 40) found.add(source[i].second)
            total++; i++
        }
        return PrefixResult(found, total)
    }
    companion object {
        fun normalize(value: String): String = value.filter(Char::isDigit).trimStart('0').ifEmpty { "0" }
        suspend fun load(context: Context): DataRepository = withContext(Dispatchers.IO) {
            val root = JSONObject(context.assets.open("discounts.json").bufferedReader().use { it.readText() })
            val rows = root.getJSONArray("rows")
            val eanMap = HashMap<String, DiscountItem>(rows.length() * 2)
            val articleMap = HashMap<String, DiscountItem>(rows.length() * 2)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                val ean = normalize(row.optString(0)); val article = normalize(row.optString(1))
                val item = DiscountItem(ean, article, row.optInt(2, -1), row.optString(3))
                if (ean != "0") eanMap.putIfAbsent(ean, item)
                if (article != "0") articleMap.putIfAbsent(article, item)
            }
            val eanSorted = eanMap.entries.map { it.key to it.value }.sortedBy { it.first }
            val articleSorted = articleMap.entries.map { it.key to it.value }.sortedBy { it.first }
            DataRepository(eanMap, articleMap, eanSorted, articleSorted)
        }
    }
}

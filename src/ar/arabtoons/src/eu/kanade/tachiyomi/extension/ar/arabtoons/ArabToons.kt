package eu.kanade.tachiyomi.extension.ar.arabtoons

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.textOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ArabToons : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 3, period = 1.seconds)

    // ========================= Popular =========================

    // The site renders "browse" client-side, so the most-viewed section of the home page is the
    // only server-rendered popularity ranking.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()
        val section = document.select("h2, h3")
            .firstOrNull { it.text().contains("الأكثر مشاهدة") }
            ?.closest("section")
            ?: document.selectFirst("main")
            ?: document
        return MangasPage(section.parseMangaEntries(), false)
    }

    // ========================= Latest =========================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/latest-releases?page=$page").asJsoup()
        val entries = (document.selectFirst("main") ?: document).parseMangaEntries()
        val hasNextPage = document.selectFirst("a[href*=page=${page + 1}]") != null
        return MangasPage(entries, hasNextPage)
    }

    // ========================= Search =========================

    // The browse/search results are fetched by client-side JavaScript, so the search runs
    // over the server-rendered sitemaps (slug based) instead.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getLatestUpdates(page)

        val tokens = query.normalizeForSearch().split(' ').filter { it.isNotEmpty() }
        val index = client.get("$baseUrl/sitemap.xml").asJsoup(Parser.xmlParser())
        val sitemaps = index.select("sitemap > loc").map { it.text() }

        val paths = coroutineScope {
            sitemaps.map { sitemap ->
                async {
                    client.get(sitemap).asJsoup(Parser.xmlParser()).select("url > loc").map { it.text() }
                }
            }.awaitAll().flatten()
        }
            .mapNotNull { it.toMangaPathOrNull() }
            .distinct()
            .filter { path ->
                val haystack = path.slug().decoded().normalizeForSearch()
                tokens.all { haystack.contains(it) }
            }

        val from = (page - 1) * SEARCH_PAGE_SIZE
        val entries = paths.drop(from).take(SEARCH_PAGE_SIZE).map { path ->
            SManga.create().apply {
                url = path
                title = path.slug().decoded().toTitle()
            }
        }
        return MangasPage(entries, paths.size > from + SEARCH_PAGE_SIZE)
    }

    // ==================== Details & Chapters ====================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url.normalizeMangaUrl()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.toString().toMangaPathOrNull() ?: return null
        return client.get(baseUrl + path).asJsoup().toSManga(path)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val path = manga.url.normalizeMangaUrl()
        val document = client.get(baseUrl + path).asJsoup()
        return SMangaUpdate(document.toSManga(path), document.parseChapters())
    }

    private fun Document.toSManga(path: String): SManga = SManga.create().apply {
        url = path
        title = selectFirst("h1")?.text() ?: path.slug().decoded().toTitle()
        thumbnail_url = selectFirst("img[src*=/storage/covers/]")?.absUrl("src")

        select("dl > div").forEach { row ->
            val value = row.selectFirst("dd")?.textOrNull() ?: return@forEach
            when (row.selectFirst("dt")?.text()) {
                "المؤلف" -> author = value
                "الرسام" -> artist = value
            }
        }

        description = buildString {
            selectFirst("p[id^=manga-description]")?.wholeText()?.trim()?.let(::append)
            selectFirst("p[aria-label='العنوان البديل']")?.textOrNull()?.let {
                if (isNotEmpty()) append("\n\n")
                append("أسماء أخرى: ", it)
            }
        }.ifEmpty { null }

        genre = (select("span.meta-badge[class*=type-]") + select("#manga-genres a"))
            .map { it.text() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString()
            .ifEmpty { null }

        val statusBadge = selectFirst("span.meta-badge[class*=status-]")
        status = parseStatus(statusBadge?.className().orEmpty(), statusBadge?.text().orEmpty())
    }

    private fun parseStatus(className: String, text: String): Int = when {
        className.contains("ongoing") || text.contains("مستمر") -> SManga.ONGOING
        className.contains("complete") || text.contains("مكتمل") -> SManga.COMPLETED
        className.contains("hiatus") || text.contains("متوقف") -> SManga.ON_HIATUS
        className.contains("cancel") || text.contains("ملغ") -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun Document.parseChapters(): List<SChapter> = select("li.chapter-item a[href]").map { link ->
        SChapter.create().apply {
            url = link.absUrl("href").toHttpUrl().encodedPath
            name = link.selectFirst("p")?.text() ?: url.substringAfterLast('/').decoded()
            chapter_number = NUMBER_REGEX.find(name)?.value?.toFloatOrNull() ?: -1f
            date_upload = parseRelativeDate(link.selectFirst("span")?.text())
        }
    }

    // ========================= Pages =========================

    // Only the first page image is rendered into the HTML; the rest come from the page payload,
    // so every image URL of the chapter is collected straight from the raw response.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val html = client.get(baseUrl + chapter.url).use { it.body.string() }
            .replace("\\u002F", "/")
            .replace("\\/", "/")

        val images = IMAGE_REGEX.findAll(html)
            .map { it.value.let { value -> if (value.startsWith("http")) value else baseUrl + value } }
            .distinct()
            .toList()

        val pages = images.groupBy { it.substringBeforeLast('/') }.values.maxByOrNull { it.size }
            ?: throw Exception("لم يتم العثور على صور الفصل")

        return pages.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    // ========================= Utilities =========================

    private fun Element.parseMangaEntries(): List<SManga> {
        val titles = LinkedHashMap<String, String?>()
        val covers = HashMap<String, String>()
        select("a[href*=/manga/]").forEach { link ->
            val path = link.absUrl("href").toMangaPathOrNull() ?: return@forEach
            val image = link.selectFirst("img")

            if (path !in covers && image != null) {
                image.absUrl("src").ifEmpty { image.absUrl("data-src") }
                    .takeIf { it.contains("/storage/covers/") }
                    ?.let { covers[path] = it }
            }

            val title = link.attrOrNull("title")
                ?: link.selectFirst("h3, h4")?.textOrNull()
                ?: image?.attrOrNull("alt")
            if (titles[path] == null) titles[path] = title
        }
        return titles.map { (path, title) ->
            SManga.create().apply {
                url = path
                this.title = title ?: path.slug().decoded().toTitle()
                thumbnail_url = covers[path]
            }
        }
    }

    private fun String.toMangaPathOrNull(): String? {
        val path = parseUrlOrNull()?.encodedPath ?: return null
        return path.trimEnd('/').takeIf { MANGA_PATH_REGEX.matches(it) }
    }

    private fun String.parseUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    // Entries saved by the old WordPress version were stored as "/manga/slug/".
    private fun String.normalizeMangaUrl(): String = "/manga/" + trim('/').removePrefix("manga/").substringBefore('/')

    private fun String.slug(): String = trim('/').substringAfter("manga/").substringBefore('/')

    private fun String.decoded(): String = URLDecoder.decode(this, "UTF-8")

    private fun String.toTitle(): String = replace('-', ' ').split(' ')
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ENGLISH) } }

    private fun String.normalizeForSearch(): String = lowercase()
        .replace(NON_WORD_REGEX, " ")
        .replace(SPACES_REGEX, " ")
        .trim()

    private fun parseRelativeDate(text: String?): Long {
        val match = RELATIVE_DATE_REGEX.find(text ?: return 0L) ?: return 0L
        val unit = match.groupValues[2]
        val amount = match.groupValues[1].toLongOrNull()
            ?: if (unit.endsWith("ين")) 2L else 1L
        val millis = when {
            unit.startsWith("ثاني") -> 1_000L
            unit.startsWith("دقيق") || unit.startsWith("دقائق") -> 60_000L
            unit.startsWith("ساع") -> 3_600_000L
            unit.startsWith("يوم") || unit.startsWith("أيام") || unit.startsWith("ايام") -> 86_400_000L
            unit.startsWith("أسبوع") || unit.startsWith("اسبوع") || unit.startsWith("أسابيع") -> 604_800_000L
            unit.startsWith("شهر") || unit.startsWith("أشهر") || unit.startsWith("شهور") -> 2_592_000_000L
            unit.startsWith("سن") || unit.startsWith("عام") || unit.startsWith("أعوام") -> 31_536_000_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - amount * millis
    }

    companion object {
        private const val SEARCH_PAGE_SIZE = 20
        private val MANGA_PATH_REGEX = Regex("^/manga/[^/]+$")
        private val NUMBER_REGEX = Regex("""\d+(\.\d+)?""")
        private val NON_WORD_REGEX = Regex("""[^\p{L}\p{N}\s]""")
        private val SPACES_REGEX = Regex("""\s+""")
        private val IMAGE_REGEX = Regex("""(?:https://arabtoons\.net)?/storage/mangas/[^"'\s\\<>)]+?\.(?:webp|jpe?g|png|avif)""")
        private val RELATIVE_DATE_REGEX = Regex("""منذ\s*(\d+)?\s*([^\s\d]+)""")
    }
}

package com.persianrubinfilm.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

private val background = Color(0xFF101015)
private val panel = Color(0xFF24242D)
private val accent = Color(0xFFE63B45)
private const val site = "https://farsiplex.com"

private data class TitleItem(val title: String, val page: String, val poster: String?, val series: Boolean)
private data class EpisodeItem(val name: String, val page: String, val season: Int, val number: Int)
private enum class Tab { Home, Movies, Series, History, Favorites }

private fun sameSite(url: String): Boolean = try {
    val uri = URI(url)
    uri.scheme == "https" && (uri.host == "farsiplex.com" || uri.host == "www.farsiplex.com")
} catch (_: Exception) { false }

private fun cleanImage(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
private fun imageOf(anchor: Element, doc: Document): String? {
    val container = anchor.closest("article, li, .item, .movie, .tvshow, .card, .poster, .owl-item")
    val img = (container?.selectFirst("img") ?: anchor.selectFirst("img")
        ?: anchor.parent()?.selectFirst("img") ?: anchor.parent()?.parent()?.selectFirst("img"))
    val raw = img?.let {
        sequenceOf("data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "src")
            .mapNotNull { key -> cleanImage(it.attr(key)) }
            .firstOrNull()
    }?.substringBefore(",")?.trim()?.substringBefore(" ")
    return raw?.let { doc.location().let { base -> URI(base).resolve(it).toString() } }
}

private fun extractTitles(doc: Document, series: Boolean): List<TitleItem> {
    val type = if (series) "tvshow" else "movie"
    val result = linkedMapOf<String, TitleItem>()
    for (link in doc.select("a[href]")) {
        val url = link.absUrl("href").substringBefore('?').trimEnd('/') + "/"
        if (!sameSite(url) || !url.contains("/$type/") || url == "$site/$type/") continue
        val fromHeading = link.selectFirst("h2,h3,h4")?.text()
            ?: link.closest("h2,h3,h4")?.text()
        val title = (fromHeading ?: link.attr("title").ifBlank { link.text() }).trim()
            .ifBlank { link.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
        val invalidTitles = setOf("genres", "genre", "all genres", "movies", "movie", "series", "tv shows", "tv show", "play", "view", "more", "watch", "home", "browse")
        val slug = url.removeSuffix("/").substringAfterLast('/').lowercase()
        if (title.isBlank() || title.length > 110 || title.lowercase() in invalidTitles || slug in invalidTitles) continue
        // A catalog entry needs a real title or poster, not a menu/navigation link.
        val hasTitleEvidence = fromHeading != null || link.selectFirst("img") != null ||
            link.closest("article, .item, .movie, .tvshow, .card, .poster, .owl-item") != null
        if (!hasTitleEvidence) continue
        val poster = imageOf(link, doc)
        val old = result[url]
        if (old == null || (old.poster == null && poster != null)) result[url] = TitleItem(title, url, poster, series)
    }
    return result.values.toList()
}

private suspend fun readPage(url: String): Document = withContext(Dispatchers.IO) {
    Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        .timeout(20000).followRedirects(true).get()
}

private suspend fun readCatalog(series: Boolean): List<TitleItem> = withContext(Dispatchers.IO) {
    val base = "$site/${if (series) "tvshow" else "movie"}/"
    val found = linkedMapOf<String, TitleItem>()
    val pending = ArrayDeque<String>()
    val seen = mutableSetOf<String>()
    pending.add(base)
    // The site currently publishes these catalogs on a single page. Follow real pagination links if added.
    while (pending.isNotEmpty() && seen.size < 12) {
        val page = pending.removeFirst()
        if (!seen.add(page)) continue
        val doc = try { readPage(page) } catch (_: Exception) { continue }
        for (item in extractTitles(doc, series)) {
            val previous = found[item.page]
            if (previous == null || (previous.poster == null && item.poster != null)) found[item.page] = item
        }
        for (a in doc.select(".pagination a[href], .page-numbers[href], nav.pagination a[href]")) {
            val next = a.absUrl("href")
            if (sameSite(next) && next.startsWith(base) && next !in seen && next !in pending) pending.add(next)
        }
    }
    found.values.toList()
}

private suspend fun readEpisodes(seriesUrl: String): List<EpisodeItem> = withContext(Dispatchers.IO) {
    val doc = readPage(seriesUrl)
    val found = linkedMapOf<String, EpisodeItem>()
    for (a in doc.select("a[href*=/episode/]")) {
        val url = a.absUrl("href").substringBefore('?').trimEnd('/') + "/"
        if (!sameSite(url) || url == "$site/episode/") continue
        val context = (a.closest("li, article, .item, .episode, .episod, .seasons-episodes, .season")?.text()
            ?: a.parent()?.text() ?: a.text()).take(300)
        val nearby = "$context ${a.attr("title")} ${a.text()} ${url.substringAfterLast('/', "")}".lowercase()
        val combined = Regex("(?:season\\s*([0-9]+)|s([0-9]{1,2})\\s*e[0-9]+|([0-9]+)\\s*[-x]\\s*[0-9]+)")
            .find(nearby)
        val season = combined?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()
            ?: Regex("(?:season|se|s)\\s*0?([0-9]{1,2})", RegexOption.IGNORE_CASE).find(nearby)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1
        val ep = Regex("(?:episode|ep|e)\\s*0*([0-9]+)", RegexOption.IGNORE_CASE).find(nearby)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("[0-9]+\\s*[-x]\\s*([0-9]+)").find(nearby)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
        if (ep == 0) continue
        found[url] = EpisodeItem("Episode $ep", url, season, ep)
    }
    found.values.sortedWith(compareBy(EpisodeItem::season, EpisodeItem::number))
}

private class FarsiStore(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("robinpersianfilm", 0)
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    fun watched(): Set<String> = prefs.getStringSet("watched", emptySet())?.toSet() ?: emptySet()
    fun favorite(id: String) { val s = favorites().toMutableSet(); if (!s.add(id)) s.remove(id); prefs.edit().putStringSet("favorites", s).apply() }
    fun mark(id: String) { prefs.edit().putStringSet("watched", watched() + id).apply() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = background, surface = panel)) { RobinApp() } }
    }
}

@Composable
private fun RobinApp() {
    val context = LocalContext.current
    val store = remember { FarsiStore(context) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.Home) }
    var search by remember { mutableStateOf("") }
    var movies by remember { mutableStateOf<List<TitleItem>>(emptyList()) }
    var series by remember { mutableStateOf<List<TitleItem>>(emptyList()) }
    var selected by remember { mutableStateOf<TitleItem?>(null) }
    var chosenEpisode by remember { mutableStateOf<EpisodeItem?>(null) }
    var episodeList by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var chosenSeason by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var playerUrl by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(store.favorites()) }
    var watched by remember { mutableStateOf(store.watched()) }
    fun closeDetails() { playerUrl = null; selected = null; chosenEpisode = null; episodeList = emptyList() }
    BackHandler(playerUrl != null || selected != null) {
        if (playerUrl != null) playerUrl = null else closeDetails()
    }
    LaunchedEffect(Unit) {
        loading = true
        val m = try { readCatalog(false) } catch (_: Exception) { emptyList() }
        val s = try { readCatalog(true) } catch (_: Exception) { emptyList() }
        movies = m; series = s; loading = false
        if (m.isEmpty() && s.isEmpty()) message = "Could not load FarsiPlex. Check your connection and try Refresh."
    }
    LaunchedEffect(selected?.page) {
        val item = selected
        if (item != null && item.series) {
            episodeList = try { readEpisodes(item.page) } catch (_: Exception) { emptyList() }
            chosenSeason = episodeList.firstOrNull()?.season ?: 1
            chosenEpisode = null
        }
    }
    Column(Modifier.fillMaxSize().background(background).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("RobinPersianFilm", color = accent, fontWeight = FontWeight.Bold, fontSize = 23.sp)
            Spacer(Modifier.weight(1f))
            if (playerUrl == null && selected == null) {
                BasicTextField(value = search, onValueChange = { search = it }, singleLine = true,
                    modifier = Modifier.widthIn(min = 110.dp, max = 230.dp).background(panel, RoundedCornerShape(8.dp)).padding(10.dp).focusable(),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
                    decorationBox = { inner -> Box { if (search.isEmpty()) Text("⌕ Search", color = Color.LightGray); inner() } })
            }
        }
        when {
            playerUrl != null -> {
                TextButton(onClick = { playerUrl = null }) { Text("← Back to details") }
                FarsiWebPlayer(url = playerUrl!!, modifier = Modifier.fillMaxWidth().weight(1f))
            }
            selected != null -> {
                val item = selected!!
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { TextButton(onClick = { closeDetails() }) { Text("← Back") } }
                    item { Text(item.title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold) }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = ImageRequest.Builder(context).data(item.poster).crossfade(true).build(), contentDescription = item.title,
                                modifier = Modifier.size(width = 110.dp, height = 155.dp))
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(if (item.series) "TV Series" else "Movie", color = Color.LightGray)
                                Text("FarsiPlex provides playback sources, quality options and any required unlock step.", color = Color.LightGray)
                                TextButton(onClick = { store.favorite(item.page); favorites = store.favorites() }) { Text(if (item.page in favorites) "♥ Saved" else "♡ My List") }
                            }
                        }
                    }
                    if (item.series) {
                        if (episodeList.isEmpty()) {
                            item { Text("Episodes may still be loading, or this series has no episode links available.", color = Color.LightGray) }
                            item { Button(onClick = { playerUrl = item.page }) { Text("Open series page") } }
                        } else {
                            item { Text("Choose season", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    episodeList.map { it.season }.distinct().forEach { season ->
                                        if (season == chosenSeason) Button(onClick = { chosenSeason = season }) { Text("Season $season") }
                                        else OutlinedButton(onClick = { chosenSeason = season }) { Text("Season $season") }
                                    }
                                }
                            }
                            items(episodeList.filter { it.season == chosenSeason }, key = { it.page }) { episode ->
                                Row(Modifier.fillMaxWidth().background(if (chosenEpisode?.page == episode.page) panel else background, RoundedCornerShape(8.dp))
                                    .clickable { chosenEpisode = episode }.focusable().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("S${episode.season} · ${episode.name}", color = Color.White)
                                    Spacer(Modifier.weight(1f))
                                    if (chosenEpisode?.page == episode.page) Text("✓", color = accent)
                                }
                            }
                            item { Button(enabled = chosenEpisode != null, onClick = { chosenEpisode?.let { store.mark(it.page); watched = store.watched(); playerUrl = it.page } }) { Text("▶ Play selected episode") } }
                        }
                    } else {
                        item { Button(onClick = { store.mark(item.page); watched = store.watched(); playerUrl = item.page }) { Text("▶ Play movie") } }
                    }
                    item { Text("Playback stays on FarsiPlex. If an advertisement is required to unlock a video, complete it normally. Player and quality choices are available when offered by the website.", color = Color.LightGray, fontSize = 13.sp) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tab.entries.forEach { t ->
                        TextButton(onClick = { tab = t }, modifier = Modifier.weight(1f)) {
                            Text(t.name, color = if (tab == t) accent else Color.LightGray, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, color = Color.Yellow)
                val listed = when (tab) {
                    Tab.Home -> (movies.take(12) + series.take(12))
                    Tab.Movies -> movies
                    Tab.Series -> series
                    Tab.History -> (movies + series).filter { it.page in watched }
                    Tab.Favorites -> (movies + series).filter { it.page in favorites }
                }.filter { search.isBlank() || it.title.contains(search, ignoreCase = true) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${tab.name} (${listed.size})", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            loading = true
                            movies = try { readCatalog(false) } catch (_: Exception) { movies }
                            series = try { readCatalog(true) } catch (_: Exception) { series }
                            loading = false
                        }
                    }) { Text("Refresh") }
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 150.dp), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(listed, key = { it.page }) { item ->
                        Column(Modifier.background(panel, RoundedCornerShape(10.dp)).clickable { selected = item; chosenEpisode = null; episodeList = emptyList() }.focusable().padding(8.dp)) {
                            if (item.poster != null) AsyncImage(model = ImageRequest.Builder(context).data(item.poster).crossfade(true).build(),
                                contentDescription = item.title, modifier = Modifier.fillMaxWidth().height(200.dp))
                            else Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text("🎬", fontSize = 40.sp) }
                            Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (item.series) "Series · Episodes" else "Movie · Play", color = accent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Hide obvious ad banners without touching FarsiPlex's legitimate "View a short ad" unlock dialog.
 * An unlock wall is access control, not a removable popup. */
private const val playerAdCleaner = """
(function() {
  if (window.__robinAdCleanerInstalled) return;
  window.__robinAdCleanerInstalled = true;
  var css = `
    ins.adsbygoogle, .adsbygoogle, .google-auto-placed,
    [id^="google_ads_"], [id^="div-gpt-ad"], [id*="google_ads_iframe"],
    .ad-banner, .banner-ad, .advertisement, .adsense,
    .ad-unit, .ad-slot, .advertising-banner,
    iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"],
    iframe[src*="googleadservices.com"] {
      display:none !important; visibility:hidden !important;
      height:0 !important; min-height:0 !important; max-height:0 !important;
      margin:0 !important; padding:0 !important;
      pointer-events:none !important;
    }
  `;
  function installStyle() {
    if (document.getElementById('robin-ad-style')) return;
    var el = document.createElement('style');
    el.id = 'robin-ad-style'; el.textContent = css;
    (document.head || document.documentElement).appendChild(el);
  }
  installStyle();
  // Some banner containers are added after the page finishes loading.
  var observer = new MutationObserver(installStyle);
  observer.observe(document.documentElement, {childList:true, subtree:true});
})();
"""

/** The provider's actual website handles the unlock countdown, source switching, and quality menu.
 * This intentionally does not extract signed MP4 links or bypass access checks. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FarsiWebPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val web = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    view?.evaluateJavascript(playerAdCleaner, null)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return false
                    // Block unrelated top-level redirects, while allowing site navigation and video iframes.
                    return request.isForMainFrame && target.host?.let { it != "farsiplex.com" && it != "www.farsiplex.com" } == true
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
    BackHandler(customView != null) {
        customCallback?.onCustomViewHidden(); customView = null; customCallback = null
    }
    web.webChromeClient = remember(web) {
        object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                customView = view; customCallback = callback
            }
            override fun onHideCustomView() { customView = null; customCallback = null }
        }
    }
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        customView?.let { view ->
            AndroidView(factory = { parent ->
                (view.parent as? ViewGroup)?.removeView(view)
                FrameLayout(parent).apply {
                    addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}

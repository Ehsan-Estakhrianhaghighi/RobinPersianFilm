package com.persianrubinfilm.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONTokener
import kotlinx.coroutines.delay
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import java.net.HttpURLConnection
import java.net.URL

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

/** Only use a final player address when the selected title publishes a link that
 * carries title-specific context (such as a query string). The generic HQ URL on
 * its own is NOT enough to identify the movie. For generic links the WebView follows
 * the site's actual Play element so its browser session can be initialized normally.
 * Required unlock dialogs remain entirely controlled by the provider. */
private suspend fun resolvePublishedPlaybackPage(titlePage: String): String = withContext(Dispatchers.IO) {
    if (!sameSite(titlePage)) return@withContext titlePage
    try {
        val doc = readPage(titlePage)
        val candidates = doc.select("a[href], iframe[src], [data-href], [data-url]").mapNotNull { node ->
            val link = when {
                node.tagName() == "iframe" -> node.absUrl("src")
                node.hasAttr("href") -> node.absUrl("href")
                node.hasAttr("data-href") -> URI(doc.location()).resolve(node.attr("data-href")).toString()
                node.hasAttr("data-url") -> URI(doc.location()).resolve(node.attr("data-url")).toString()
                else -> ""
            }
            if (!sameSite(link)) null else {
                val parsed = URI(link)
                val path = parsed.path.orEmpty().lowercase()
                val isPlayback = path.startsWith("/play-hq-player/") ||
                    path.startsWith("/play-quick-view/") ||
                    path.matches(Regex(".*/(watch|stream|play)(/.*)?"))
                // A generic /play-hq-player/ page may rely on JavaScript state. Do not
                // directly load it without clicking the title's actual link first.
                val carriesContext = !parsed.rawQuery.isNullOrEmpty() ||
                    path.removeSuffix("/").split('/').size > 2 ||
                    node.tagName() == "iframe"
                if (isPlayback && carriesContext && link != titlePage) link else null
            }
        }
        candidates.firstOrNull() ?: titlePage
    } catch (_: Exception) { titlePage }
}

private class FarsiStore(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("robinpersianfilm", 0)
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    fun watched(): Set<String> = prefs.getStringSet("watched", emptySet())?.toSet() ?: emptySet()
    fun favorite(id: String) { val s = favorites().toMutableSet(); if (!s.add(id)) s.remove(id); prefs.edit().putStringSet("favorites", s).apply() }
    fun mark(id: String) { prefs.edit().putStringSet("watched", watched() + id).apply() }
}

class MainActivity : ComponentActivity() {
    var remoteMediaHandler: ((Int) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (remoteMediaHandler?.invoke(event.keyCode) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = background, surface = panel)) { RobinApp() } }
    }
}

@Composable
private fun TvTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    TextButton(onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus }
            .border(if (focused) 2.dp else 0.dp,
                if (focused) Color(0xFFFFD600) else Color.Transparent, RoundedCornerShape(8.dp)),
        content = content)
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
    var resolvingPlayer by remember { mutableStateOf(false) }
    fun openPlayback(page: String) {
        scope.launch {
            resolvingPlayer = true
            val destination = resolvePublishedPlaybackPage(page)
            resolvingPlayer = false
            playerUrl = destination
        }
    }
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
                FarsiWebPlayer(url = playerUrl!!, onExit = { playerUrl = null }, modifier = Modifier.fillMaxWidth().weight(1f))
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
                                Text("Choose a player and video quality inside RobinPersianFilm. Some sources require an unlock step before playing.", color = Color.LightGray)
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
                            item { Button(onClick = { chosenEpisode?.let { store.mark(it.page); watched = store.watched(); openPlayback(it.page) } }, enabled = chosenEpisode != null && !resolvingPlayer) { Text(if (resolvingPlayer) "Opening player…" else "▶ Play selected episode") } }
                        }
                    } else {
                        item { Button(onClick = { store.mark(item.page); watched = store.watched(); openPlayback(item.page) }, enabled = !resolvingPlayer) { Text(if (resolvingPlayer) "Opening player…" else "▶ Play movie") } }
                    }
                    item { Text("Playback stays inside RobinPersianFilm. Complete a provider-required unlock if shown. Player and quality options appear as they become available.", color = Color.LightGray, fontSize = 13.sp) }
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
                        var cardFocused by remember { mutableStateOf(false) }
                        Column(Modifier
                            .onFocusChanged { cardFocused = it.hasFocus }
                            .border(if (cardFocused) 3.dp else 1.dp,
                                if (cardFocused) Color(0xFFFFD600) else panel, RoundedCornerShape(10.dp))
                            .background(panel, RoundedCornerShape(10.dp))
                            .clickable { selected = item; chosenEpisode = null; episodeList = emptyList() }
                            .padding(8.dp)) {
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

/** Advance through FarsiPlex's preview *after normal site authorization*.
 * Both a title page and /play-quick-view/ are intermediate stages. V1.5
 * mistakenly stopped at /play-quick-view/, which is not the video player.
 * Click only first-party playback links / clearly identified media controls;
 * never click the ad-unlock dialog or an unrelated advertisement. */
private const val followFinalPlayerScript = """
(function(){
  var path=(location.pathname||'').toLowerCase();
  if(path.indexOf('/play-hq-player/')===0) return 'final';
  var quick=path.indexOf('/play-quick-view/')===0;
  var title=/^\/(movie|episode)\//.test(path);
  if(!quick && !title) return 'other';
  function visible(el){
    if(!el || !el.getBoundingClientRect) return false;
    var r=el.getBoundingClientRect(), s=getComputedStyle(el);
    return r.width>8 && r.height>8 && s.display!=='none' && s.visibility!=='hidden';
  }
  // Do not automate any gated advertising or content-access action.
  var blocks=[...document.querySelectorAll('[role=dialog],.modal,.modal-dialog,.popup,.unlock-modal,[class*=unlock]')];
  if(blocks.some(function(el){
    return visible(el) && /unlock more content|view a short ad|watch.{0,35}to unlock/i.test((el.textContent||'').slice(0,1300));
  })) return 'unlock-required';
  // A single click per loaded document. Page transitions reset this flag.
  if(window.__robinAutoPreviewAttempted) return 'waiting-for-navigation';
  var blocked='[role=dialog],.modal,.popup,[class*=unlock],ins.adsbygoogle,.adsbygoogle,[class*=ad-banner]';
  function safe(el){return visible(el) && !el.closest(blocked);}
  function onSite(raw){
    try { var u=new URL(raw, location.href); return u.origin===location.origin && u.protocol==='https:'; }
    catch(e){return false;}
  }
  // Follow ORIGINAL elements to preserve FarsiPlex's selected-title/session context.
  var links=[...document.querySelectorAll('a[href],button[data-href],[data-url],[data-href]')].filter(safe);
  var linksToPlayer=links.filter(function(el){
    var raw=el.getAttribute('href')||el.getAttribute('data-href')||el.getAttribute('data-url')||'';
    if(!onSite(raw)) return false;
    var p=new URL(raw,location.href).pathname.toLowerCase();
    return quick ? p.indexOf('/play-hq-player/')===0 : /^\/play-(hq-player|quick-view)\/?$/.test(p);
  });
  if(linksToPlayer.length){
    linksToPlayer.sort(function(a,b){
      function score(el){var t=((el.textContent||'')+' '+(el.className||'')+' '+(el.getAttribute('aria-label')||'')).toLowerCase();
        return (/\b(play|watch|video)\b/.test(t)?3:0)+(el.closest('[class*=poster],[class*=player],[class*=video]')?2:0);}
      return score(b)-score(a);
    });
    window.__robinAutoPreviewAttempted=true;
    linksToPlayer[0].click();
    return quick?'opening-final-player':'opening-playback-page';
  }
  // FarsiPlex's quick-view often has a big Play overlay on the poster, with
  // no readable text. Locate the overlay or the play icon's clickable parent.
  var selectors=[
    'a.play','a[class*=play]','a[class*=watch]','button[class*=play]','button[class*=watch]','[role=button][class*=play]',
    '[class*=play-overlay]','[class*=play-button]','[class*=play-btn]',
    '[class*=watch-button]','[class*=video-play]','[class*=player-play]',
    '[data-action*=play]','[aria-label*=Play]','[title*=Play]',
    '[onclick*=play]','[onclick*=Play]'
  ];
  var nodes=[...document.querySelectorAll(selectors.join(','))];
  // Include clickable parents of graphical (textless) Play symbols.
  document.querySelectorAll('.fa-play,[class*=icon-play],svg[class*=play],i[class*=play]').forEach(function(icon){
    var parent=icon.closest('a,button,[role=button],[onclick],[class*=play]');
    if(parent) nodes.push(parent);
  });
  var candidates=[...new Set(nodes)].filter(function(el){
    if(!safe(el)) return false;
    var href=el.getAttribute('href')||'';
    if(href && !onSite(href) && href!=='#' && href.indexOf('javascript:')!==0) return false;
    var t=((el.textContent||'')+' '+(el.className||'')+' '+(el.id||'')+' '+(el.getAttribute('aria-label')||'')).toLowerCase().slice(0,240);
    if(/advert|banner|sponsor|download|unlock|trailer|share/.test(t)) return false;
    return /\bplay\b|play-|play_|-play|_play|watch|video/.test(t) || !!el.querySelector('.fa-play,[class*=icon-play],svg[class*=play]');
  });
  candidates.sort(function(a,b){
    function score(el){
      var r=el.getBoundingClientRect(), t=((el.className||'')+' '+(el.getAttribute('aria-label')||'')).toLowerCase();
      return (el.closest('[class*=poster],[class*=preview],[class*=video],[class*=player],[class*=cover]')?8:0)
       +(/play/.test(t)?3:0)+(r.width>50&&r.width<500?2:0);
    }
    return score(b)-score(a);
  });
  if(candidates.length){
    window.__robinAutoPreviewAttempted=true;
    candidates[0].click();
    return quick?'opening-final-from-preview':'opening-preview';
  }
  return quick?'preview-needs-tap':'title-needs-tap';
})();
"""

/** The user has requested playback. When FarsiPlex presents its REQUIRED
 *  access-ad dialog, try its first-party "View a short ad" control ONCE per
 *  newly presented dialog. A second, new dialog after changing player/quality
 *  is eligible for a new attempt; the old two-minute sessionStorage lockout
 *  prevented that. This NEVER marks an advertisement watched or bypasses the
 *  provider's access requirements. A scripted click may be rejected. */
private const val startRequiredAccessAdScript = """
(function(){
  if(!/^(www\.)?farsiplex\.com$/i.test(location.hostname)) return 'offsite';
  function visible(el){
    if(!el || !el.getBoundingClientRect) return false;
    var r=el.getBoundingClientRect(), cs=getComputedStyle(el);
    return r.width>6 && r.height>6 && cs.visibility!=='hidden' && cs.display!=='none';
  }
  function txt(el){return (el.innerText||el.textContent||'').replace(/\s+/g,' ').trim();}
  function eligibleRoot(el){
    if(!visible(el)) return false;
    var t=txt(el).toLowerCase();
    return t.includes('unlock more content') && t.includes('view a short ad') && t.length<1800;
  }
  // This can only see the FarsiPlex document, NOT protected cross-origin ad frames.
  var roots=[...document.querySelectorAll('[role="dialog"],[aria-modal="true"],.modal,.modal-dialog,.popup,.unlock-modal,[class*="unlock"],[id*="unlock"]')].filter(eligibleRoot);
  if(!roots.length){
    var headings=[...document.querySelectorAll('h1,h2,h3,h4,div,span,p,strong')].filter(function(el){
      return visible(el) && txt(el).toLowerCase()==='unlock more content';
    });
    for(var i=0;i<headings.length;i++){
      var el=headings[i];
      for(var depth=0;depth<6 && el;depth++,el=el.parentElement){
        if(eligibleRoot(el)){roots.push(el);break;}
      }
    }
  }
  var state=window.__robinAccessState;
  if(!state) state=window.__robinAccessState={root:null,shown:false,attempted:false,lastAttempt:0};
  // Some providers reuse the SAME popup element when changing Player/quality;
  // mark the old prompt finished when a genuine source selector is clicked.
  if(!window.__robinSourceChangeListener){
    window.__robinSourceChangeListener=true;
    document.addEventListener('click',function(event){
      var el=event.target && event.target.closest && event.target.closest('button,a,li,[role="button"],[role="tab"]');
      if(!el) return;
      var title=txt(el);
      if(!/^(?:Player\s*[1-9]|(?:360|480|720|1080|1440|2160)\s*p?)$/i.test(title)) return;
      if(el.closest('[role="dialog"],.modal,.modal-dialog,.popup,.unlock-modal')) return;
      var st=window.__robinAccessState;
      if(st){st.shown=false;st.root=null;st.attempted=false;}
    },true);
  }
  if(!roots.length){
    // The old prompt was dismissed. Allow another click if a NEW prompt appears.
    state.shown=false;
    state.root=null;
    state.attempted=false;
    return 'not-shown';
  }
  // Prefer the smallest actual dialog, rather than an outer page container.
  roots.sort(function(a,b){return txt(a).length-txt(b).length;});
  var root=roots[0], now=Date.now();
  if(!state.shown || state.root!==root){
    state.root=root;
    state.shown=true;
    state.attempted=false;
  }
  if(state.attempted){
    return now-state.lastAttempt<1400 ? 'click-pending' : 'still-visible';
  }
  // A freshly replaced DOM node may still represent the same offerwall.
  // This short safety interval avoids repeated accidental ad interactions.
  if(now-state.lastAttempt<5000) return 'cooldown';
  function actionable(el){return el.closest('button,a,[role="button"],[onclick],input[type="button"],[tabindex]');}
  var candidates=[...root.querySelectorAll('button,a,[role="button"],[onclick],label,span,div,p')].filter(function(el){
    if(!visible(el)) return false;
    var t=txt(el).toLowerCase();
    return t.includes('view a short ad') && t.length<160 &&
      !/withdraw money|transfer money|install extension|learn more|download now/.test(t);
  });
  candidates.sort(function(a,b){return txt(a).length-txt(b).length;});
  for(var j=0;j<candidates.length;j++){
    var el=candidates[j], button=actionable(el);
    if(!button){
      for(var cur=el, depth=0;depth<3 && cur && cur!==root;depth++,cur=cur.parentElement){
        if(cur.onclick || getComputedStyle(cur).cursor==='pointer'){button=cur;break;}
      }
    }
    // FarsiPlex sometimes attaches its click listener to a plain option card
    // without role=button/onclick. Clicking the short, exact option label lets
    // the browser bubble the event to that card; never click the whole dialog.
    if(!button && el!==root && typeof el.click==='function') button=el;
    if(!button || !root.contains(button) || !visible(button)) continue;
    var href=button.getAttribute('href')||'';
    if(href && href!=='#' && !href.startsWith('javascript:')){
      try{if(new URL(href,location.href).origin!==location.origin) continue;}
      catch(e){continue;}
    }
    state.attempted=true;
    state.lastAttempt=now;
    try{button.click();}catch(e){return 'manual-required';}
    // A click() returning does NOT mean the provider accepted it.
    return 'click-attempted';
  }
  return 'manual-required';
})();
"""

/** Hide obvious ad banners without touching FarsiPlex's legitimate "View a short ad" unlock dialog.
 * An unlock wall is access control, not a removable popup. */
private const val playerAdCleaner = """
(function(){
  if (window.__robinAdCleanerInstalled) {
    window.__robinCleanBanners(); return 'refreshed';
  }
  window.__robinAdCleanerInstalled=true;
  // Cosmetic filtering only. The 24-hour access dialog and actual player ads
  // must remain accessible so FarsiPlex can grant playback normally.
  var selectors=[
    'ins.adsbygoogle','.adsbygoogle','.google-auto-placed',
    '[id^="google_ads_iframe"]','[id^="google_ads_"]','[id^="aswift_"]',
    '[id^="div-gpt-ad"]','[id*="google-adsense-platform-ads-anchor"]',
    '[id*="google_adsense_anchor"]','[data-ad-client]','[data-ad-slot]',
    '[data-google-query-id]', '.ad-banner','.banner-ad','.advertisement',
    '.adsense','.ad-unit','.ad-slot','.ad-wrapper','.advertising-banner',
    '.ad-container','.sticky-ad','.floating-ad','.top-banner',
    '.bottom-banner','.ezoic-ad','.adthrive-ad'
  ].join(',');
  function shortText(el){return (el.textContent||'').replace(/\s+/g,' ').trim().slice(0,2000).toLowerCase();}
  function isUnlock(el){
    if(!el) return false;
    // Inspect the actual dialog content rather than treating every .popup as
    // protected; some banner ad wrappers also use that class.
    for(var p=el, n=0; p && n<7 && p!==document.body; p=p.parentElement,n++){
      if(!p.matches || !p.matches('[role="dialog"],[aria-modal="true"],.modal,.modal-dialog,.popup,[class*="unlock"],[id*="unlock"]')) continue;
      var t=shortText(p);
      if(t.indexOf('unlock more content')>=0 && t.indexOf('view a short ad')>=0) return true;
    }
    return false;
  }
  function ownsPlayer(el){
    if(!el || !el.querySelector) return false;
    var t=shortText(el);
    return (t.includes('player 1') && t.includes('player 2')) ||
      t.includes('video sources') || !!el.querySelector('video,[id*="jwplayer"],[class*="jwplayer"]');
  }
  function insidePlayer(el){
    return !!el.closest('video,[class*="jwplayer"],[id*="jwplayer"],[class*="video-player"],[id*="video-player"]');
  }
  function conceal(el){
    if(!el || el===document.body || el===document.documentElement || isUnlock(el) || insidePlayer(el) || ownsPlayer(el)) return false;
    el.setAttribute('data-robin-banner','hidden');
    el.style.setProperty('display','none','important');
    el.style.setProperty('height','0','important');
    el.style.setProperty('min-height','0','important');
    el.style.setProperty('margin','0','important');
    el.style.setProperty('padding','0','important');
    return true;
  }
  var style=document.createElement('style');
  style.id='robin-banner-style';
  style.textContent='[data-robin-banner="hidden"]{display:none!important;height:0!important;min-height:0!important;margin:0!important;padding:0!important;}';
  (document.head||document.documentElement).appendChild(style);
  function clean(){
    document.querySelectorAll(selectors).forEach(function(el){
      var parent=el.parentElement;
      if(!conceal(el)) return;
      // Collapse space reserved for a single ad, but never a container with
      // player tabs, source/quality choices, or the legitimate access prompt.
      for(var n=0;n<2 && parent && parent!==document.body;n++){
        if(ownsPlayer(parent)||isUnlock(parent)) break;
        var cls=((parent.className||'')+' '+(parent.id||'')).toLowerCase();
        var adWrapper=/adsbygoogle|ad-container|ad-wrapper|adsense|ad-slot|ad-unit|google|aswift|anchor-ad|banner-ad|top-banner|sticky-ad/.test(cls);
        var onlyHidden=Array.from(parent.children).every(function(child){
          return child.getAttribute('data-robin-banner')==='hidden' ||
            (child.matches && child.matches('script,style,noscript'));
        });
        if(!(adWrapper||onlyHidden) || shortText(parent).length>140) break;
        var next=parent.parentElement;
        conceal(parent);parent=next;
      }
    });
    // Advertising iframes often arrive only after the site has initialized.
    document.querySelectorAll('iframe[src],iframe[id]').forEach(function(frame){
      if(isUnlock(frame)||insidePlayer(frame)) return;
      var src=(frame.getAttribute('src')||'').toLowerCase();
      var id=(frame.id||'').toLowerCase();
      if(/doubleclick\.net|googlesyndication\.com|googleadservices\.com|adservice\.google/.test(src) ||
         /^google_ads_iframe|^aswift_/.test(id)) conceal(frame);
    });
  }
  window.__robinCleanBanners=clean;
  clean();
  var pending=false;
  new MutationObserver(function(){
    if(pending)return;pending=true;
    setTimeout(function(){pending=false;clean();},250);
  }).observe(document.documentElement,{childList:true,subtree:true});
  return 'installed';
})();
"""

/** Make the final FarsiPlex page a dedicated player surface, not a website inside
 * the player. We hide only site navigation / unrelated article content. The actual
 * video, its Player 1/2/3 tabs, quality links and required access dialog stay live.
 * FarsiPlex sometimes builds its header AFTER the document load, hence the observer. */
private const val compactPlaybackScript = """
(function(){
  if (window.__robinCompactPlayerInstalled) { window.__robinCleanPlayerPage(); return 'refreshed'; }
  window.__robinCompactPlayerInstalled = true;
  var style=document.createElement('style');
  style.id='robin-final-player-only';
  style.textContent=`
    /* Hide the FarsiPlex website chrome; NOT the app's Back button. */
    body > header, header[role="banner"], [role="banner"],
    #header, #site-header, #masthead, #navbar,
    .site-header, .main-header, .header-wrap, .header-area,
    .header-main, .header-inner, .mobile-header, .mobile-nav,
    .site-navigation, .main-navigation, .header-menu,
    .navbar, .navbar-header, .navigation-bar,
    .site-branding, .header-branding, .header-logo,
    .site-logo, .custom-logo-link, .logo-area, .logo-container,
    .menu-toggle, .nav-toggle, .search-toggle, .hamburger,
    .mobile-menu, .header-search, .header-menu,
    footer.site-footer, .site-footer, #colophon,
    .breadcrumbs, .breadcrumb, aside.widget-area, .sidebar {
      display:none!important; max-height:0!important; margin:0!important;
      padding:0!important; overflow:hidden!important;
    }
    [data-robin-site-chrome="hidden"], [data-robin-unrelated="hidden"] {
      display:none!important; max-height:0!important; margin:0!important;
      padding:0!important; overflow:hidden!important;
    }
    html,body { scroll-behavior:auto!important; }
    body { padding-top:0!important; }
  `;
  (document.head||document.documentElement).appendChild(style);
  var danger='[role="dialog"],[aria-modal="true"],.modal,.modal-dialog,.unlock-modal,[class*="unlock"],[id*="unlock"]';
  function containsPlayback(el){
    if (!el) return false;
    var text=(el.textContent||'').slice(0,9000);
    return /video sources|player\s*1\s*player\s*2/i.test(text) ||
      !!el.querySelector('video,[class*="jwplayer"],[id*="jwplayer"]');
  }
  function containsUnlock(el){
    if(!el) return false;
    if (el.matches(danger) || el.querySelector(danger)) return true;
    var text=(el.textContent||'').slice(0,1700);
    return /unlock more content/i.test(text) && /view a short ad/i.test(text);
  }
  function hide(el,kind){
    if(!el || el===document.body || el===document.documentElement || containsUnlock(el) || containsPlayback(el)) return false;
    el.setAttribute(kind==='article'?'data-robin-unrelated':'data-robin-site-chrome','hidden');
    return true;
  }
  function nearTop(el){
    var r=el.getBoundingClientRect();
    return r.width>0 && r.height>0 && r.top<450 && r.bottom> -10;
  }
  function looksLikeSiteBrand(el){
    var label=(el.getAttribute('alt')||'')+' '+(el.getAttribute('title')||'')+' '+
      (el.getAttribute('aria-label')||'')+' '+(el.textContent||'').slice(0,90);
    var cl=(String(el.className||'')+' '+(el.id||'')).toLowerCase();
    var src=(el.getAttribute('src')||'').toLowerCase();
    return /farsi\s*plex|فارسی\s*plex/i.test(label) ||
      (/(logo|branding)/.test(cl) && /(farsi|logo)/.test(src+' '+label.toLowerCase()));
  }
  function cleanNavigation(){
    /* The actual website sometimes uses a plain div for its top logo/menu/search
       rather than the standard WordPress header classes. Find that row. */
    var brands=document.querySelectorAll('img[alt],a[href], [class*="logo"],[class*="brand"], [id*="logo"]');
    for(var i=0;i<brands.length;i++){
      var logo=brands[i];
      if (!nearTop(logo) || !looksLikeSiteBrand(logo)) continue;
      var row=null, parent=logo;
      for(var step=0;step<5 && parent && parent!==document.body;step++,parent=parent.parentElement){
        var r=parent.getBoundingClientRect();
        if(r.height>15 && r.height<230 && r.width>=Math.min(window.innerWidth*0.65,320) &&
           r.top<450 && !containsPlayback(parent) && !containsUnlock(parent)) row=parent;
      }
      // If the site's toolbar has its own row, remove it as a unit; otherwise
      // at least remove the logo itself (without removing the video container).
      hide(row || logo,'chrome');
    }
    /* Sometimes the logo is a background image and has no alt/title.
       Hide the site-home link near the top, but never the player/quality row. */
    document.querySelectorAll('a[href],img[src],input[type="search"]').forEach(function(el){
      if(!nearTop(el))return;
      var raw=((el.getAttribute('href')||'')+' '+(el.getAttribute('src')||'')+' '+
        (el.getAttribute('alt')||'')+' '+(el.className||'')).toLowerCase();
      var siteHome=false;
      try{var u=new URL(el.getAttribute('href')||'#',location.href);
        siteHome=u.hostname===location.hostname && (u.pathname==='/'||u.pathname==='/explore/');}
      catch(e){}
      if(siteHome || /farsiplex.*logo|logo.*farsiplex|فارسی.*plex/.test(raw)){
        var parent=el.closest('a')||el;
        hide(parent,'chrome');
      }
    });
    /* Independently hide menu/search affordances when the logo isn't an image. */
    var triggers=document.querySelectorAll(
      '.menu-toggle,.search-toggle,.mobile-menu-toggle,.navbar-toggle,'+
      '.hamburger,.hamburger-menu,[aria-label*="menu" i],[aria-label*="search" i],'+
      '[class*="header-search"],[class*="header-menu"],'+
      'header form[role="search"], header input[type="search"]');
    for(var j=0;j<triggers.length;j++){
      var item=triggers[j];
      if(!nearTop(item)) continue;
      var container=item.closest('button,a,[role="button"]')||item;
      hide(container,'chrome');
    }
    document.querySelectorAll('header,nav,[role="banner"],.site-header,.main-header').forEach(function(n){
      var r=n.getBoundingClientRect();
      if(r.top<450 && r.height<260) hide(n,'chrome');
    });
  }
  function cleanArticles(){
    /* FarsiPlex currently appends an unrelated movie-list article below the
       real player/source list. Do not remove an article that owns the player. */
    document.querySelectorAll('h1,h2,h3,h4').forEach(function(heading){
      var title=(heading.textContent||'').trim();
      if(!/^the 15 best iranian movies of all time/i.test(title)) return;
      var section=heading.closest('article,.post,.blog-post,.entry-content,.post-content,.article-content');
      if(section && hide(section,'article')) return;
      // The site sometimes places this article in generic divs. Walk up to
      // the largest dedicated article container, stopping BEFORE the player.
      var candidate=heading, ancestor=heading.parentElement;
      for(var depth=0;depth<6 && ancestor && ancestor!==document.body;depth++,ancestor=ancestor.parentElement){
        if(containsPlayback(ancestor) || containsUnlock(ancestor)) break;
        if((ancestor.textContent||'').length>22000) break;
        candidate=ancestor;
      }
      hide(candidate,'article');
    });
  }
  var cleaning=false;
  window.__robinCleanPlayerPage=function(){
    if(cleaning) return;
    cleaning=true;
    try { cleanNavigation(); cleanArticles(); } finally { cleaning=false; }
  };
  window.__robinCleanPlayerPage();
  var scheduled=false, lastScan=Date.now();
  var observer=new MutationObserver(function(){
    if(scheduled) return;
    scheduled=true;
    // Player frameworks frequently modify the DOM. Throttle layout checks
    // to avoid unnecessary CPU use and video stuttering on Fire TV.
    var wait=Math.max(350,1500-(Date.now()-lastScan));
    setTimeout(function(){
      scheduled=false;lastScan=Date.now();window.__robinCleanPlayerPage();
    },wait);
  });
  observer.observe(document.documentElement,{childList:true,subtree:true});
  return 'installed';
})();
"""

/** On a legitimate, unlocked playback page, bring the real player/source section
 * into view rather than making the viewer search through the article. */
private fun focusAvailablePlayerScript(): String = """
(function(){
  if(window.__robinPlayerLocated) return 'located';
  function shown(el){
    if(!el || !el.getBoundingClientRect) return false;
    var r=el.getBoundingClientRect(), st=getComputedStyle(el);
    return r.width>50 && r.height>14 && st.display!=='none' && st.visibility!=='hidden';
  }
  // The first iframe on FarsiPlex can be a large advertisement. Never use a
  // generic iframe as the scroll target; prefer Player 1 or Video Sources.
  var choices=[...document.querySelectorAll('button,a,[role="tab"],li,div,span,h2,h3,h4')];
  var target=choices.find(function(el){
    var t=(el.textContent||'').trim().replace(/\s+/g,' ');
    return shown(el) && /^player\s*1$/i.test(t) && t.length<30;
  });
  if(!target) target=choices.find(function(el){
    return shown(el) && /^video sources$/i.test((el.textContent||'').trim());
  });
  if(!target) target=[...document.querySelectorAll('video,[class*="jwplayer"],[id*="jwplayer"]')].find(shown);
  if(!target) return 'waiting';
  window.__robinPlayerLocated=true;
  target.scrollIntoView({block:'start',behavior:'instant'});
  return 'located';
})();
"""

/** Restore ordinary page scrolling after the provider removes its access
 *  overlay. Some offerwalls leave overflow:hidden on html/body, and this
 *  prevents swiping to source/quality choices on phones and TVs. */
private const val restorePlayerScrollScript = """
(function(){
  function hasUnlock(){
    return [...document.querySelectorAll('[role="dialog"],[aria-modal="true"],.modal,.modal-dialog')].some(function(el){
      var r=el.getBoundingClientRect(),t=(el.textContent||'').toLowerCase();
      return r.width>50&&r.height>50&&t.includes('unlock more content')&&t.includes('view a short ad');
    });
  }
  if(hasUnlock()) return 'unlock-visible';
  [document.documentElement,document.body,...document.querySelectorAll('main,#content,.site-content,.content-area,[class*="scroll"],[class*="wrapper"]')].forEach(function(el){
    if(!el)return;
    var css=getComputedStyle(el);
    if(/hidden|clip/.test(css.overflowY) && el.scrollHeight>el.clientHeight+50)
      el.style.setProperty('overflow-y','auto','important');
    if(css.touchAction==='none')el.style.setProperty('touch-action','pan-y','important');
  });
  return 'scroll-ready';
})();
"""

/** Compact advertising that is outside the player and access dialog. In addition
 * to hiding the ad iframe, collapse its dedicated host wrapper, otherwise a
 * blank full-width banner continues to consume most of the phone viewport. */
private const val reduceFinalAdSpaceScript = """
(function(){
  if(!/\/play-hq-player\/?/.test(location.pathname))return 'other-page';
  if(!window.__robinAdSpaceInstalled){
    window.__robinAdSpaceInstalled=true;
    var css=document.createElement('style');
    css.textContent=`
      ins.adsbygoogle,.adsbygoogle,.google-auto-placed,
      [data-ad-client],[data-ad-slot],[data-google-query-id],
      [id^="google_ads_iframe"],[id^="aswift_"],
      [id*="google_adsense_anchor"],.ad-banner,.top-banner,
      [data-robin-adspace="hidden"]{
        display:none!important;visibility:hidden!important;
        height:0!important;min-height:0!important;max-height:0!important;
        margin:0!important;padding:0!important;overflow:hidden!important;
      }
    `;
    (document.head||document.documentElement).appendChild(css);
  }
  function isUnlock(el){
    for(var n=el,i=0;n&&i<6;i++,n=n.parentElement){
      if(!n.matches)continue;
      if(!n.matches('[role="dialog"],[aria-modal="true"],.modal,.popup,[class*="unlock"]'))continue;
      var t=(n.textContent||'').toLowerCase();
      if(t.includes('unlock more content')&&t.includes('view a short ad'))return true;
    }
    return false;
  }
  function hasControls(n){
    var text=(n.textContent||'').toLowerCase().slice(0,20000);
    return text.includes('video sources')||
      (text.includes('player 1')&&text.includes('player 2'))||
      !!n.querySelector('video,[id*="jwplayer"],[class*="jwplayer"]');
  }
  function collapse(n){
    if(!n||n===document.body||n===document.documentElement||isUnlock(n)||hasControls(n))return false;
    n.setAttribute('data-robin-adspace','hidden');return true;
  }
  var ads=[...document.querySelectorAll('ins.adsbygoogle,.adsbygoogle,.google-auto-placed,[data-ad-client],[data-ad-slot],[id^="google_ads_iframe"],[id^="aswift_"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"],iframe[src*="googleadservices"],.ad-banner,.top-banner')];
  ads.forEach(function(ad){
    if(isUnlock(ad))return;
    var wrapper=ad;
    for(var i=0;i<4&&wrapper&&wrapper.parentElement;i++){
      var parent=wrapper.parentElement;
      if(parent===document.body||hasControls(parent)||isUnlock(parent))break;
      var children=[...parent.children].filter(function(ch){return !ch.matches('script,style,noscript');});
      var txt=(parent.textContent||'').trim();
      var pclass=String(parent.className||'')+' '+String(parent.id||'');
      var onlyAd=children.length<=2 && children.some(function(ch){return ch===wrapper||ch.contains(ad);}) && txt.length<100;
      if(!onlyAd && !/ad-|adsense|banner|google|advert|sponsor/i.test(pclass))break;
      wrapper=parent;
    }
    collapse(wrapper);
  });
  // Some top ad units arrive as plain iframes without adsbygoogle classes.
  // Only remove frames above the source tabs with clear advertising markers.
  var tabs=[...document.querySelectorAll('button,a,[role="tab"],li,div,span')].find(function(el){
    return (el.textContent||'').trim().toLowerCase()==='player 1' && el.getBoundingClientRect().width>30;
  });
  var tabTop=tabs?tabs.getBoundingClientRect().top:innerHeight;
  document.querySelectorAll('iframe').forEach(function(frame){
    if(isUnlock(frame))return;
    var r=frame.getBoundingClientRect();
    if(r.width<200||r.height<110||r.top>=tabTop)return;
    var id=(frame.id||'')+' '+(frame.className||'')+' '+(frame.getAttribute('title')||'')+' '+(frame.getAttribute('src')||'');
    var parent=frame.parentElement;
    var p=((parent&&parent.id)||'')+' '+((parent&&parent.className)||'');
    if(!/(advert|banner|adsense|adsbygoogle|googleads|googlesyndication|doubleclick|google_ads|aswift|adslot|ad-slot|sponsor|google-auto|adsystem)/i.test(id+' '+p))return;
    if(!collapse(parent))collapse(frame);
  });
  // A Google anchor sometimes reserves a fixed-height host outside its iframe.
  // The host can be collapsed, but never an unlock dialog or player wrapper.
  return 'cleaned:'+ads.length;
})();
"""

/** Find a real source option and scroll its ancestors, not just window. */
private const val scrollToVideoSourcesScript = """
(function(){
  var all=[...document.querySelectorAll('h1,h2,h3,h4,h5,button,a,li,span,div')];
  var target=all.find(function(el){
    var t=(el.textContent||'').trim().replace(/\s+/g,' ').toLowerCase();
    return t==='player 1';
  });
  if(!target)target=all.find(function(el){
    var t=(el.textContent||'').trim().replace(/\s+/g,' ').toLowerCase();
    return t==='video sources'||t==='480'||t==='480p';
  });
  if(!target)return 'not-found';
  // Avoid constantly pulling the page away from a user who is choosing a source.
  if(window.__robinAutoSourcesScrolled) return 'already-scrolled';
  window.__robinAutoSourcesScrolled=true;
  target.scrollIntoView({block:'start',behavior:'instant'});
  var r=target.getBoundingClientRect();
  if(r.top>innerHeight-70 || r.bottom<0){
    for(var p=target.parentElement;p;p=p.parentElement){
      if(p.scrollHeight>p.clientHeight+40){
        p.scrollTop+=(r.top-innerHeight/3);
      }
    }
  }
  return 'moved';
})();
"""

/** On the *final* FarsiPlex page, display the actual player section instead of
 * the site's preceding header/ad blocks. We don't navigate around the site's
 * access checks: while its offerwall is visible, leave the document alone.
 * This uses the three real player tabs as an anchor rather than guessing ad
 * class names. Re-run after dynamic insertions, without removing media nodes. */
private const val isolateFinalPlayerScript = """
(function(){
  if(location.pathname.indexOf('/play-hq-player')!==0)return 'other-page';
  function visible(el){
    if(!el||!el.getBoundingClientRect)return false;
    var r=el.getBoundingClientRect(),cs=getComputedStyle(el);
    return r.width>10&&r.height>8&&cs.display!=='none'&&cs.visibility!=='hidden';
  }
  function exact(s){
    return [...document.querySelectorAll('button,a,[role="tab"],[role="button"],li,span,div')]
      .find(function(n){return visible(n)&&(n.textContent||'').trim().toLowerCase()===s;});
  }
  var a=exact('player 1'),b=exact('player 2'),c=exact('player 3');
  if(!a||!b||!c)return 'tabs-loading';
  // Find the smallest container containing all three tabs, rather than
  // climbing to a page-wide container that also holds the advertisements.
  var group=a;
  while(group&&group!==document.body&&!(group.contains(b)&&group.contains(c)))
    group=group.parentElement;
  if(!group||group===document.body)return 'tabs-loading';
  function unlock(n){
    if(!n||!n.querySelectorAll)return false;
    var t=(n.textContent||'').toLowerCase();
    if(t.includes('unlock more content')&&t.includes('view a short ad'))return true;
    return [...n.querySelectorAll('[role="dialog"],[aria-modal="true"]')].some(function(d){
      var x=(d.textContent||'').toLowerCase();
      return visible(d)&&x.includes('unlock more content');
    });
  }
  // Keep any access prompt on-screen. Do not suppress provider access checks.
  if([...document.querySelectorAll('[role="dialog"],[aria-modal="true"],.modal,.modal-dialog,[class*="unlock"]')]
       .some(function(d){return visible(d)&&unlock(d);}))return 'unlock-visible';
  var style=document.getElementById('robin-player-layout');
  if(!style){
    style=document.createElement('style');style.id='robin-player-layout';
    style.textContent='[data-robin-hide-before-player="yes"]{display:none!important;height:0!important;min-height:0!important;max-height:0!important;overflow:hidden!important;margin:0!important;padding:0!important;} html,body{overflow-y:auto!important;scroll-behavior:auto!important;}';
    (document.head||document.documentElement).appendChild(style);
  }
  var hidden=0,child=group;
  while(child&&child.parentElement){
    var parent=child.parentElement;
    for(var sibling=parent.firstElementChild;sibling&&sibling!==child;sibling=sibling.nextElementSibling){
      if(unlock(sibling)||sibling.matches('script,style,link,noscript'))continue;
      // Only collapse preceding page chrome and advertisements, not a playing video.
      if(sibling.querySelector('video,[class*="jwplayer"],[id*="jwplayer"]'))continue;
      if(sibling.dataset.robinHideBeforePlayer!=='yes'){
        sibling.dataset.robinHideBeforePlayer='yes';hidden++;
      }
    }
    if(parent===document.body)break;
    child=parent;
  }
  // Some Google ad anchors are fixed overlays and aren't ordinary siblings.
  document.querySelectorAll('ins.adsbygoogle,.google-auto-placed,[id*="google_adsense_anchor"],[id^="aswift_"],[id^="google_ads_iframe"]').forEach(function(el){
    if(!unlock(el)&&!el.contains(group))el.dataset.robinHideBeforePlayer='yes';
  });
  var position=group.getBoundingClientRect().top+window.scrollY;
  window.scrollTo(0,Math.max(0,position-8));
  return 'player-first:'+hidden;
})();
"""

/** The WebView remains inside the app. We do NOT bypass FarsiPlex's 24-hour
 * ad unlock, copy signed streams, or circumvent access controls. The native controls
 * below only click player/quality controls actually present in the page's DOM.
 * Cross-origin iframe controls remain provider-managed. */
private fun findOptionsScript(): String = """
(function(){
 var players=[],quality=[];
 document.querySelectorAll('button,a,[role="tab"],[role="button"],li,span,div,p,strong,label,[class*="source"],[class*="quality"],[class*="server"]').forEach(function(n){
   if(n.children.length>8)return;
   var t=(n.textContent||'').trim().replace(/\s+/g,' ');
   if(t.length>55)return;
   var m=t.match(/^Player\s*([1-9])$/i);
   if(m&&!players.includes(m[1]))players.push(m[1]);
   var q=t.match(/^(360|480|720|1080|1440|2160)\s*p?$/i);
   if(q&&!quality.includes(q[1]))quality.push(q[1]);
 });
 players.sort(function(a,b){return +a-+b;});
 quality.sort(function(a,b){return +a-+b;});
 return players.join(',')+'|'+quality.join(',');
})();
"""

private fun selectOptionScript(kind: String, value: String): String {
    val safe = value.filter { it.isDigit() }
    val wanted = if (kind == "player") "Player $safe" else safe
    return """(function(){
      var wanted='${wanted}'.toLowerCase();
      var nodes=[...document.querySelectorAll('button,a,[role=tab],[role=button],li,span,div,p,strong,label,[class*="source"],[class*="quality"],[class*="server"]')];
      var matches=nodes.filter(function(el){
        var t=(el.textContent||'').trim().replace(/\s+/g,' ').toLowerCase();
        return t===wanted || (${if (kind == "quality") "true" else "false"} && t===wanted+'p');
      });
      if(!matches.length)return 'unavailable';
      matches.sort(function(a,b){return a.querySelectorAll('*').length-b.querySelectorAll('*').length;});
      var leaf=matches[0],control=leaf.closest('button,a,[role=tab],[role=button],li,[onclick],[class*="source-item"],[class*="quality-item"]')||leaf;
      if(control && typeof control.click==='function'){control.click();return 'selected';}
      return 'unavailable';
    })();"""
}

private data class NativeVideoSource(val label: String, val url: String, val quality: String)

/** Resolutions of the same media file share one source entry. */
private fun nativeSourceGroupKey(source: NativeVideoSource): String = try {
    val uri = URI(source.url)
    val basePath = uri.path.orEmpty().replace(
        Regex("""[.-](360|480|720|1080|1440|2160)(?=\.mp4$)""", RegexOption.IGNORE_CASE),
        "-quality"
    )
    "${uri.host}$basePath"
} catch (_: Exception) { source.url }


/** Check whether a filename-based quality variant is actually served for the
 * currently authorized request. The original signed URL is reused unchanged
 * except for the filename; invalid/expired signatures are rejected by the host.
 * No signatures are generated or access checks bypassed.
 */
private suspend fun probeAuthorizedQualityVariants(source: NativeVideoSource): List<NativeVideoSource> =
    withContext(Dispatchers.IO) {
        val uri = try { URI(source.url) } catch (_: Exception) { return@withContext emptyList() }
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" || !(host == "farsiplex.buzz" || host.endsWith(".farsiplex.buzz")))
            return@withContext emptyList()
        // Some FarsiPlex titles use 01.480.mp4; others use antik-480.mp4.
        // Preserve the original delimiter and signed query string.
        val match = Regex("""[.-](360|480|720|1080|1440|2160)\.mp4$""", RegexOption.IGNORE_CASE)
            .find(uri.path.orEmpty()) ?: return@withContext emptyList()
        val original = match.groupValues[1]
        val qualityRange = match.groups[1]?.range ?: return@withContext emptyList()
        // Only reasonable alternatives, not guesses at a provider's server list.
        val resolutions = listOf("480", "720", "1080").filter { it != original }
        resolutions.mapNotNull { quality ->
            val candidatePath = uri.path.replaceRange(qualityRange, quality)
            val checkUri = URI(uri.scheme, uri.rawAuthority, candidatePath, uri.rawQuery, null)
            val connection = try {
                (URL(checkUri.toASCIIString()).openConnection() as HttpURLConnection).apply {
                    // Some signed media servers do not implement HEAD consistently.
                    // A one-byte GET is the same approach used by streaming players.
                    requestMethod = "GET"
                    setRequestProperty("Range", "bytes=0-0")
                    connectTimeout = 3500
                    readTimeout = 3500
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "video/mp4")
                }
            } catch (_: Exception) { null }
            if (connection == null) return@mapNotNull null
            val accepted = try {
                val code = connection.responseCode
                val contentType = connection.contentType.orEmpty().lowercase()
                code in 200..206 && contentType.startsWith("video/")
            } catch (_: Exception) { false } finally { connection.disconnect() }
            if (accepted) NativeVideoSource("Verified $quality", checkUri.toASCIIString(), quality) else null
        }
    }

/** Only discover media URLs already exposed to the user's authorized browser page.
 * No token generation, ad-unlock circumvention or cross-origin iframe inspection.
 * Native playback is optional because some hosts require their embedded player. */
private const val discoverNativeVideoScript = """
(function(){
  if(location.pathname.indexOf('/play-hq-player')!==0)return '[]';
  var modal=[...document.querySelectorAll('[role="dialog"],[aria-modal="true"],.modal,[class*="unlock"]')].some(function(n){
    var text=(n.textContent||'').toLowerCase();
    var rect=n.getBoundingClientRect();
    return rect.width>40&&rect.height>40&&text.includes('unlock more content')&&text.includes('view a short ad');
  });
  if(modal)return '[]';
  var found=[],seen={};
  var media=/\.(mp4|m3u8|mpd)(?:[?#]|$)/i;
  function register(url,el){
    if(!url||!media.test(url))return;
    var p=el,steps=0;
    while(p&&steps++<5){
      var cls=String(p.className||'')+' '+String(p.id||'');
      if(/advert|adsbygoogle|google_ads|banner-ad|sponsor|offerwall/i.test(cls))return;
      p=p.parentElement;
    }
    try{
      var u=new URL(url,location.href);
      if(u.protocol!=='https:'||seen[u.href])return;
      seen[u.href]=1;
      var near=el.closest('[class*="source"],[class*="quality"],[class*="player"],li')||el.parentElement;
      var nearby=(near&&near.textContent||'').trim().slice(0,120);
      var q=(nearby.match(/(?:360|480|720|1080|1440|2160)\s*p?/i)||u.pathname.match(/(?:360|480|720|1080|1440|2160)(?=\D|$)/)||[])[0]||'Auto';
      found.push({label:nearby.length<45&&nearby?nearby:'Video source '+(found.length+1),url:u.href,quality:q});
    }catch(e){}
  }
  document.querySelectorAll('video,video source,a[href],source[src]').forEach(function(n){
    register(n.currentSrc||n.getAttribute('src')||n.getAttribute('href'),n);
    if(n.tagName==='VIDEO')register(n.getAttribute('src'),n);
  });
  return JSON.stringify(found.slice(0,24));
})();
"""

private fun isFinalPlayerPage(url: String?): Boolean = try {
    val path = URI(url.orEmpty()).path?.lowercase().orEmpty().trimEnd('/')
    path == "/play-hq-player"
} catch (_: Exception) { false }

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FarsiWebPlayer(url: String, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var availablePlayers by remember(url) { mutableStateOf<List<String>>(emptyList()) }
    var availableQualities by remember(url) { mutableStateOf<List<String>>(emptyList()) }
    var selectedPlayer by remember(url) { mutableStateOf("") }
    var selectedQuality by remember(url) { mutableStateOf("") }
    var nativeSources by remember(url) { mutableStateOf<List<NativeVideoSource>>(emptyList()) }
    var nativeChoice by remember(url) { mutableStateOf<NativeVideoSource?>(null) }
    var nativeFullscreen by remember(url) { mutableStateOf(false) }
    var fullscreenControlsVisible by remember(url) { mutableStateOf(false) }
    var seekBarFocused by remember(url) { mutableStateOf(false) }
    var openOnSeekBar by remember(url) { mutableStateOf(false) }
    var seekPosition by remember(url) { mutableLongStateOf(0L) }
    var currentPlaybackPosition by remember(url) { mutableLongStateOf(0L) }
    var playbackDuration by remember(url) { mutableLongStateOf(0L) }
    val fullscreenMenuRequester = remember { FocusRequester() }
    val seekBarRequester = remember { FocusRequester() }
    val fullscreenForKeys by rememberUpdatedState(nativeFullscreen)
    val controlsForKeys by rememberUpdatedState(fullscreenControlsVisible)
    val seekFocusedForKeys by rememberUpdatedState(seekBarFocused)
    var pendingSourceChange by remember(url) { mutableStateOf(false) }
    var checkingQualityVariants by remember(url) { mutableStateOf(false) }
    var playerMessage by remember(url) { mutableStateOf("Opening the final player…") }
    val nativePlayer = remember(url) { ExoPlayer.Builder(context).build() }
    val activity = context as? MainActivity
    LaunchedEffect(nativePlayer, fullscreenControlsVisible) {
        while (fullscreenControlsVisible) {
            currentPlaybackPosition = nativePlayer.currentPosition.coerceAtLeast(0L)
            playbackDuration = nativePlayer.duration.coerceAtLeast(0L)
            delay(350L)
        }
    }
    fun showControls(focusProgress: Boolean, pauseVideo: Boolean) {
        if (pauseVideo) nativePlayer.pause()
        seekPosition = nativePlayer.currentPosition.coerceAtLeast(0L)
        currentPlaybackPosition = seekPosition
        playbackDuration = nativePlayer.duration.coerceAtLeast(0L)
        openOnSeekBar = focusProgress
        fullscreenControlsVisible = true
        seekBarFocused = false
    }
    DisposableEffect(activity, nativePlayer) {
        if (activity != null) activity.remoteMediaHandler = { code ->
            if (nativePlayer.currentMediaItem == null) false else {
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (nativePlayer.isPlaying) nativePlayer.pause() else nativePlayer.play()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { nativePlayer.play(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                        nativePlayer.pause(); true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        nativePlayer.seekTo((nativePlayer.currentPosition + 15_000L)
                            .coerceAtMost(nativePlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        nativePlayer.seekTo((nativePlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!fullscreenForKeys) false
                        else if (!controlsForKeys) {
                            // First press reveals the seek bar; no jump yet.
                            showControls(focusProgress = true, pauseVideo = false)
                            true
                        } else if (seekFocusedForKeys) {
                            val delta = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                            val duration = nativePlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                            seekPosition = (seekPosition + delta).coerceIn(0L, duration)
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (!fullscreenForKeys) false
                        else if (!controlsForKeys) {
                            showControls(focusProgress = false, pauseVideo = true)
                            true
                        } else if (seekFocusedForKeys) {
                            nativePlayer.seekTo(seekPosition)
                            seekBarFocused = false
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }
        onDispose { if (activity != null) activity.remoteMediaHandler = null }
    }
    DisposableEffect(nativePlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            override fun onPlayerError(error: PlaybackException) {
                nativeChoice = null
                playerMessage = "Native stream unavailable; returning to provider controls"
            }
        }
        nativePlayer.addListener(listener)
        onDispose {
            nativePlayer.removeListener(listener)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    DisposableEffect(nativePlayer) { onDispose { nativePlayer.release() } }
    LaunchedEffect(nativeChoice) {
        val choice = nativeChoice
        if (choice != null) {
            nativePlayer.setMediaItem(MediaItem.fromUri(choice.url))
            nativePlayer.prepare()
            nativePlayer.playWhenReady = true
        } else {
            nativePlayer.stop()
        }
    }
    var accessPromptActive by remember(url) { mutableStateOf(false) }
    // Cover every intermediate WebView page with an app-owned loading surface.
    // Revealing it on a timer was the cause of the unwanted preview flash.
    var showWebContent by remember(url) { mutableStateOf(false) }
    var manualContinuation by remember(url) { mutableStateOf(false) }
    var lastCompletedPage by remember(url) { mutableStateOf("") }
    // Discover the exact HTTPS media request that the legitimately loaded provider
    // player makes, including its original short-lived signature. Never infer or
    // manufacture tokens from filenames.
    fun registerObservedMedia(rawUrl: String, wasRequested: Boolean = true) {
        val uri = try { URI(rawUrl) } catch (_: Exception) { return }
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        if (uri.scheme != "https" ||
            !(host == "farsiplex.buzz" || host.endsWith(".farsiplex.buzz")) ||
            !Regex("\\.(mp4|m3u8|mpd)$", RegexOption.IGNORE_CASE).containsMatchIn(path)
        ) return
        val quality = Regex("(360|480|720|1080|1440|2160)(?=\\D|$)").find(path)?.value ?: "Auto"
        val source = NativeVideoSource("Source ${nativeSources.size + 1}", rawUrl, quality)
        if (nativeSources.none { it.url == rawUrl }) {
            nativeSources = (nativeSources + source).takeLast(24)
        }
        if (wasRequested && (nativeChoice == null || pendingSourceChange) && !accessPromptActive) {
            pendingSourceChange = false
            nativeChoice = nativeSources.firstOrNull { it.url == rawUrl } ?: source
            playerMessage = "Playing ${quality}p natively"
        }
    }
    // Recheck only when a different primary signed stream is observed.
    // This confirms the existence and access status of alternate resolutions.
    val primarySourceUrl = nativeSources.firstOrNull()?.url
    LaunchedEffect(primarySourceUrl) {
        val primary = nativeSources.firstOrNull() ?: return@LaunchedEffect
        checkingQualityVariants = true
        try {
            val verified = probeAuthorizedQualityVariants(primary)
            if (verified.isNotEmpty()) {
                nativeSources = (nativeSources + verified)
                    .distinctBy { it.url }.takeLast(24)
            }
        } finally {
            checkingQualityVariants = false
        }
    }
    val web = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            // Request the same desktop FarsiPlex layout seen in Chrome on a laptop.
            // Apply before the first load; keep JavaScript, cookies and normal
            // authorized source discovery untouched.
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = true
            // On Fire Stick, the embedded web player may consume D-pad keys.
            // Scroll the *page* with Up/Down when not in provider full screen.
            setOnKeyListener { _, keyCode, event ->
                if (customView == null && event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
                    val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    val pixels = (220 * resources.displayMetrics.density).toInt() * direction
                    evaluateJavascript("""(function(){
                      var amount=$pixels;
                      var root=document.scrollingElement||document.documentElement;
                      var candidates=[root,...document.querySelectorAll('main,[class*="content"],[class*="scroll"]')];
                      var target=candidates.find(function(el){
                        if(!el || !el.scrollHeight)return false;
                        var st=getComputedStyle(el);
                        return el.scrollHeight>el.clientHeight+50 &&
                          (el===root || /auto|scroll/.test(st.overflowY));
                      });
                      if(target)target.scrollTop+=amount;
                      else window.scrollBy(0,amount);
                      document.body&&document.body.scrollBy&&document.body.scrollBy(0,amount);
                    })();""", null)
                    true
                } else false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val observed = request?.url?.toString().orEmpty()
                    if (observed.isNotEmpty() && view != null) {
                        view.post { registerObservedMedia(observed) }
                    }
                    // Observe only; let Chromium handle the original request and
                    // provider access conditions without modifying the response.
                    return null
                }
                override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, pageUrl, favicon)
                    manualContinuation = false
                    lastCompletedPage = ""
                    // Switching source/quality often reloads the SAME final page.
                    // Do not put a blocking spinner over that real player.
                    if (isFinalPlayerPage(pageUrl)) {
                        showWebContent = true
                        playerMessage = "Loading video source…"
                    } else {
                        showWebContent = false
                        availablePlayers = emptyList()
                        availableQualities = emptyList()
                        playerMessage = "Opening the final player…"
                    }
                }
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    lastCompletedPage = loadedUrl.orEmpty()
                    val isFinal = isFinalPlayerPage(loadedUrl)
                    if (view == null) return
                    if (isFinal) {
                        // Do NOT delay reaching the final player for cosmetic scripts.
                        showWebContent = true
                        manualContinuation = false
                        playerMessage = "Choose player or video quality"
                        view.evaluateJavascript(compactPlaybackScript, null)
                        view.evaluateJavascript(playerAdCleaner, null)
                        view.evaluateJavascript(reduceFinalAdSpaceScript, null)
                        view.evaluateJavascript(restorePlayerScrollScript, null)
                        view.evaluateJavascript(isolateFinalPlayerScript, null)
                    }
                    // Probe for the required access dialog promptly on EVERY page.
                    view.evaluateJavascript(startRequiredAccessAdScript) { raw ->
                        val status = raw.removeSurrounding("\"")
                        when (status) {
                            "click-attempted", "click-pending" -> {
                                accessPromptActive = true
                                showWebContent = true
                                manualContinuation = false
                                playerMessage = "Opening the required access ad, if accepted…"
                            }
                            "still-visible", "manual-required", "cooldown" -> {
                                accessPromptActive = true
                                showWebContent = true
                                manualContinuation = false
                                playerMessage = "Tap View a short ad if the prompt remains."
                            }
                            else -> {
                                if (status == "not-shown" && accessPromptActive) {
                                    accessPromptActive = false
                                    if (isFinal) playerMessage = "Choose player or video quality"
                                }
                                if (!isFinal) {
                                    // Continue without waiting for cosmetic ad filtering.
                                    view.evaluateJavascript(followFinalPlayerScript) { result ->
                                        if (result.removeSurrounding("\"") == "unlock-required") {
                                            showWebContent = true
                                            manualContinuation = false
                                            playerMessage = "FarsiPlex requires an access step"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return false
                    val hostname = target.host?.lowercase() ?: return true
                    // Keep top-level redirects to unrelated advertising websites blocked.
                    return request.isForMainFrame && hostname != "farsiplex.com" && hostname != "www.farsiplex.com"
                }
            }
            loadUrl(url)
        }
    }
    // Do not leave the user behind an endless loading mask when a provider
    // requires a real tap or an access prompt inside a protected iframe.
    // Navigation still proceeds automatically while the WebView is visible.
    LaunchedEffect(web) {
        delay(4500)
        if (!showWebContent && nativeChoice == null) {
            showWebContent = true
            manualContinuation = false
            playerMessage = "Continue in the provider player if required"
        }
    }
    // Check newly inserted offerwalls after page load, and keep moving through
    // information/preview pages until the real playback page appears. A new
    // offerwall (e.g., after a quality change) receives its own single attempt.
    LaunchedEffect(web) {
        while (true) {
            val atFinal = isFinalPlayerPage(web.url)
            web.evaluateJavascript(startRequiredAccessAdScript) { raw ->
                when (raw.removeSurrounding("\"")) {
                    "click-attempted", "click-pending" -> {
                        accessPromptActive = true
                        showWebContent = true
                        manualContinuation = false
                        playerMessage = "Opening the required access ad, if accepted…"
                    }
                    "still-visible", "manual-required", "cooldown" -> {
                        accessPromptActive = true
                        showWebContent = true
                        manualContinuation = false
                        playerMessage = "Tap View a short ad if FarsiPlex requires a real touch."
                    }
                    "not-shown" -> {
                        if (accessPromptActive) {
                            accessPromptActive = false
                            if (atFinal) playerMessage = "Choose player or video quality"
                        }
                        if (!atFinal) web.evaluateJavascript(followFinalPlayerScript) { result ->
                            if (result.removeSurrounding("\"") == "unlock-required") {
                                showWebContent = true
                                manualContinuation = false
                            }
                        }
                    }
                }
            }
            if (atFinal && lastCompletedPage.isNotBlank() && isFinalPlayerPage(lastCompletedPage)) {
                showWebContent = true
                manualContinuation = false
            }
            if (atFinal && showWebContent) {
                web.evaluateJavascript(restorePlayerScrollScript, null)
                web.evaluateJavascript(reduceFinalAdSpaceScript, null)
                web.evaluateJavascript(isolateFinalPlayerScript, null)
                web.evaluateJavascript(discoverNativeVideoScript) { raw ->
                    try {
                        val decoded = JSONTokener(raw).nextValue() as? String ?: "[]"
                        val array = JSONArray(decoded)
                        val fresh = (0 until array.length()).mapNotNull { idx ->
                            val obj = array.optJSONObject(idx) ?: return@mapNotNull null
                            val mediaUrl = obj.optString("url")
                            if (!mediaUrl.startsWith("https://")) return@mapNotNull null
                            NativeVideoSource(
                                obj.optString("label", "Video source"),
                                mediaUrl,
                                obj.optString("quality", "Auto")
                            )
                        }
                        fresh.forEach { registerObservedMedia(it.url, wasRequested = false) }
                    } catch (_: Exception) { /* Keep site player if source inspection fails. */ }
                }
                web.evaluateJavascript(findOptionsScript()) { raw ->
                    val decoded = raw.removeSurrounding("\"").replace("\\n", "")
                    val parts = decoded.split('|')
                    val players = parts.getOrNull(0)?.split(',')?.filter { it.matches(Regex("[1-9]")) }.orEmpty()
                    val qualities = parts.getOrNull(1)?.split(',')?.filter {
                        it in setOf("360", "480", "720", "1080", "1440", "2160")
                    }.orEmpty()
                    availablePlayers = players
                    availableQualities = qualities
                    if (players.isNotEmpty() || qualities.isNotEmpty()) {
                        // Do not overwrite a message about an outstanding unlock.
                        if (!accessPromptActive) {
                            playerMessage = "Choose a source or quality below"
                        }
                        web.evaluateJavascript(focusAvailablePlayerScript(), null)
                        web.evaluateJavascript(isolateFinalPlayerScript, null)
                    }
                }
            }
            // Faster discovery on intermediate pages; fewer probes once the
            // real player is visible (to avoid unnecessary TV WebView work).
            delay(if (atFinal) 1400L else 500L)
        }
    }
    // ExoPlayer and a hidden WebView must never play sound simultaneously.
    // Retain the authorized browser session for changing source/quality later.
    LaunchedEffect(nativeChoice, web) {
        if (nativeChoice != null) {
            web.evaluateJavascript(
                """(function(){document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();m.muted=true;}catch(e){}});})();""",
                null
            )
            web.onPause()
        } else {
            web.onResume()
        }
    }
    fun chooseProviderOption(kind: String, value: String) {
        pendingSourceChange = true
        // The original provider page is retained solely for authorized option
        // selection. Resume it briefly to let the actual player make a new request.
        web.onResume()
        web.evaluateJavascript(
            """(function(){document.querySelectorAll('video,audio').forEach(function(m){try{m.muted=true;m.volume=0;}catch(e){}});})();""",
            null
        )
        web.evaluateJavascript(selectOptionScript(kind, value)) { raw ->
            if (!raw.contains("selected")) {
                pendingSourceChange = false
                playerMessage = "This ${kind} option is not accessible in the provider player"
                if (nativeChoice != null) web.onPause()
            } else {
                playerMessage = "Checking available ${kind} ${value} stream…"
            }
        }
    }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.onPause(); web.destroy() } }
    BackHandler(nativeFullscreen) {
        fullscreenControlsVisible = false
        nativeFullscreen = false
    }
    LaunchedEffect(nativeFullscreen) {
        fullscreenControlsVisible = false
        seekBarFocused = false
    }
    LaunchedEffect(fullscreenControlsVisible) {
        if (nativeFullscreen && fullscreenControlsVisible) {
            delay(100)
            if (openOnSeekBar) seekBarRequester.requestFocus()
            else fullscreenMenuRequester.requestFocus()
        }
    }
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
        Column(Modifier.fillMaxSize()) {
            if (!nativeFullscreen) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TvTextButton(onClick = onExit) { Text("← Back to details") }
                Spacer(Modifier.weight(1f))
                Text("RobinPersianFilm Player", fontSize = 14.sp, color = Color.LightGray)
            }
            if (customView == null && !nativeFullscreen) {
                if (nativeSources.isNotEmpty()) {
                    Text("Video options", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    val groupedSources = nativeSources.distinctBy { it.url }
                    val sourceGroups = groupedSources.groupBy(::nativeSourceGroupKey)
                    val activeGroup = nativeChoice?.let(::nativeSourceGroupKey)
                    val displayedQualities = activeGroup?.let { sourceGroups[it] }
                        ?: sourceGroups.values.firstOrNull().orEmpty()
                    Text("Available sources", color = Color.LightGray, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        sourceGroups.entries.forEachIndexed { index, entry ->
                            val candidate = entry.value.firstOrNull { it.quality == nativeChoice?.quality }
                                ?: entry.value.first()
                            TvTextButton(onClick = { nativeChoice = candidate }) {
                                val chosen = activeGroup == entry.key
                                Text("${if (chosen) "✓ " else ""}Source ${index + 1}",
                                    color = if (chosen) Color.White else accent)
                            }
                        }
                        availablePlayers.forEach { number ->
                            TvTextButton(onClick = { chooseProviderOption("player", number) }) {
                                Text("Player $number", color = accent)
                            }
                        }
                    }
                    Text("Quality", color = Color.LightGray, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        displayedQualities.distinctBy { it.quality }.forEach { candidate ->
                            val qualityLabel = if (candidate.quality == "Auto") "Auto" else "${candidate.quality}p"
                            TvTextButton(onClick = { nativeChoice = candidate }) {
                                Text("${if (nativeChoice?.quality == candidate.quality) "✓ " else ""}$qualityLabel",
                                    color = if (nativeChoice?.quality == candidate.quality) Color.White else accent)
                            }
                        }
                        availableQualities.filter { quality -> displayedQualities.none { it.quality == quality } }.forEach { quality ->
                            TvTextButton(onClick = { chooseProviderOption("quality", quality) }) {
                                Text("${quality}p · check", color = accent)
                            }
                        }
                    }
                    if (checkingQualityVariants) {
                        Text("Checking whether 720p or 1080p is available for this episode…",
                            color = Color.LightGray, fontSize = 12.sp)
                    } else if (availableQualities.isEmpty() && displayedQualities.distinctBy { it.quality }.size == 1) {
                        Text("Only confirmed streams are playable directly; other website options must be requested.",
                            color = Color.LightGray, fontSize = 12.sp)
                    }
                    if (pendingSourceChange) Text(playerMessage, color = Color.LightGray, fontSize = 12.sp)
                    if (nativeChoice != null) {
                        TvTextButton(onClick = { nativeFullscreen = true }) {
                            Text("⛶ Full screen", color = Color.White)
                        }
                    }
                }
                if (nativeChoice == null && availablePlayers.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Text("Player:", color = Color.White, fontSize = 13.sp)
                        availablePlayers.forEach { number ->
                            TvTextButton(onClick = {
                                web.evaluateJavascript(selectOptionScript("player", number)) { result ->
                                    if (result.contains("selected")) { selectedPlayer = number; playerMessage = "Player $number selected" }
                                    else playerMessage = "Use the player's own source controls if they are inside an iframe"
                                }
                            }) { Text("${if (selectedPlayer == number) "✓ " else ""}Player $number") }
                        }
                    }
                }
                if (nativeChoice == null && availableQualities.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Text("Quality:", color = Color.White, fontSize = 13.sp)
                        availableQualities.forEach { number ->
                            TvTextButton(onClick = {
                                web.evaluateJavascript(selectOptionScript("quality", number)) { result ->
                                    if (result.contains("selected")) { selectedQuality = number; playerMessage = "$number quality selected" }
                                    else playerMessage = "This quality must be selected inside the video provider"
                                }
                            }) { Text("${if (selectedQuality == number) "✓ " else ""}${number}p") }
                        }
                    }
                }
                if (nativeChoice == null && playerMessage != "Choose player or video quality") {
                    Text(playerMessage, fontSize = 12.sp, color = Color.LightGray, maxLines = 2, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                if (nativeChoice != null) {
                    AndroidView(
                        factory = { PlayerView(it).apply {
                            player = nativePlayer
                            useController = true
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setOnKeyListener { _, code, event ->
                                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) false
                                else if (nativeFullscreen &&
                                    (code == KeyEvent.KEYCODE_DPAD_CENTER ||
                                     code == KeyEvent.KEYCODE_ENTER)) {
                                    showControls(focusProgress = false, pauseVideo = true)
                                    true
                                } else if (nativeFullscreen &&
                                    (code == KeyEvent.KEYCODE_DPAD_LEFT ||
                                     code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                                    showControls(focusProgress = true, pauseVideo = false)
                                    true
                                } else false
                            }
                            requestFocus()
                        } },
                        update = { it.player = nativePlayer },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                }
                // The screen stays clean during playback. Fire Stick OK opens
                // the controls; Up/Down navigates and Back closes them.
                if (nativeFullscreen && nativeChoice != null && fullscreenControlsVisible) {
                    Column(
                        modifier = Modifier.align(Alignment.CenterEnd)
                            .background(Color(0xE8101015), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text("Playback position", color = Color.White, fontSize = 13.sp)
                        TvTextButton(
                            onClick = {
                                nativePlayer.seekTo(seekPosition)
                                seekBarFocused = false
                            },
                            modifier = Modifier.focusRequester(seekBarRequester)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        seekBarFocused = true
                                        seekPosition = nativePlayer.currentPosition.coerceAtLeast(0L)
                                    } else if (seekBarFocused) seekBarFocused = false
                                }
                        ) {
                            val duration = playbackDuration.coerceAtLeast(1L)
                            val progress = (seekPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            val elapsed = seekPosition / 1000L
                            val total = playbackDuration / 1000L
                            val elapsedLabel = "%02d:%02d".format(elapsed / 60, elapsed % 60)
                            val totalLabel = "%02d:%02d".format(total / 60, total % 60)
                            Column(Modifier.widthIn(min = 180.dp, max = 420.dp)) {
                                Box(Modifier.fillMaxWidth().height(6.dp)
                                    .background(Color.DarkGray, RoundedCornerShape(4.dp))) {
                                    Box(Modifier.fillMaxWidth(progress).height(6.dp)
                                        .background(Color(0xFFFFD600), RoundedCornerShape(4.dp)))
                                }
                                Text("$elapsedLabel / $totalLabel   ← 10s  ·  10s →",
                                    color = Color.White, fontSize = 12.sp)
                            }
                        }
                        TvTextButton(
                            onClick = { fullscreenControlsVisible = false },
                            modifier = Modifier.focusRequester(fullscreenMenuRequester)
                        ) { Text("Close controls", color = Color.White) }
                        TvTextButton(onClick = {
                            if (nativePlayer.isPlaying) nativePlayer.pause() else nativePlayer.play()
                        }) { Text("Play / Pause", color = Color.White) }
                        Text("Quality", color = Color.White, fontSize = 13.sp)
                        nativeSources.filter {
                            nativeSourceGroupKey(it) == nativeChoice?.let(::nativeSourceGroupKey)
                        }.distinctBy { it.quality }.forEach { source ->
                            TvTextButton(onClick = {
                                nativeChoice = source
                                fullscreenControlsVisible = false
                            }) {
                                Text("${if (nativeChoice?.url == source.url) "✓ " else ""}${source.quality}p", color = Color.White)
                            }
                        }
                        Text("Source", color = Color.White, fontSize = 13.sp)
                        nativeSources.groupBy(::nativeSourceGroupKey).values.forEachIndexed { index, group ->
                            val source = group.firstOrNull { it.quality == nativeChoice?.quality } ?: group.first()
                            TvTextButton(onClick = {
                                nativeChoice = source
                                fullscreenControlsVisible = false
                            }) { Text("Source ${index + 1}", color = Color.White) }
                        }
                        TvTextButton(onClick = {
                            fullscreenControlsVisible = false
                            nativeFullscreen = false
                        }) { Text("Exit full screen", color = Color.White) }
                    }
                }
                if (!showWebContent && nativeChoice == null) {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = accent)
                            Spacer(Modifier.height(12.dp))
                            Text("Opening the video player…", color = Color.White)
                            Text("Checking the selected video's playback page", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
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

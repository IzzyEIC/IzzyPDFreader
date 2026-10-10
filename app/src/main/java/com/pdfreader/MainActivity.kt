package com.pdfreader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.provider.OpenableColumns
import android.text.InputType
import android.util.LruCache
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.SearchView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class Match(val page: Int, val rects: List<RectF>)
    private class Rendered(val bitmap: Bitmap, val gen: Int)
    private class Recent(val uri: String, val name: String) {
        override fun toString() = name
    }

    // PdfRenderer nie jest thread-safe — wszystkie operacje na dokumencie idą przez ten jeden wątek.
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Używane tylko na wątku worker.
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var searcherPreV: AutoCloseable? = null

    @Volatile private var docGen = 0
    @Volatile private var renderGen = 0

    private var currentUri: Uri? = null
    private var pageSizes: List<Pair<Int, Int>> = emptyList()

    private val pending = HashSet<Int>()
    private val cache = object : LruCache<Int, Rendered>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: Int, value: Rendered) = value.bitmap.byteCount
    }

    private var searchGen = 0
    private var query = ""
    private var matches: List<Match> = emptyList()
    private var currentMatch = -1

    private lateinit var list: ZoomListView
    private lateinit var home: View
    private lateinit var recentTitle: View
    private lateinit var recentList: ListView
    private val adapter = PageAdapter()

    private var searchItem: MenuItem? = null
    private var prevItem: MenuItem? = null
    private var nextItem: MenuItem? = null
    private var gotoItem: MenuItem? = null

    private val prefs by lazy { getSharedPreferences("state", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.pages)
        home = findViewById(R.id.home)
        recentTitle = findViewById(R.id.recent_title)
        recentList = findViewById(R.id.recent)

        list.adapter = adapter
        list.onZoomEnd = { adapter.notifyDataSetChanged() }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = updateSubtitle()
        })

        findViewById<View>(R.id.open).setOnClickListener { pickFile() }
        recentList.setOnItemClickListener { _, _, position, _ ->
            open(Uri.parse(loadRecents()[position].uri))
        }
        recentList.setOnItemLongClickListener { _, _, position, _ ->
            removeRecent(loadRecents()[position].uri)
            true
        }

        val uri = intent?.data ?: savedInstanceState?.getString(KEY_URI)?.let(Uri::parse)
        if (uri != null) open(uri) else showHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { open(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUri?.let { outState.putString(KEY_URI, it.toString()) }
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentUri != null) {
            savePosition()
            closeCurrent()
            showHome()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        docGen++
        worker.execute { closeDocument() }
        worker.shutdown()
    }

    // ---------- Menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val searchView = SearchView(this).apply {
            queryHint = getString(R.string.search_hint)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String): Boolean {
                    clearFocus()
                    if (text == query && matches.isNotEmpty()) stepMatch(1) else startSearch(text)
                    return true
                }

                override fun onQueryTextChange(text: String) = false
            })
        }
        searchItem = menu.add(0, ID_SEARCH, 0, R.string.search)
            .setIcon(android.R.drawable.ic_menu_search)
            .setActionView(searchView)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
            .setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem) = true
                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    clearSearch()
                    return true
                }
            })
        prevItem = menu.add(0, ID_PREV, 1, R.string.prev_match)
            .setIcon(android.R.drawable.arrow_up_float)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
        nextItem = menu.add(0, ID_NEXT, 2, R.string.next_match)
            .setIcon(android.R.drawable.arrow_down_float)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
        gotoItem = menu.add(0, ID_GOTO, 3, R.string.go_to_page)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, ID_OPEN, 4, R.string.open)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER)
        updateMenu()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            ID_OPEN -> pickFile()
            ID_GOTO -> showGoToPage()
            ID_PREV -> stepMatch(-1)
            ID_NEXT -> stepMatch(1)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // Widoczność ustawiana bezpośrednio — invalidateOptionsMenu zwinęłoby otwarte pole wyszukiwania.
    private fun updateMenu() {
        val docOpen = currentUri != null && pageSizes.isNotEmpty()
        searchItem?.isVisible = docOpen && searchSupported()
        gotoItem?.isVisible = docOpen
        prevItem?.isVisible = matches.size > 1
        nextItem?.isVisible = matches.size > 1
    }

    // ---------- Otwieranie / zamykanie ----------

    private fun pickFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
        startActivityForResult(pick, REQUEST_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && uri != null) {
            // Trwały dostęp, żeby plik dało się otworzyć ponownie z listy ostatnich.
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            open(uri)
        }
    }

    private fun open(uri: Uri) {
        savePosition()
        closeCurrent()
        val gen = docGen
        worker.execute {
            closeDocument()
            val newFd = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            if (newFd == null) {
                main.post { if (gen == docGen) openFailed(uri, R.string.error_open, forget = true) }
                return@execute
            }
            try {
                val newRenderer = PdfRenderer(newFd)
                val sizes = (0 until newRenderer.pageCount).map { i ->
                    newRenderer.openPage(i).use { it.width to it.height }
                }
                fd = newFd
                renderer = newRenderer
                if (Build.VERSION.SDK_INT < 35 && searchSupported()) {
                    searcherPreV = runCatching { openPreV(uri) }.getOrNull()
                }
                main.post {
                    if (gen != docGen) return@post
                    showDocument(uri, sizes)
                }
            } catch (e: Exception) {
                runCatching { newFd.close() }
                closeDocument()
                // PdfRenderer rzuca SecurityException dla plików z hasłem.
                val message = if (e is SecurityException) R.string.error_protected else R.string.error_open
                main.post { if (gen == docGen) openFailed(uri, message, forget = false) }
            }
        }
    }

    private fun showDocument(uri: Uri, sizes: List<Pair<Int, Int>>) {
        currentUri = uri
        pageSizes = sizes
        val name = displayName(uri)
        addRecent(uri.toString(), name)
        title = name
        home.visibility = View.GONE
        list.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
        restorePosition(uri)
        updateMenu()
        updateSubtitle()
    }

    private fun openFailed(uri: Uri, message: Int, forget: Boolean) {
        toast(message)
        // Plik z listy ostatnich mógł zniknąć albo stracić uprawnienia.
        if (forget) removeRecent(uri.toString())
        showHome()
    }

    /** Czyści stan bieżącego dokumentu po stronie UI; sam plik zamyka worker. */
    private fun closeCurrent() {
        docGen++
        renderGen++
        currentUri = null
        pageSizes = emptyList()
        pending.clear()
        cache.evictAll()
        searchGen++
        query = ""
        matches = emptyList()
        currentMatch = -1
        searchItem?.collapseActionView()
        list.resetZoom()
        adapter.notifyDataSetChanged()
        worker.execute { closeDocument() }
    }

    private fun closeDocument() {
        runCatching { searcherPreV?.close() }
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        searcherPreV = null
        renderer = null
        fd = null
    }

    private fun showHome() {
        title = getString(R.string.app_name)
        actionBar?.subtitle = null
        list.visibility = View.GONE
        home.visibility = View.VISIBLE
        val recents = loadRecents()
        recentTitle.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        recentList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, recents)
        updateMenu()
    }

    // ---------- Renderowanie ----------

    private fun itemWidth() = list.width - list.paddingLeft - list.paddingRight

    private fun itemHeight(page: Int): Int {
        val (pw, ph) = pageSizes[page]
        return itemWidth() * ph / pw
    }

    // Po przybliżeniu renderujemy w 2x, żeby tekst był ostry (więcej nie — pamięć).
    private fun renderWidth() = itemWidth() * (if (list.zoom > 1.3f) 2 else 1)

    private fun requestRender(index: Int, width: Int) {
        if (!pending.add(index)) return
        val gen = renderGen
        val highlights = matches.filter { it.page == index }
        val current = matches.getOrNull(currentMatch)?.takeIf { it.page == index }
        worker.execute {
            val r = renderer
            val bitmap = if (r != null && gen == renderGen && index < r.pageCount) {
                try {
                    r.openPage(index).use { page -> renderPage(page, width, highlights, current) }
                } catch (e: OutOfMemoryError) {
                    null
                }
            } else null
            main.post {
                pending.remove(index)
                if (bitmap != null && gen == renderGen) cache.put(index, Rendered(bitmap, gen))
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun renderPage(page: PdfRenderer.Page, width: Int, highlights: List<Match>, current: Match?): Bitmap {
        val height = width * page.height / page.width
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        if (highlights.isNotEmpty()) {
            val canvas = Canvas(bitmap)
            val scale = width.toFloat() / page.width
            canvas.scale(scale, scale)
            for (m in highlights) {
                val paint = if (m === current) currentPaint else highlightPaint
                m.rects.forEach { canvas.drawRect(it, paint) }
            }
        }
        return bitmap
    }

    private val highlightPaint = Paint().apply { color = Color.argb(90, 255, 213, 0) }
    private val currentPaint = Paint().apply { color = Color.argb(130, 255, 109, 0) }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = pageSizes.size
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? ImageView) ?: ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
            }
            val width = itemWidth()
            if (width <= 0) return view
            view.layoutParams = AbsListView.LayoutParams(width, itemHeight(position))

            // Pokazujemy to, co jest w cache (nawet nieaktualne), żeby nie migało przy odświeżaniu.
            val cached = cache.get(position)
            view.setImageBitmap(cached?.bitmap)
            val target = renderWidth()
            if (cached == null || cached.gen != renderGen || cached.bitmap.width != target) {
                requestRender(position, target)
            }
            return view
        }
    }

    // ---------- Pozycja i nawigacja ----------

    private fun currentPage(): Int {
        val pos = list.pointToPosition(list.width / 2, list.height / 2)
        return if (pos != ListView.INVALID_POSITION) pos else list.firstVisiblePosition
    }

    private fun updateSubtitle() {
        if (currentUri == null || pageSizes.isEmpty()) return
        actionBar?.subtitle = when {
            currentMatch >= 0 -> getString(R.string.match_of, currentMatch + 1, matches.size)
            else -> getString(R.string.page_of, currentPage() + 1, pageSizes.size)
        }
    }

    private fun scrollToPage(page: Int, offsetInPage: Int = 0) {
        list.resetZoom()
        list.setSelectionFromTop(page.coerceIn(0, pageSizes.size - 1), -offsetInPage)
    }

    private fun savePosition() {
        val uri = currentUri ?: return
        val child = list.getChildAt(0) ?: return
        if (child.height <= 0) return
        val fraction = -child.top.toFloat() / child.height
        prefs.edit().putString(KEY_POS + uri, "${list.firstVisiblePosition}:$fraction").apply()
    }

    private fun restorePosition(uri: Uri) {
        val saved = prefs.getString(KEY_POS + uri, null)?.split(":") ?: return
        val page = saved.getOrNull(0)?.toIntOrNull() ?: return
        val fraction = saved.getOrNull(1)?.toFloatOrNull() ?: 0f
        list.post {
            if (currentUri != uri || page >= pageSizes.size) return@post
            scrollToPage(page, (fraction * itemHeight(page)).toInt())
        }
    }

    private fun showGoToPage() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1–${pageSizes.size}"
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.go_to_page)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.let { scrollToPage(it - 1) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            true
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    // ---------- Wyszukiwanie ----------

    // Wyszukiwanie tekstu jest w systemie od Androida 15, a na Androidzie 11–14
    // przez aktualizację systemową (SDK Extensions 13). Na starszych przycisk jest ukryty.
    private fun searchSupported(): Boolean = Build.VERSION.SDK_INT >= 35 ||
        (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)

    @SuppressLint("NewApi")
    private fun openPreV(uri: Uri): AutoCloseable? {
        val searchFd = contentResolver.openFileDescriptor(uri, "r") ?: return null
        return PdfRendererPreV(searchFd)
    }

    /** Wywoływane na wątku worker. */
    @SuppressLint("NewApi")
    private fun searchPage(index: Int, text: String): List<Match> {
        val found = if (Build.VERSION.SDK_INT >= 35) {
            renderer?.openPage(index)?.use { it.searchText(text) }
        } else {
            (searcherPreV as? PdfRendererPreV)?.openPage(index)?.use { it.searchText(text) }
        }
        return found.orEmpty().map { Match(index, it.bounds) }
    }

    private fun startSearch(text: String) {
        val gen = ++searchGen
        query = text
        matches = emptyList()
        currentMatch = -1
        updateMenu()
        if (text.isBlank()) return refreshHighlights()
        actionBar?.subtitle = getString(R.string.searching)

        val results = ArrayList<Match>()
        val startPage = currentPage()
        val pageCount = pageSizes.size
        // Każda strona to osobne zadanie, żeby renderowanie mogło się wcisnąć pomiędzy.
        fun step(page: Int) {
            worker.execute {
                if (gen != searchGen) return@execute
                if (page < pageCount) {
                    runCatching { results += searchPage(page, text) }
                    step(page + 1)
                } else {
                    main.post { if (gen == searchGen) searchFinished(results, startPage) }
                }
            }
        }
        step(0)
    }

    private fun searchFinished(results: List<Match>, startPage: Int) {
        matches = results
        updateMenu()
        if (results.isEmpty()) {
            toast(R.string.not_found)
            updateSubtitle()
            return
        }
        currentMatch = results.indexOfFirst { it.page >= startPage }.takeIf { it >= 0 } ?: 0
        refreshHighlights()
        showCurrentMatch()
    }

    private fun stepMatch(delta: Int) {
        if (matches.isEmpty()) return
        currentMatch = (currentMatch + delta).mod(matches.size)
        refreshHighlights()
        showCurrentMatch()
    }

    private fun showCurrentMatch() {
        val m = matches[currentMatch]
        val top = m.rects.firstOrNull()?.top ?: 0f
        val scale = itemWidth().toFloat() / pageSizes[m.page].first
        // Wynik mniej więcej w 1/3 wysokości ekranu.
        scrollToPage(m.page, (top * scale - list.height / 3).toInt())
        updateSubtitle()
    }

    private fun clearSearch() {
        searchGen++
        query = ""
        matches = emptyList()
        currentMatch = -1
        refreshHighlights()
        updateMenu()
        updateSubtitle()
    }

    private fun refreshHighlights() {
        renderGen++
        pending.clear()
        adapter.notifyDataSetChanged()
    }

    // ---------- Ostatnio otwierane ----------

    private fun loadRecents(): List<Recent> {
        val json = runCatching { JSONArray(prefs.getString(KEY_RECENT, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until json.length()).map {
            val o = json.getJSONObject(it)
            Recent(o.getString("uri"), o.getString("name"))
        }
    }

    private fun saveRecents(recents: List<Recent>) {
        val json = JSONArray()
        recents.take(MAX_RECENT).forEach { json.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        prefs.edit().putString(KEY_RECENT, json.toString()).apply()
    }

    private fun addRecent(uri: String, name: String) {
        saveRecents(listOf(Recent(uri, name)) + loadRecents().filter { it.uri != uri })
    }

    private fun removeRecent(uri: String) {
        saveRecents(loadRecents().filter { it.uri != uri })
        prefs.edit().remove(KEY_POS + uri).apply()
        if (currentUri == null) showHome()
    }

    // ---------- Pomocnicze ----------

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        }
        return uri.lastPathSegment ?: getString(R.string.app_name)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_PICK = 1
        private const val ID_SEARCH = 1
        private const val ID_PREV = 2
        private const val ID_NEXT = 3
        private const val ID_GOTO = 4
        private const val ID_OPEN = 5
        private const val MAX_RECENT = 15
        private const val KEY_URI = "uri"
        private const val KEY_POS = "pos:"
        private const val KEY_RECENT = "recent"
    }
}

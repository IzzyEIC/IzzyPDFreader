package com.pdfreader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    // PdfRenderer nie jest thread-safe — wszystkie operacje na nim idą przez ten jeden wątek.
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageSizes: List<Pair<Int, Int>> = emptyList()
    private var generation = 0

    private val pending = HashSet<Int>()
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    private lateinit var list: ListView
    private lateinit var openButton: Button
    private val adapter = PageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.pages)
        openButton = findViewById(R.id.open)
        list.adapter = adapter
        openButton.setOnClickListener { pickFile() }

        intent?.data?.let { open(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { open(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.open)
            .setIcon(android.R.drawable.ic_menu_upload)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            pickFile()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun pickFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
        startActivityForResult(pick, REQUEST_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            data?.data?.let { open(it) }
        }
    }

    private fun open(uri: Uri) {
        val gen = ++generation
        worker.execute {
            closeDocument()
            try {
                val newFd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("null descriptor")
                val newRenderer = PdfRenderer(newFd)
                val sizes = (0 until newRenderer.pageCount).map { i ->
                    newRenderer.openPage(i).use { it.width to it.height }
                }
                fd = newFd
                renderer = newRenderer
                main.post {
                    if (gen != generation) return@post
                    pageSizes = sizes
                    cache.evictAll()
                    pending.clear()
                    title = displayName(uri)
                    openButton.visibility = View.GONE
                    adapter.notifyDataSetChanged()
                    list.setSelection(0)
                }
            } catch (e: SecurityException) {
                closeDocument()
                main.post { toast(R.string.error_protected) }
            } catch (e: Exception) {
                closeDocument()
                main.post { toast(R.string.error_open) }
            }
        }
    }

    private fun closeDocument() {
        renderer?.close()
        fd?.close()
        renderer = null
        fd = null
    }

    private fun requestRender(index: Int, width: Int) {
        if (!pending.add(index)) return
        val gen = generation
        worker.execute {
            val r = renderer
            val bitmap = if (r != null && gen == generation && index < r.pageCount) {
                r.openPage(index).use { page ->
                    val height = width * page.height / page.width
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } else null
            main.post {
                if (gen != generation) return@post
                pending.remove(index)
                if (bitmap != null) {
                    cache.put(index, bitmap)
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        }
        return uri.lastPathSegment ?: getString(R.string.app_name)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        generation++
        worker.execute { closeDocument() }
        worker.shutdown()
    }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = pageSizes.size
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? ImageView) ?: ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
            }
            val width = parent.width - parent.paddingLeft - parent.paddingRight
            if (width <= 0) return view

            val (pw, ph) = pageSizes[position]
            view.layoutParams = AbsListView.LayoutParams(width, width * ph / pw)

            val cached = cache.get(position)
            view.setImageBitmap(cached)
            // Brak w cache albo zmiana szerokości (np. obrót ekranu) — renderuj ponownie.
            if (cached == null || cached.width != width) requestRender(position, width)
            return view
        }
    }

    companion object {
        private const val REQUEST_PICK = 1
    }
}

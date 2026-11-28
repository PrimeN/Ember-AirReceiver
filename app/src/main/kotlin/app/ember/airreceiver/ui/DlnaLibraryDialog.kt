package app.ember.airreceiver.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.ember.airreceiver.R
import app.ember.airreceiver.dlna.DlnaLibraryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal class DlnaLibraryDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val play: suspend (String) -> Boolean
) {
    private data class Folder(val name: String, val id: String)

    private val client = DlnaLibraryClient()
    private val folders = ArrayDeque<Folder>()
    private val pages = ArrayDeque<Int>()
    private var selectedServer: DlnaLibraryClient.Server? = null
    private var servers = emptyList<DlnaLibraryClient.Server>()
    private var task: Job? = null
    private val status = TextView(context)
    private val list = ListView(context)
    private val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(status)
        addView(list, LinearLayout.LayoutParams(-1, (300 * resources.displayMetrics.density).toInt()))
    }
    private val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.dlna_library)
        .setView(view)
        .setNegativeButton(R.string.dlna_library_dismiss, null)
        .create()

    fun show() {
        dialog.setOnDismissListener { task?.cancel() }
        dialog.show()
        discover()
    }

    fun dismiss() = dialog.dismiss()

    private fun showRows(rows: List<Pair<String, () -> Unit>>) {
        list.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, rows.map { it.first })
        list.setOnItemClickListener { _, _, index, _ -> rows.getOrNull(index)?.second?.invoke() }
        if (rows.isNotEmpty()) list.requestFocus()
    }

    private fun showServerList(emptyMessage: Int = R.string.dlna_library_empty) {
        dialog.setTitle(R.string.dlna_library)
        status.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
        status.setText(emptyMessage)
        val rows = servers.map { server ->
            server.title to {
                selectedServer = server
                folders.clear()
                folders.addLast(Folder(server.title, "0"))
                pages.clear()
                browse(0)
            }
        }
        showRows(rows + (context.getString(R.string.dlna_library_retry) to ::discover))
    }

    private fun discover() {
        task?.cancel()
        status.visibility = View.VISIBLE
        status.setText(R.string.dlna_library_searching)
        showRows(emptyList())
        task = scope.launch {
            try {
                servers = runInterruptible(Dispatchers.IO, client::discover)
                showServerList()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showServerList(R.string.dlna_library_error)
            }
        }
    }

    private fun browse(offset: Int) {
        val server = selectedServer ?: return
        val folder = folders.lastOrNull() ?: return
        task?.cancel()
        dialog.setTitle(folder.name)
        status.visibility = View.VISIBLE
        status.setText(R.string.dlna_library_loading)
        showRows(emptyList())
        task = scope.launch {
            val back = context.getString(R.string.dlna_library_back) to {
                folders.removeLast()
                pages.clear()
                if (folders.isEmpty()) showServerList() else browse(0)
            }
            try {
                val page = runInterruptible(Dispatchers.IO) { client.browse(server, folder.id, offset) }
                status.visibility = if (page.entries.isEmpty()) View.VISIBLE else View.GONE
                status.setText(R.string.dlna_library_folder_empty)
                val rows = buildList<Pair<String, () -> Unit>> {
                    add(back)
                    pages.lastOrNull()?.let { previous ->
                        add(context.getString(R.string.dlna_library_previous) to {
                            pages.removeLast()
                            browse(previous)
                        })
                    }
                    page.entries.forEach { entry ->
                        add(entry.title to {
                            val id = entry.container
                            if (id != null) {
                                folders.addLast(Folder(entry.title, id))
                                pages.clear()
                                browse(0)
                            } else {
                                entry.uri?.let(::playEntry)
                            }
                        })
                    }
                    page.nextOffset?.let { next ->
                        add(context.getString(R.string.dlna_library_next) to {
                            pages.addLast(offset)
                            browse(next)
                        })
                    }
                }
                showRows(rows)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status.setText(R.string.dlna_library_error)
                showRows(listOf(back, context.getString(R.string.dlna_library_retry) to { browse(offset) }))
            }
        }
    }

    private fun playEntry(url: String) {
        task?.cancel()
        list.isEnabled = false
        task = scope.launch {
            try {
                if (play(url)) dismiss()
                else {
                    status.visibility = View.VISIBLE
                    status.setText(R.string.dlna_library_unavailable)
                }
            } finally {
                list.isEnabled = true
            }
        }
    }
}

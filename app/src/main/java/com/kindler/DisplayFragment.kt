package com.kindler

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File
import java.io.IOException

class DisplayFragment : Fragment() {

    companion object {
        private const val TAG = "DisplayFragment"
        private const val HIGHLIGHTS_FILE_NAME = "kindle_highlights.json"
    }

    private lateinit var contentLayout: LinearLayout
    private lateinit var loadMoreButton: Button
    private lateinit var highlightsFileStore: HighlightsFileStore

    private var hasMoreBooks = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_display, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        contentLayout = view.findViewById(R.id.contentLayout)
        loadMoreButton = view.findViewById(R.id.loadMoreButton)

        scrollView.viewTreeObserver.addOnScrollChangedListener {
            updateLoadMoreButton()
        }

        highlightsFileStore = HighlightsFileStore(
            File(requireContext().filesDir, HIGHLIGHTS_FILE_NAME)
        )

        loadMoreButton.setOnClickListener {
            loadBooks()
        }

        loadInitialBooks()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    private fun loadInitialBooks() {
        hasMoreBooks = false
        contentLayout.removeAllViews()
        loadBooks(fromStart = true)
    }

    private fun loadBooks(fromStart: Boolean = false) {
        loadMoreButton.isEnabled = false
        try {
            val result = highlightsFileStore.loadBooks(
                limit = HighlightsFileStore.BOOKS_PER_PAGE,
                fromStart = fromStart
            )

            if (fromStart) {
                contentLayout.removeAllViews()
            }

            val books = result.books
            hasMoreBooks = result.hasMore

            if (fromStart && books.isEmpty()) {
                showEmptyState()
                return
            }

            books.forEach { book ->
                addBookView(book)
            }

            updateLoadMoreButton()
        } catch (e: IOException) {
            Log.e(TAG, "Error loading highlights", e)
            showCorruptedState()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading highlights", e)
            showCorruptedState()
        } finally {
            updateLoadMoreButton()
        }
    }

    private fun updateLoadMoreButton() {
        val shouldShowLoadMore = hasMoreBooks && isScrolledToBottom()

        if (shouldShowLoadMore) {
            loadMoreButton.visibility = View.VISIBLE
            loadMoreButton.text = getString(R.string.load_more)
        } else {
            loadMoreButton.visibility = View.GONE
        }
        loadMoreButton.isEnabled = hasMoreBooks
    }

    private fun addBookView(book: BookEntry) {
        val bookContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(24))
            }
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // Book title
        val titleView = TextView(requireContext()).apply {
            text = book.title
            textSize = 20f
            setTextColor(0xFF000000.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(4))
            }
        }
        bookContainer.addView(titleView)

        // Book author
        val authorView = TextView(requireContext()).apply {
            text = book.author
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(4))
            }
        }
        bookContainer.addView(authorView)

        // Last accessed date
        val dateView = TextView(requireContext()).apply {
            text = getString(R.string.last_accessed, book.lastAccessedDate)
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(12))
            }
        }
        bookContainer.addView(dateView)

        // Highlights and notes
        if (book.highlights.isNotEmpty()) {
            book.highlights.forEach { highlightEntry ->
                val highlightContainer = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dpToPx(8), 0, 0, dpToPx(12))
                    }
                }

                // Highlight text
                if (highlightEntry.highlight.isNotEmpty()) {
                    val highlightView = TextView(requireContext()).apply {
                        text = getString(R.string.highlight_quote, highlightEntry.highlight)
                        textSize = 14f
                        setTextColor(0xFF000000.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, dpToPx(4))
                        }
                    }
                    highlightContainer.addView(highlightView)
                }

                // Note text
                if (highlightEntry.note.isNotEmpty()) {
                    val noteView = TextView(requireContext()).apply {
                        text = getString(R.string.note_prefix, highlightEntry.note)
                        textSize = 13f
                        setTextColor(0xFF666666.toInt())
                        setTypeface(null, android.graphics.Typeface.ITALIC)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    highlightContainer.addView(noteView)
                }

                bookContainer.addView(highlightContainer)
            }
        } else {
            val noHighlightsView = TextView(requireContext()).apply {
                text = getString(R.string.no_highlights_for_book)
                textSize = 13f
                setTextColor(0xFF666666.toInt())
                setTypeface(null, android.graphics.Typeface.ITALIC)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dpToPx(8), 0, 0, 0)
                }
            }
            bookContainer.addView(noHighlightsView)
        }

        contentLayout.addView(bookContainer)
    }

    private fun showEmptyState() {
        hasMoreBooks = false
        contentLayout.removeAllViews()
        loadMoreButton.visibility = View.GONE

        val emptyView = TextView(requireContext()).apply {
            text = getString(R.string.no_highlights_stored)
            textSize = 16f
            setTextColor(0xFF666666.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(dpToPx(32), dpToPx(64), dpToPx(32), dpToPx(64))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        contentLayout.addView(emptyView)
    }

    private fun showCorruptedState() {
        hasMoreBooks = false
        contentLayout.removeAllViews()
        loadMoreButton.visibility = View.GONE

        val errorView = TextView(requireContext()).apply {
            text = getString(R.string.error_loading_highlights)
            textSize = 16f
            setTextColor(0xFFCC0000.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(dpToPx(32), dpToPx(64), dpToPx(32), dpToPx(64))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        contentLayout.addView(errorView)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}

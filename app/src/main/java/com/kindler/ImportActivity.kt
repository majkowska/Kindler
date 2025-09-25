package com.kindler

import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets


class ImportActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ImportActivity"
        private const val BASE_URL = "https://read.amazon.com/"
        private const val NOTEBOOK_URL = "${BASE_URL}notebook"
        private const val HIGHLIGHT_URL_PREFIX = "${BASE_URL}notebook?asin="
        private const val HIGHLIGHT_URL_SUFFIX = "&contentLimitState="
        private const val SIGN_IN_URL_PREFIX = "https://www.amazon.com/ap/signin"
        private const val HIGHLIGHTS_FILE_NAME = "kindle_highlights.json"
    }

    private val importStateMachine = NotebookImportStateMachine()
    private lateinit var myWebView: WebView
    private lateinit var overlayLayout: LinearLayout
    private lateinit var startImportButton: Button
    private lateinit var promptTextView: TextView
    private lateinit var highlightsFileStore: HighlightsFileStore
    private var highlightsStoreResetThisRun = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)

        myWebView = findViewById(R.id.webView)
        overlayLayout = findViewById(R.id.overlayLayout)
        startImportButton = findViewById(R.id.startImportButton)
        promptTextView = findViewById(R.id.promptTextView)
        highlightsFileStore = HighlightsFileStore(File(filesDir, HIGHLIGHTS_FILE_NAME))

        myWebView.settings.javaScriptEnabled = true
        myWebView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        myWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "New page finished loading: $url")
                Log.d(TAG, "Current state: ${importStateMachine.state}")
                when (importStateMachine.state) {
                    ImportState.INITIAL -> checkLoginAndUrlStatus()
                    ImportState.LOADING_BOOK_LIST -> verifyUrlAndProceed(NOTEBOOK_URL, url, "extract_book_list.js")
                    ImportState.LOADING_HIGHLIGHTS -> verifyUrlAndProceed(HIGHLIGHT_URL_PREFIX, url, "extract_highlights.js")
                    else -> {
                        // No action needed for other states
                    }
                }
            }
        }

        startImportButton.setOnClickListener {
            overlayLayout.visibility = View.GONE
            startImportProcess()
        }
        
        myWebView.loadUrl(NOTEBOOK_URL)
    }

    private fun checkLoginAndUrlStatus() {
        val script = """
        (function() {
            var isLoggedIn = document.getElementById('kp-notebook-library') !== null;
            var isOnNotebookPage = window.location.href.startsWith('${NOTEBOOK_URL}');
            var isOnSignInPage = window.location.href.startsWith('${SIGN_IN_URL_PREFIX}');
            if (typeof AndroidInterface !== 'undefined' && AndroidInterface.reportUiStatus) {
                AndroidInterface.reportUiStatus(isLoggedIn, isOnNotebookPage, isOnSignInPage);
            }
        })();
        """.trimIndent()
        myWebView.evaluateJavascript(script, null)
    }
    
    private fun terminateProcess(finalState: ImportState) {
        if (finalState != ImportState.FINISHED && finalState != ImportState.ERROR) {
            Log.w(TAG, "terminateProcess called with non-terminal state: $finalState")
            return
        }
        when (finalState) {
            ImportState.FINISHED -> importStateMachine.markFinished()
            ImportState.ERROR -> importStateMachine.markError()
            else -> { /* Already validated above */ }
        }
        runOnUiThread {
            overlayLayout.visibility = View.VISIBLE
            checkLoginAndUrlStatus()
        }
    }

    private fun startImportProcess(){
        highlightsStoreResetThisRun = false
        importStateMachine.startImport()
        Log.d(TAG, "Starting import process. Loading book list page: $NOTEBOOK_URL")
        myWebView.loadUrl(NOTEBOOK_URL)
    }

    private fun verifyUrlAndProceed(expectedUrlPrefix: String, actualUrl: String?, scriptToLoad: String) {
        if (actualUrl?.startsWith(expectedUrlPrefix) == true) {
            loadAndExecuteJavascript(myWebView, scriptToLoad)
        } else {
            Log.e(TAG, "URL Mismatch! State: ${importStateMachine.state}, Expected prefix: '$expectedUrlPrefix', Actual: '$actualUrl'.")
            terminateProcess(ImportState.ERROR)
        }
    }

    private fun loadNextBookHighlights() {
        if (importStateMachine.state != ImportState.LOADING_HIGHLIGHTS) {
            Log.e(TAG, "loadNextBookHighlights called in a wrong state ${importStateMachine.state}. Aborting.")
            return
        }
        val book = importStateMachine.currentBook()
        if (book == null) {
            Log.e(
                TAG,
                "loadNextBookHighlights called with invalid index ${importStateMachine.currentBookIndex}. " +
                        "Book list size: ${importStateMachine.totalBooks}. Aborting."
            )
            return
        }
        val highlightsUrl = "$HIGHLIGHT_URL_PREFIX${book.asin}$HIGHLIGHT_URL_SUFFIX"
        Log.i(TAG, "Loading highlights for book '${book.title}' (ASIN: ${book.asin}) from $highlightsUrl")
        myWebView.loadUrl(highlightsUrl)
    }

    private fun loadAndExecuteJavascript(webView: WebView?, scriptName: String) {
        val javascript: String
        try {
            val inputStream = assets.open(scriptName)
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            javascript = String(buffer, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            Log.e(TAG, "Error reading $scriptName from assets", e)
            terminateProcess(ImportState.ERROR)
            return
        }
        Log.d(TAG, "Executing $scriptName")
        webView?.evaluateJavascript(javascript, null)
    }

    private fun ensureHighlightsStoreReady(): Boolean {
        if (highlightsStoreResetThisRun) {
            return true
        }
        return try {
            highlightsFileStore.reset()
            highlightsStoreResetThisRun = true
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to reset highlights storage", e)
            terminateProcess(ImportState.ERROR)
            false
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun reportUiStatus(isLoggedIn: Boolean, isOnNotebookPage: Boolean, isOnSignInPage: Boolean) {
            runOnUiThread {
                if (isLoggedIn && isOnNotebookPage) {
                    promptTextView.text = "Import your Kindle books and highlights."
                    startImportButton.visibility = View.VISIBLE
                } else if (isOnSignInPage || isOnNotebookPage) {
                    promptTextView.text = "Please log in to your Amazon account to continue."
                    startImportButton.visibility = View.GONE
                } else{
                    promptTextView.text = "Please navigate to your Kindle Notebook page."
                    startImportButton.visibility = View.GONE
                }
            }
        }

        @JavascriptInterface
        fun processBookData(bookDataJson: String) {
            if (importStateMachine.state != ImportState.LOADING_BOOK_LIST) return
            Log.i(TAG, "Received book list data. Processing...")
            NotebookJsonParser.parseBookEntries(bookDataJson)
                .onSuccess { parsedBooks ->
                    Log.i(TAG, "Total books found on main page: ${parsedBooks.size}")
                    when (val result = importStateMachine.onBooksParsed(parsedBooks)) {
                        NotebookImportStateMachine.BooksUpdateResult.NoBooks -> {
                            Log.i(TAG, "No books found in the list. Finishing process.")
                            terminateProcess(ImportState.FINISHED)
                        }
                        is NotebookImportStateMachine.BooksUpdateResult.Ready -> {
                            runOnUiThread { loadNextBookHighlights() }
                        }
                        is NotebookImportStateMachine.BooksUpdateResult.InvalidState -> {
                            Log.e(TAG, "Received book data while in invalid state ${result.state}.")
                            terminateProcess(ImportState.ERROR)
                        }
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Error processing book list data: ", e)
                    terminateProcess(ImportState.ERROR)
                }
        }

        @JavascriptInterface
        fun processBookHighlights(highlightsJson: String) {
            if (importStateMachine.state != ImportState.LOADING_HIGHLIGHTS) return
            val currentBook = importStateMachine.currentBook()
            if (currentBook == null || currentBook.asin.isEmpty())  {
                Log.e(TAG, "No book data available for the current book. Aborting.")
                terminateProcess(ImportState.ERROR)
                return
            }
            val parseResult = NotebookJsonParser.parseHighlights(highlightsJson)
            val highlights = parseResult.getOrNull()
            if (highlights != null) {
                if (!ensureHighlightsStoreReady()) {
                    return
                }
                try {
                    highlightsFileStore.addBookHighlights(currentBook, highlights)
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to persist highlights for ASIN $currentBook.asin", e)
                }
                if (highlights.isEmpty()) {
                    Log.i(TAG, "No highlights found for book '$currentBook.bookTitle' (ASIN: $currentBook.asin).")
                } else {
                    highlights.forEach { highlightEntry ->
                        Log.i(
                            TAG,
                            "ASIN: $currentBook.asin - Highlight: \"${highlightEntry.highlight}\" --- Note: \"${highlightEntry.note}\""
                        )
                    }
                }
            } else {
                parseResult.exceptionOrNull()?.let { e ->
                    Log.e(TAG, "Error processing highlights for book '$currentBook.bookTitle' (ASIN: $currentBook.asin): ", e)
                    // we continue to process highlights even though there's an error
                }
            }

            when (val result = importStateMachine.advanceToNextBook()) {
                is NotebookImportStateMachine.HighlightProcessingResult.Next -> {
                    runOnUiThread { loadNextBookHighlights() }
                }
                NotebookImportStateMachine.HighlightProcessingResult.Completed -> {
                    try {
                        highlightsFileStore.flush()
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to flush highlights to file", e)
                    }
                    Log.i(TAG, "Highlight extraction complete.")
                    terminateProcess(ImportState.FINISHED)
                }
                is NotebookImportStateMachine.HighlightProcessingResult.InvalidState -> {
                    Log.e(TAG, "advanceToNextBook called in a wrong state ${result.state}. Aborting.")
                    terminateProcess(ImportState.ERROR)
                }
            }
        }
    }
}

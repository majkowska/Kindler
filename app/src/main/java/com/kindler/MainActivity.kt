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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets

data class BookEntry(val asin: String, val title: String, val author: String, val lastAccessedDate: String)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BASE_URL = "https://read.amazon.com/"
        private const val NOTEBOOK_URL = "${BASE_URL}notebook"
        private const val HIGHLIGHT_URL_PREFIX = "${BASE_URL}notebook?asin="
        private const val HIGHLIGHT_URL_SUFFIX = "&contentLimitState="
        private const val SIGN_IN_URL_PREFIX = "https://www.amazon.com/ap/signin"
    }

    private enum class State { INITIAL, LOADING_BOOK_LIST, LOADING_HIGHLIGHTS, FINISHED, ERROR }

    private var currentState = State.INITIAL
    private var currentBookIndex = 0
    private var booksList: MutableList<BookEntry> = mutableListOf()
    private lateinit var myWebView: WebView
    private lateinit var overlayLayout: LinearLayout
    private lateinit var startImportButton: Button
    private lateinit var promptTextView: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        myWebView = findViewById(R.id.webView)
        overlayLayout = findViewById(R.id.overlayLayout)
        startImportButton = findViewById(R.id.startImportButton)
        promptTextView = findViewById(R.id.promptTextView)

        myWebView.settings.javaScriptEnabled = true
        myWebView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        myWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "New page finished loading: $url")
                Log.d(TAG, "Current state: $currentState")
                when (currentState) {
                    State.INITIAL -> checkLoginAndUrlStatus()
                    State.LOADING_BOOK_LIST -> verifyUrlAndProceed(NOTEBOOK_URL, url, "extract_book_list.js")
                    State.LOADING_HIGHLIGHTS -> verifyUrlAndProceed(HIGHLIGHT_URL_PREFIX, url, "extract_highlights.js")
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
    
    private fun terminateProcess(finalState: State) {
        if (finalState != State.FINISHED && finalState != State.ERROR) {
            Log.w(TAG, "terminateProcess called with non-terminal state: $finalState")
            return
        }
        currentState = finalState
        runOnUiThread {
            overlayLayout.visibility = View.VISIBLE
            checkLoginAndUrlStatus()
        }
    }

    private fun startImportProcess(){
        currentState = State.LOADING_BOOK_LIST
        currentBookIndex = 0
        booksList.clear()
        Log.d(TAG, "Starting import process. Loading book list page: $NOTEBOOK_URL")
        myWebView.loadUrl(NOTEBOOK_URL)
    }

    private fun verifyUrlAndProceed(expectedUrlPrefix: String, actualUrl: String?, scriptToLoad: String) {
        if (actualUrl?.startsWith(expectedUrlPrefix) == true) {
            loadAndExecuteJavascript(myWebView, scriptToLoad)
        } else {
            Log.e(TAG, "URL Mismatch! State: $currentState, Expected prefix: '$expectedUrlPrefix', Actual: '$actualUrl'.")
            terminateProcess(State.ERROR)
        }
    }

    private fun loadNextBookHighlights() {
        if (currentState != State.LOADING_HIGHLIGHTS) {
            Log.e(TAG, "loadNextBookHighlights called in a wrong state $currentState. Aborting.")
            return
        }
        if (currentBookIndex >=  booksList.size) {
             Log.e(TAG, "loadNextBookHighlights called with invalid index $currentBookIndex. Book list size: ${booksList.size}. Aborting.")
            return
        }
        val book = booksList[currentBookIndex]
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
            terminateProcess(State.ERROR)
            return
        }
        Log.d(TAG, "Executing $scriptName")
        webView?.evaluateJavascript(javascript, null)
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
            if (currentState != State.LOADING_BOOK_LIST) return
            Log.i(TAG, "Received book list data. Processing...")
            try {
                val rawBooksArray = JSONArray(bookDataJson)
                booksList.clear()
                for (i in 0 until rawBooksArray.length()) {
                    val bookObject: JSONObject = rawBooksArray.getJSONObject(i)
                    val asin = bookObject.getString("asin")
                    val title = bookObject.getString("title")
                    val author = bookObject.getString("author")
                    val lastAccessedDate = bookObject.getString("lastAccessedDate")
                    booksList.add(BookEntry(asin, title, author, lastAccessedDate))
                }
                Log.i(TAG, "Total books found on main page: ${booksList.size}")
                if (booksList.isEmpty()) {
                    Log.i(TAG, "No books found in the list. Finishing process.")
                    terminateProcess(State.FINISHED)
                    return
                }

                // move on to loading the first book's highlights
                currentState = State.LOADING_HIGHLIGHTS
                runOnUiThread { loadNextBookHighlights() }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing book list data: ", e)
                terminateProcess(State.ERROR)
            }
        }

        @JavascriptInterface
        fun processBookHighlights(highlightsJson: String, asin: String) {
            if (currentState != State.LOADING_HIGHLIGHTS) return
            val currentBook = booksList.getOrNull(currentBookIndex)
            val bookTitle = currentBook?.title ?: "Unknown (ASIN: $asin)"
            try {
                val highlightsArray = JSONArray(highlightsJson)
                if (highlightsArray.length() == 0) {
                     Log.i(TAG, "No highlights found for book '$bookTitle' (ASIN: $asin).")
                } else {
                    for (i in 0 until highlightsArray.length()) {
                        val highlightObject: JSONObject = highlightsArray.getJSONObject(i)
                        val highlightText = highlightObject.getString("highlight")
                        val noteText = highlightObject.getString("note")
                        Log.i(TAG, "ASIN: $asin - Highlight: \"$highlightText\" --- Note: \"$noteText\"")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing highlights for ASIN $asin: ", e)
                // we continue to process highlights even though there's an error
            }

            // load the next book highlights
            currentBookIndex++
            if (currentBookIndex < booksList.size) {
                runOnUiThread { loadNextBookHighlights() }
            } else {
                Log.i(TAG, "Highlight extraction complete.")
                terminateProcess(State.FINISHED)
            }
        }
    }
}

package com.kindler

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class ExportFragment : Fragment() {

    companion object {
        private const val TAG = "ExportFragment"
    }

    private lateinit var exportToCsvButton: Button
    private lateinit var exportToKeepButton: Button
    private lateinit var highlightsFileStore: HighlightsFileStore
    private lateinit var highlightsCsvExporter: HighlightsCsvExporter

    private val createCsvDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            handleDocumentResult(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_export, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        exportToCsvButton = view.findViewById(R.id.exportToCsvButton)

        highlightsFileStore = HighlightsFileStore(
            File(requireContext().filesDir, HIGHLIGHTS_FILE_NAME)
        )
        highlightsCsvExporter = HighlightsCsvExporter(highlightsFileStore)

        exportToCsvButton.setOnClickListener {
            startCsvExportFlow()
        }

        exportToKeepButton = view.findViewById(R.id.exportToKeepButton)
        exportToKeepButton.setOnClickListener {
            startKeepExportFlow()
        }
    }

    private fun startKeepExportFlow() {
        val hasHighlights = try {
            highlightsCsvExporter.hasAnyHighlights()
        } catch (ioException: IOException) {
            Log.e(TAG, "Failed to read highlights for Keep export", ioException)
            Toast.makeText(
                requireContext(),
                getString(R.string.export_failed),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!hasHighlights) {
            Toast.makeText(
                requireContext(),
                getString(R.string.export_no_data),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        exportToKeepButton.isEnabled = false
        val filesDir = requireContext().filesDir

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val keepExporter = HighlightsKeepExporter(
                        highlightsFileStore = highlightsFileStore,
                        filesDir = filesDir
                    )
                    keepExporter.exportToKeep(
                        email = BuildConfig.GOOGLE_ACCOUNT_EMAIL,
                        masterToken = BuildConfig.MASTER_TOKEN
                    )
                }
            }

            if (!isAdded) {
                return@launch
            }

            exportToKeepButton.isEnabled = true

            result.onSuccess {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.export_success),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                Log.e(TAG, "Failed to export highlights to Google Keep", error)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.export_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startCsvExportFlow() {
        val hasHighlights = try {
            highlightsCsvExporter.hasAnyHighlights()
        } catch (ioException: IOException) {
            Log.e(TAG, "Failed to read highlights for export", ioException)
            Toast.makeText(
                requireContext(),
                getString(R.string.export_failed),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!hasHighlights) {
            Toast.makeText(
                requireContext(),
                getString(R.string.export_no_data),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        createCsvDocumentLauncher.launch(highlightsCsvExporter.buildDefaultFileName())
    }

    private fun handleDocumentResult(uri: Uri?) {
        if (uri == null) {
            return
        }

        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                    highlightsCsvExporter.exportToWriter(writer)
                }
            } ?: throw IOException("Unable to open output stream for URI: $uri")

            Toast.makeText(
                requireContext(),
                getString(R.string.export_success),
                Toast.LENGTH_SHORT
            ).show()
        } catch (ioException: IOException) {
            Log.e(TAG, "Failed to export highlights to CSV", ioException)
            Toast.makeText(
                requireContext(),
                getString(R.string.export_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

}

package com.example.mgrskor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mgrskor.databinding.ActivitySavedPointsBinding
import com.example.mgrskor.export.GpxExporter
import com.example.mgrskor.ui.MainViewModel
import com.example.mgrskor.ui.SavedPointsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SavedPointsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySavedPointsBinding
    private val viewModel: MainViewModel by viewModels()

    private val createGpxDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri: Uri? ->
        if (uri != null) writeGpxToUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySavedPointsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Плавні переходи між Activity у стилі Material (slide+fade)
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export_gpx -> { promptExport(); true }
                else -> false
            }
        }

        val adapter = SavedPointsAdapter(onDelete = viewModel::deletePoint)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.savedPoints.collect { list ->
                    adapter.submitList(list)
                    binding.tvEmpty.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun promptExport() {
        if (viewModel.savedPoints.value.isEmpty()) {
            Toast.makeText(this, R.string.saved_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        createGpxDoc.launch("waypoints-$ts.gpx")
    }

    /** Зворотня анімація при закритті: список «їде» вправо, головна екран «виплигує». */
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
    }

    private fun writeGpxToUri(uri: Uri) {
        val points = viewModel.savedPoints.value
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val gpx = GpxExporter.buildWaypointsGpx(points)
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(gpx.toByteArray()) }
                        ?: error("openOutputStream returned null")
                }.isSuccess
            }
            Toast.makeText(
                this@SavedPointsActivity,
                if (ok) R.string.export_done else R.string.export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

}

package com.example.x265encoder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import android.media.MediaScannerConnection
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // Permission related variables
    private val mediaPermissions = arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    private lateinit var pickVideoButton: Button
    private var selectedVideosUris: List<Uri> = mutableListOf()

    // Encoding job tracking
    private lateinit var encodingJobsRecyclerView: RecyclerView
    private lateinit var encodingJobsAdapter: EncodingJobsAdapter
    private val encodingJobs = mutableListOf<EncodingJob>()
    private val executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())

    // Handles video selection result
    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Handle selected videos when user picks from gallery
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Process multiple or single video selection
            val clipData = result.data?.clipData
            selectedVideosUris = if (clipData != null) {
                List(clipData.itemCount) { clipData.getItemAt(it).uri }
            } else {
                listOf(result.data?.data!!)
            }
            encodeVideos()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        // Setup list for showing encoding progress
        encodingJobsRecyclerView = findViewById(R.id.encodingJobsRecyclerView)
        encodingJobsAdapter = EncodingJobsAdapter(encodingJobs)
        encodingJobsRecyclerView.layoutManager = LinearLayoutManager(this)
        encodingJobsRecyclerView.adapter = encodingJobsAdapter

        // Setup button for selecting videos
        pickVideoButton = findViewById(R.id.pickVideoButton)
        pickVideoButton.setOnClickListener {
            if (checkPermissions()) {
                openGallery()
            } else {
                requestMediaPermissions()
            }
        }
    }

    // Open device gallery to select videos
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        pickVideoLauncher.launch(intent)
    }

    // Check if app has needed permissions
    private fun checkPermissions(): Boolean {
        return mediaPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Ask user for required permissions
    private fun requestMediaPermissions() {
        requestPermissions(mediaPermissions, 1001)
    }

    // Handle permission request result
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openGallery()
        } else {
            showPermissionDeniedMessage()
        }
    }

    // Show message when permissions are denied
    private fun showPermissionDeniedMessage() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Media access is required to select videos. Please enable permissions in settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                // Open app settings for permission changes
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // Convert video URI to file path
    private fun getRealPathFromURI(uri: Uri): String? {
        val projection = arrayOf(android.provider.MediaStore.Video.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }

    // Get video duration in milliseconds
    private fun getVideoDuration(path: String): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.e("FFmpeg", "Failed to get video duration: ${e.message}")
            return 0L
        } finally {
            retriever.release()
        }
    }

    // Create thumbnail from video's first frame
    private fun getThumbnail(videoPath: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            return retriever.getFrameAtTime(0)
        } catch (e: Exception) {
            Log.e("FFmpeg", "Failed to get thumbnail: ${e.message}")
            return null
        } finally {
            retriever.release()
        }
    }

    // Start encoding process for selected videos
    private fun encodeVideos() {
        selectedVideosUris.forEach { videoUri ->
            val videoPath = getRealPathFromURI(videoUri) ?: run {
                showToast("Failed to get path for $videoUri")
                return@forEach
            }

            // Prepare input and output files
            val inputFile = File(videoPath)
            val outputFile = File(inputFile.parentFile!!, "${inputFile.nameWithoutExtension}_x265.mp4")
            outputFile.delete()

            // Create new encoding job
            val job = EncodingJob(
                id = UUID.randomUUID().toString(),
                inputPath = videoPath,
                outputPath = outputFile.absolutePath,
                fileName = inputFile.name,
                durationInMs = getVideoDuration(videoPath),
                thumbnail = getThumbnail(videoPath),
                status = EncodingStatus.PENDING
            )

            // Add job to list and start encoding
            encodingJobs.add(job)
            runOnUiThread { encodingJobsAdapter.notifyItemInserted(encodingJobs.size - 1) }
            executorService.execute { encodeVideo(job) }
        }
    }

    // Convert video to x265 format using FFmpeg
    private fun encodeVideo(job: EncodingJob) {
        // FFmpeg command setup
        val cmd = arrayOf(
            "-i", job.inputPath,
            "-c:v", "libx265",
            "-preset", "faster",
            "-crf", "23",
            "-x265-params", "fast_decode=1",
            "-threads", "${Runtime.getRuntime().availableProcessors() / 2}",
            job.outputPath
        )

        // Update job status to encoding
        runOnUiThread {
            job.status = EncodingStatus.ENCODING
            encodingJobsAdapter.notifyItemChanged(encodingJobs.indexOf(job), EncodingJobsAdapter.PAYLOAD_STATUS)
        }

        // Track encoding progress
        val statsCallback = StatisticsCallback { stats ->
            if (job.durationInMs > 0) {
                // Calculate progress percentage
                val totalMs = (job.accumulatedTimeMs + stats.time).coerceAtMost(job.durationInMs.toDouble())
                val progress = ((totalMs / job.durationInMs) * 100).toInt().coerceIn(0, 100)

                // Update progress display
                runOnUiThread {
                    job.progress = progress
                    encodingJobsAdapter.notifyItemChanged(encodingJobs.indexOf(job), EncodingJobsAdapter.PAYLOAD_PROGRESS)
                }
            }
        }

        // Execute FFmpeg command
        FFmpegKit.executeAsync(cmd.joinToString(" "), { session ->
            job.sessionId = session.sessionId
            when {
                ReturnCode.isSuccess(session.returnCode) -> {
                    // Handle successful encoding
                    runOnUiThread {
                        job.progress = 100
                        job.status = EncodingStatus.COMPLETED
                        encodingJobsAdapter.notifyItemChanged(encodingJobs.indexOf(job))
                    }
                    // Make new file visible in gallery
                    MediaScannerConnection.scanFile(
                        this, arrayOf(job.outputPath), arrayOf("video/mp4"), null
                    )
                    showToast("Encoding completed: ${job.fileName}")
                }
                else -> {
                    // Handle encoding failure
                    runOnUiThread {
                        job.status = EncodingStatus.FAILED
                        encodingJobsAdapter.notifyItemChanged(encodingJobs.indexOf(job))
                    }
                    showToast("Encoding failed for ${job.fileName}")
                }
            }
        }, null, statsCallback)
    }

    // Show short message at bottom of screen
    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        executorService.shutdown()
        super.onDestroy()
    }
}

// Possible states for encoding process
enum class EncodingStatus {
    PENDING, ENCODING, COMPLETED, FAILED
}

// Container for video conversion information
data class EncodingJob(
    val id: String,                  // Unique job identifier
    val inputPath: String,           // Source file location
    val outputPath: String,          // Destination file location
    val fileName: String,            // Original file name
    var progress: Int = 0,           // Conversion completion percentage
    val durationInMs: Long = 0,      // Video length in milliseconds
    val thumbnail: Bitmap?,           // Preview image of video
    var status: EncodingStatus,      // Current job state
    var sessionId: Long = -1,        // FFmpeg session reference
    var accumulatedTimeMs: Long = 0L,// Total encoding time
    var lastReportedTimeMs: Long = 0L// Last progress update time
)

// Manages display of encoding jobs in list
class EncodingJobsAdapter(private val jobs: List<EncodingJob>) :
    RecyclerView.Adapter<EncodingJobsAdapter.EncodingJobViewHolder>() {

    companion object {
        const val PAYLOAD_PROGRESS = "progress_update"  // Identifier for progress changes
        const val PAYLOAD_STATUS = "status_update"      // Identifier for status changes
    }

    // Holds view elements for each list item
    class EncodingJobViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileNameText: TextView = view.findViewById(R.id.fileNameText)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val progressText: TextView = view.findViewById(R.id.progressText)
        val thumbnailView: ImageView = view.findViewById(R.id.thumbnailView)
        val statusText: TextView = view.findViewById(R.id.statusText)
    }

    // Create new list items
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EncodingJobViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_encoding_job, parent, false)
        return EncodingJobViewHolder(view)
    }

    // Update list item contents
    override fun onBindViewHolder(holder: EncodingJobViewHolder, position: Int) {
        val job = jobs[position]
        holder.fileNameText.text = job.fileName
        holder.progressBar.progress = job.progress
        holder.progressText.text = "${job.progress}%"
        holder.thumbnailView.setImageBitmap(job.thumbnail)
        holder.statusText.text = job.status.name
        holder.statusText.setTextColor(when (job.status) {
            EncodingStatus.PENDING -> holder.itemView.context.getColor(android.R.color.darker_gray)
            EncodingStatus.ENCODING -> holder.itemView.context.getColor(android.R.color.holo_blue_dark)
            EncodingStatus.COMPLETED -> holder.itemView.context.getColor(android.R.color.holo_green_dark)
            EncodingStatus.FAILED -> holder.itemView.context.getColor(android.R.color.holo_red_dark)
        })
    }

    // Update specific parts of list item
    override fun onBindViewHolder(holder: EncodingJobViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) return super.onBindViewHolder(holder, position, payloads)

        val job = jobs[position]
        payloads.forEach { payload ->
            when (payload) {
                PAYLOAD_PROGRESS -> {
                    holder.progressBar.progress = job.progress
                    holder.progressText.text = "${job.progress}%"
                }
                PAYLOAD_STATUS -> {
                    holder.statusText.text = job.status.name
                    holder.statusText.setTextColor(when (job.status) {
                        EncodingStatus.PENDING -> holder.itemView.context.getColor(android.R.color.darker_gray)
                        EncodingStatus.ENCODING -> holder.itemView.context.getColor(android.R.color.holo_blue_dark)
                        EncodingStatus.COMPLETED -> holder.itemView.context.getColor(android.R.color.holo_green_dark)
                        EncodingStatus.FAILED -> holder.itemView.context.getColor(android.R.color.holo_red_dark)
                    })
                }
            }
        }
    }

    override fun getItemCount() = jobs.size
}
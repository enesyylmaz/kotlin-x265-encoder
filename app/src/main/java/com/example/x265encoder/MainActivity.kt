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

    private val mediaPermissions = arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    private lateinit var pickVideoButton: Button
    private var selectedVideosUris: List<Uri> = mutableListOf()
    private lateinit var encodingJobsRecyclerView: RecyclerView
    private lateinit var encodingJobsAdapter: EncodingJobsAdapter
    private val encodingJobs = mutableListOf<EncodingJob>()
    private val executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val clipData = result.data?.clipData
            if (clipData != null) {
                val uris = mutableListOf<Uri>()
                for (i in 0 until clipData.itemCount) {
                    val videoUri = clipData.getItemAt(i).uri
                    uris.add(videoUri)
                }
                selectedVideosUris = uris
            } else {
                result.data?.data?.let { uri ->
                    selectedVideosUris = listOf(uri)
                }
            }
            encodeVideos()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        encodingJobsRecyclerView = findViewById(R.id.encodingJobsRecyclerView)
        encodingJobsAdapter = EncodingJobsAdapter(encodingJobs)
        encodingJobsRecyclerView.layoutManager = LinearLayoutManager(this)
        encodingJobsRecyclerView.adapter = encodingJobsAdapter

        pickVideoButton = findViewById(R.id.pickVideoButton)
        pickVideoButton.setOnClickListener {
            if (checkPermissions()) {
                openGallery()
            } else {
                requestMediaPermissions()
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        pickVideoLauncher.launch(intent)
    }

    private fun checkPermissions(): Boolean {
        return mediaPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermissions() {
        requestPermissions(mediaPermissions, 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openGallery()
        } else {
            showPermissionDeniedMessage()
        }
    }

    private fun showPermissionDeniedMessage() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Media access is required to select videos. Please enable permissions in settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

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

    private fun getVideoDuration(path: String): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            return time?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.e("FFmpeg", "Failed to get video duration: ${e.message}")
            return 0L
        } finally {
            retriever.release()
        }
    }

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

    private fun encodeVideos() {
        selectedVideosUris.forEach { videoUri ->
            val videoPath = getRealPathFromURI(videoUri)
            if (videoPath == null) {
                showToast("Failed to get path for $videoUri")
                return@forEach
            }

            val inputFile = File(videoPath)
            val inputDirectory = inputFile.parentFile
            val outputFile = File(inputDirectory, "${inputFile.nameWithoutExtension}_x265.mp4")

            if (outputFile.exists()) {
                outputFile.delete()
            }

            val durationInMs = getVideoDuration(videoPath)
            val thumbnail = getThumbnail(videoPath)

            val job = EncodingJob(
                id = UUID.randomUUID().toString(),
                inputPath = videoPath,
                outputPath = outputFile.absolutePath,
                fileName = inputFile.name,
                progress = 0,
                durationInMs = durationInMs,
                thumbnail = thumbnail,
                status = EncodingStatus.PENDING
            )

            encodingJobs.add(job)
            runOnUiThread {
                encodingJobsAdapter.notifyItemInserted(encodingJobs.size - 1)
            }

            executorService.execute {
                encodeVideo(job)
            }
        }
    }

    private fun encodeVideo(job: EncodingJob) {
        val cmd = arrayOf(
            "-i", job.inputPath,
            "-c:v", "libx265",
            "-preset", "fast",
            "-crf", "23",
            "-x265-params", "fast_decode=1",
            "-threads", "${Runtime.getRuntime().availableProcessors() / 2}",
            job.outputPath
        )

        Log.d("FFmpeg", "Executing: ${cmd.joinToString(" ")}")

        runOnUiThread {
            job.status = EncodingStatus.ENCODING
            encodingJobsAdapter.notifyItemChanged(encodingJobs.indexOf(job),
                EncodingJobsAdapter.PAYLOAD_STATUS)
        }

        val statsCallback = StatisticsCallback { statistics ->
            if (job.durationInMs > 0) {
                val currentTime = statistics.time
                if (currentTime < job.lastReportedTimeMs) {
                    job.accumulatedTimeMs += job.lastReportedTimeMs
                }
                job.lastReportedTimeMs = currentTime.toLong()
                val totalTimeMs = (job.accumulatedTimeMs + currentTime).coerceAtMost(job.durationInMs.toDouble())

                val progress = ((totalTimeMs / job.durationInMs.toDouble()) * 100).toInt()
                    .coerceIn(0, 100)

                runOnUiThread {
                    job.progress = progress
                    encodingJobsAdapter.notifyItemChanged(
                        encodingJobs.indexOf(job),
                        EncodingJobsAdapter.PAYLOAD_PROGRESS
                    )
                }
            }
        }

        FFmpegKit.executeAsync(cmd.joinToString(" "), { session ->
            val returnCode = session.returnCode
            job.sessionId = session.sessionId

            if (ReturnCode.isSuccess(returnCode)) {
                runOnUiThread {
                    job.progress = 100
                    job.status = EncodingStatus.COMPLETED
                    encodingJobsAdapter.notifyItemChanged(encodingJobs.indexOf(job),
                        EncodingJobsAdapter.PAYLOAD_STATUS)
                    encodingJobsAdapter.notifyItemChanged(encodingJobs.indexOf(job),
                        EncodingJobsAdapter.PAYLOAD_PROGRESS)
                }

                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(job.outputPath),
                    arrayOf("video/mp4")
                ) { path, uri ->
                    Log.d("MediaScanner", "Scanned file: $path, Uri: $uri")
                }

                showToast("Encoding completed: ${job.fileName}")
            } else {
                runOnUiThread {
                    job.status = EncodingStatus.FAILED
                    encodingJobsAdapter.notifyItemChanged(encodingJobs.indexOf(job),
                        EncodingJobsAdapter.PAYLOAD_STATUS)
                }
                showToast("Encoding failed for ${job.fileName}")
            }
        }, null, statsCallback)
    }

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

enum class EncodingStatus {
    PENDING, ENCODING, COMPLETED, FAILED
}

data class EncodingJob(
    val id: String,
    val inputPath: String,
    val outputPath: String,
    val fileName: String,
    var progress: Int = 0,
    val durationInMs: Long = 0,
    val thumbnail: Bitmap? = null,
    var status: EncodingStatus = EncodingStatus.PENDING,
    var sessionId: Long = -1,
    var accumulatedTimeMs: Long = 0L,
    var lastReportedTimeMs: Long = 0L
)

class EncodingJobsAdapter(private val jobs: List<EncodingJob>) :
    RecyclerView.Adapter<EncodingJobsAdapter.EncodingJobViewHolder>() {

    companion object {
        const val PAYLOAD_PROGRESS = "PAYLOAD_PROGRESS"
        const val PAYLOAD_STATUS = "PAYLOAD_STATUS"
    }

    class EncodingJobViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileNameText: TextView = view.findViewById(R.id.fileNameText)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val progressText: TextView = view.findViewById(R.id.progressText)
        val thumbnailView: ImageView = view.findViewById(R.id.thumbnailView)
        val statusText: TextView = view.findViewById(R.id.statusText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EncodingJobViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_encoding_job, parent, false)
        return EncodingJobViewHolder(view)
    }

    override fun onBindViewHolder(holder: EncodingJobViewHolder, position: Int) {
        val job = jobs[position]
        bindJobData(holder, job)
    }

    override fun onBindViewHolder(holder: EncodingJobViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        val job = jobs[position]
        for (payload in payloads) {
            when (payload) {
                PAYLOAD_PROGRESS -> {
                    holder.progressBar.progress = job.progress
                    holder.progressText.text = "${job.progress}%"
                }
                PAYLOAD_STATUS -> {
                    holder.statusText.text = job.status.name
                    val color = when (job.status) {
                        EncodingStatus.PENDING -> android.R.color.darker_gray
                        EncodingStatus.ENCODING -> android.R.color.holo_blue_dark
                        EncodingStatus.COMPLETED -> android.R.color.holo_green_dark
                        EncodingStatus.FAILED -> android.R.color.holo_red_dark
                    }
                    holder.statusText.setTextColor(holder.itemView.context.getColor(color))
                }
            }
        }
    }

    private fun bindJobData(holder: EncodingJobViewHolder, job: EncodingJob) {
        holder.fileNameText.text = job.fileName
        holder.progressBar.progress = job.progress
        holder.progressText.text = "${job.progress}%"
        holder.thumbnailView.setImageBitmap(job.thumbnail)
        holder.statusText.text = job.status.name

        val color = when (job.status) {
            EncodingStatus.PENDING -> android.R.color.darker_gray
            EncodingStatus.ENCODING -> android.R.color.holo_blue_dark
            EncodingStatus.COMPLETED -> android.R.color.holo_green_dark
            EncodingStatus.FAILED -> android.R.color.holo_red_dark
        }
        holder.statusText.setTextColor(holder.itemView.context.getColor(color))
    }

    override fun getItemCount() = jobs.size
}
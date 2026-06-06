package com.example.camera_timer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                GameTimerApp()
            }
        }
    }
}

@Composable
fun GameTimerApp() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("GamePrefs", Context.MODE_PRIVATE) }

    val savedEndUptime = sharedPrefs.getLong("END_UPTIME_MILLIS", 0L)
    val totalDurationSeconds = sharedPrefs.getInt("TOTAL_DURATION_SECONDS", 0)
    val currentUptime = SystemClock.elapsedRealtime()

    // 1. Run the standard elapsed uptime subtraction
    var calculatedTimeLeft = if (savedEndUptime > currentUptime) {
        ((savedEndUptime - currentUptime) / 1000).toInt()
    } else {
        0
    }

    // 2. ANTI-CHEAT / REBOOT GUARD
    // If the calculated remaining time is bigger than the original max duration,
    // the clock must have reset due to a phone reboot!
    if (calculatedTimeLeft > totalDurationSeconds && totalDurationSeconds > 0) {
        // Reset the target end time to be exactly "total duration" from right now
        calculatedTimeLeft = totalDurationSeconds

        val newEndUptimeMillis = currentUptime + (totalDurationSeconds * 1000L)
        sharedPrefs.edit().putLong("END_UPTIME_MILLIS", newEndUptimeMillis).apply()
    }

    // --- State Variables ---
    var inputHours by remember { mutableStateOf(0) }
    var inputMinutes by remember { mutableStateOf(5) }
    var inputSeconds by remember { mutableStateOf(0) }

    var timeLeft by remember { mutableStateOf(calculatedTimeLeft) } // Uses guarded time
    var isTimerRunning by remember {
        mutableStateOf(
            sharedPrefs.getBoolean(
                "IS_RUNNING",
                false
            ) && calculatedTimeLeft > 0
        )
    }
    var isVideoHidden by remember { mutableStateOf(sharedPrefs.getBoolean("IS_HIDDEN", true)) }
    var videoUriString by remember { mutableStateOf(sharedPrefs.getString("VIDEO_URI", "")) }
    var showResetDialog by remember { mutableStateOf(false) }

    // File setup to store the video safely
    val videoFile = remember { File(context.filesDir, "game_info.mp4") }
    val currentVideoUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", videoFile)
    }

    // --- Camera Launcher ---
    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakeVideo()
    ) { resultVideoUri ->
        // Note: Some Android versions return null but still write to the file via the input URI
        if (videoFile.exists() && videoFile.length() > 0) {
            val totalSecondsInput = (inputHours * 3600) + (inputMinutes * 60) + inputSeconds
            val totalSeconds = if (totalSecondsInput > 0) totalSecondsInput else 60

            val currentUptimeMillis = SystemClock.elapsedRealtime()
            val endUptimeMillis = currentUptimeMillis + (totalSeconds * 1000L)

            timeLeft = totalSeconds
            isTimerRunning = true
            isVideoHidden = true
            videoUriString = currentVideoUri.toString()

            sharedPrefs.edit().apply {
                putLong("END_UPTIME_MILLIS", endUptimeMillis)
                putInt("TOTAL_DURATION_SECONDS", totalSeconds) // <-- Save original duration
                putBoolean("IS_RUNNING", true)
                putBoolean("IS_HIDDEN", true)
                putString("VIDEO_URI", videoUriString)
                apply()
            }
        }
    }

    // --- Background Timer Engine ---
    if (isTimerRunning && timeLeft > 0) {
        LaunchedEffect(key1 = timeLeft) {
            delay(1000L)

            val savedEnd = sharedPrefs.getLong("END_UPTIME_MILLIS", 0L)
            val now = SystemClock.elapsedRealtime()
            val remaining = ((savedEnd - now) / 1000).toInt()

            if (remaining > 0) {
                timeLeft = remaining
            } else {
                timeLeft = 0
                isTimerRunning = false
                sharedPrefs.edit().putBoolean("IS_RUNNING", false).apply()
            }
        }
    }

    // --- UI Layout ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        // PHASE 1: Setup Screen (No video captured yet, and timer isn't running)
        if (videoUriString.isNullOrEmpty()) {
            Text("Set Lock Duration", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimeStepper(
                    label = "Hours",
                    value = inputHours,
                    maxValue = 23,
                    onValueChange = { inputHours = it },
                    modifier = Modifier.weight(1f)
                )
                Text(
                    ":",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 24.dp)
                )
                TimeStepper(
                    label = "Minutes",
                    value = inputMinutes,
                    maxValue = 59,
                    onValueChange = { inputMinutes = it },
                    modifier = Modifier.weight(1f)
                )
                Text(
                    ":",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 24.dp)
                )
                TimeStepper(
                    label = "Seconds",
                    value = inputSeconds,
                    maxValue = 59,
                    onValueChange = { inputSeconds = it },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    videoLauncher.launch(currentVideoUri)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Record The Video")
            }
        }
        else {
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                if (isVideoHidden) {
                    Box(
                        modifier = Modifier
                            .size(300.dp)
                            .background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🔒 Info Hidden Until Timer Ends", color = Color.White)
                    }
                } else {
                    // We add .clipToBounds() so the video can NEVER leak outside this container box
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        // Core Video Player View
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = true
                                    player = ExoPlayer.Builder(ctx).build().apply {
                                        setMediaItem(MediaItem.fromUri(Uri.parse(videoUriString)))
                                        prepare()
                                        repeatMode = Player.REPEAT_MODE_ONE
                                        playWhenReady = true
                                    }
                                }
                            },
                            update = { playerView ->
                                val params = playerView.layoutParams


                                val density = playerView.context.resources.displayMetrics.density
                                val sizeInPx = (300 * density).toInt()
                                params.width = sizeInPx
                                params.height = sizeInPx

                                playerView.resizeMode =
                                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

                                playerView.layoutParams = params
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(videoUriString), "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black.copy(alpha = 0.4f),
                                contentColor = Color.White
                            )
                        ) {
                            Text("⛶ Fullscreen")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    isVideoHidden = false
                    sharedPrefs.edit().putBoolean("IS_HIDDEN", false).apply()
                },
                enabled = (timeLeft <= 0), // Locked until countdown hits 0
                modifier = Modifier.fillMaxWidth()
            ) {
                if (timeLeft > 0) {
                    val displayMins = timeLeft / 60
                    val displaySecs = timeLeft % 60
                    Text(String.format("Locked: %02d:%02d", displayMins, displaySecs))
                } else {
                    Text("Reveal Video Evidence")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = { showResetDialog = true }) {
                Text("Reset App / Clear Data", color = MaterialTheme.colorScheme.error)
            }

            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = {
                        // Closes the dialog if they tap outside the box
                        showResetDialog = false
                    },
                    title = {
                        Text("Reset Game State?")
                    },
                    text = {
                        Text("Are you sure you want to clear the current video and timer? This action cannot be undone.")
                    },
                    confirmButton = {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = {
                                // 1. Delete the actual video file
                                if (videoFile.exists()) videoFile.delete()

                                // 2. Reset all local states
                                timeLeft = 0
                                isTimerRunning = false
                                isVideoHidden = true
                                videoUriString = ""

                                // 3. Clear persistent phone memory
                                sharedPrefs.edit().clear().apply()

                                // 4. Close the dialog
                                showResetDialog = false
                            }
                        ) {
                            Text("Yes, Reset")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun TimeStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxValue: Int = 59
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(4.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Increment Button
                TextButton(
                    onClick = { if (value < maxValue) onValueChange(value + 1) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }

                // Value Display
                Text(
                    text = String.format("%02d", value),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                // Decrement Button
                TextButton(
                    onClick = { if (value > 0) onValueChange(value - 1) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Text("-", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
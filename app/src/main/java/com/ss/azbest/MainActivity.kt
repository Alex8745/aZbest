package com.ss.azbest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ss.azbest.data.MessageRepository
import com.ss.azbest.transport.MeshtasticTransport
import com.ss.azbest.ui.theme.screens.ChatScreen
import com.ss.azbest.ui.theme.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    // ViewModel создаётся один раз и переживает повороты экрана
    // благодаря viewModels() — стандартный механизм Android
    private val chatViewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val transport = MeshtasticTransport(applicationContext)
                val repository = MessageRepository(transport)
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(repository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MeshtasticApp(viewModel = chatViewModel)
                }
            }
        }
    }
}

@Composable
private fun MeshtasticApp(viewModel: ChatViewModel) {
    // rememberSaveable сохраняет значение при повороте (в отличие от remember)
    var showSplash by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (showSplash) {
            delay(1800)
            showSplash = false
        }
    }

    if (showSplash) {
        SplashScreen()
        return
    }

    val context = LocalContext.current
    val requiredPermissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    var hasPermissions by rememberSaveable {
        mutableStateOf(context.hasAllPermissions(requiredPermissions))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermissions = context.hasAllPermissions(requiredPermissions)
    }

    if (hasPermissions) {
        // ViewModel передаётся снаружи — уже создан в Activity и переживает поворот
        ChatScreen(viewModel = viewModel)
    } else {
        PermissionRequiredScreen {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }
}

@Composable
private fun SplashScreen() {
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "splashAlpha"
    )

    val context = LocalContext.current
    val mainLogoResId = context.resources.getIdentifier(
        "dasa_labs_logo_main", "drawable", context.packageName
    )
    val bottomLogoResId = context.resources.getIdentifier(
        "dasa_labs_logo_bottom", "drawable", context.packageName
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (mainLogoResId != 0) {
            Image(
                painter = painterResource(id = mainLogoResId),
                contentDescription = "DaSa Labs Main",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 112.dp)
                    .splashSquareSize(240.dp)
                    .alpha(alpha)
            )
        } else {
            Text(
                text = "DaSa Labs",
                color = Color.White,
                fontSize = 34.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 150.dp)
                    .alpha(alpha)
            )
        }

        if (bottomLogoResId != 0) {
            Image(
                painter = painterResource(id = bottomLogoResId),
                contentDescription = "DaSa Labs Bottom",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .splashSquareSize(93.dp)
                    .alpha(alpha)
            )
        }
    }
}

private fun Modifier.splashSquareSize(size: Dp): Modifier =
    this.size(size).aspectRatio(1f)

@Composable
private fun PermissionRequiredScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Для работы мессенджера нужны Bluetooth-разрешения.",
            style = MaterialTheme.typography.bodyLarge
        )
        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Выдать разрешения")
        }
    }
}

private fun Context.hasAllPermissions(permissions: List<String>): Boolean =
    permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

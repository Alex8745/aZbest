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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ss.azbest.ui.theme.screens.MainScreen
import com.ss.azbest.ui.theme.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val transport = MeshtasticTransport(applicationContext)
                val repository = MessageRepository(transport, applicationContext)
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(viewModel = chatViewModel)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: ChatViewModel) {
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
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermissions = context.hasAllPermissions(requiredPermissions) }

    if (hasPermissions) {
        MainScreen(viewModel = viewModel)
    } else {
        PermissionScreen { permissionLauncher.launch(requiredPermissions.toTypedArray()) }
    }
}

@Composable
private fun SplashScreen() {
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "splash"
    )

    val context = LocalContext.current
    val mainLogoId = context.resources.getIdentifier("dasa_labs_logo_main", "drawable", context.packageName)
    val bottomLogoId = context.resources.getIdentifier("dasa_labs_logo_bottom", "drawable", context.packageName)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (mainLogoId != 0) {
            Image(
                painterResource(mainLogoId), "Logo",
                Modifier.align(Alignment.Center).padding(bottom = 112.dp).splashSize(240.dp).alpha(alpha)
            )
        }
        if (bottomLogoId != 0) {
            Image(
                painterResource(bottomLogoId), "DaSa Labs",
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).splashSize(93.dp).alpha(alpha)
            )
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        Arrangement.Center, Alignment.CenterHorizontally
    ) {
        Text("Для работы нужны Bluetooth-разрешения.", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) {
            Text("Выдать разрешения")
        }
    }
}

private fun Modifier.splashSize(size: Dp) = this.size(size).aspectRatio(1f)

private fun Context.hasAllPermissions(permissions: List<String>) =
    permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

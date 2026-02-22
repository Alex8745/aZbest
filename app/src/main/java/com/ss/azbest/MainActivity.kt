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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import com.ss.azbest.data.MessageRepository
import com.ss.azbest.transport.MeshtasticTransport
import com.ss.azbest.ui.theme.screens.ChatScreen
import com.ss.azbest.ui.theme.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
                    MaterialTheme {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            MeshtasticApp()
                        }
                    }
                }
            }
        }

        @Composable
                        private fun MeshtasticApp() {
                    var showSplash by remember { mutableStateOf(true) }

                    LaunchedEffect(Unit) {
                        delay(1800)
                        showSplash = false
                    }

                    if (showSplash) {
                        SplashScreen()
                        return
                    }

                    val context = LocalContext.current
                    val requiredPermissions = remember {
                        buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                add(Manifest.permission.BLUETOOTH_SCAN)
                                add(Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }
                    }

                    var hasPermissions by remember { mutableStateOf(context.hasAllPermissions(requiredPermissions)) }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        hasPermissions = context.hasAllPermissions(requiredPermissions)
                    }

                    if (hasPermissions) {
                        val viewModel = remember {
                            ChatViewModel(
                                repository = MessageRepository(
                                    transport = MeshtasticTransport(context.applicationContext)
                                )
                            )
                        }
                        ChatScreen(viewModel = viewModel)
                    } else {
                        PermissionRequiredScreen {
                            permissionLauncher.launch(requiredPermissions.toTypedArray())
                        }
                    }
                }

                @Composable
                private fun SplashScreen() {
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        visible = true
                    }

                    val alpha by animateFloatAsState(
                        targetValue = if (visible) 1f else 0f,
                        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                        label = "splashAlpha"
                    )

                    val context = LocalContext.current
                    val mainLogoResId = remember {
                        context.resources.getIdentifier("dasa_labs_logo_main", "drawable", context.packageName)
                    }
                    val bottomLogoResId = remember {
                        context.resources.getIdentifier("dasa_labs_logo_bottom", "drawable", context.packageName)
                    }

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


                        private fun Modifier.splashSquareSize(size: Dp): Modifier {
                    return this
                        .size(size)
                        .aspectRatio(1f)
                }

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


                    private fun Context.hasAllPermissions(permissions: List<String>): Boolean {
                        return permissions.all {
                            ContextCompat.checkSelfPermission(
                                this,
                                it
                            ) == PackageManager.PERMISSION_GRANTED
                        }
                    }
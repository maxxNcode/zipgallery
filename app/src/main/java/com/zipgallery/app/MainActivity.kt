package com.zipgallery.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zipgallery.app.model.AppScreen
import com.zipgallery.app.ui.screens.GalleryScreen
import com.zipgallery.app.ui.screens.MainScreen
import com.zipgallery.app.ui.screens.SettingsScreen
import com.zipgallery.app.ui.screens.ViewerScreen
import com.zipgallery.app.ui.theme.ZipGalleryTheme
import com.zipgallery.app.viewmodel.GalleryViewModel

class MainActivity : ComponentActivity() {

    private var pendingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingUri = intent?.data?.takeIf { intent?.action == Intent.ACTION_VIEW }

        setContent {
            val viewModel: GalleryViewModel = viewModel()
            ZipGalleryTheme(
                themeMode = viewModel.state.themeMode,
                dynamicColor = viewModel.state.useDynamicColor
            ) {

                val uriToOpen = pendingUri
                if (uriToOpen != null) {
                    LaunchedEffect(uriToOpen) {
                        viewModel.loadArchive(uriToOpen)
                        pendingUri = null
                    }
                }

                AppContent(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val uri = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        if (uri != null) {
            pendingUri = uri
        }
    }
}

@Composable
private fun AppContent(viewModel: GalleryViewModel) {
    val state = viewModel.state
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Paint the app background from the active M3 color scheme so the content
    // and text roles always come from the same palette — the window background
    // (which follows the system) can never mismatch the Compose theme again.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedContent(
            targetState = state.screen,
            transitionSpec = {
                if (initialState == AppScreen.Settings || targetState == AppScreen.Settings) {
                    // M3 fade-through: peer destinations (Settings) have no
                    // directional relationship with their neighbours.
                    fadeThrough()
                } else {
                    // M3 shared-axis X: direction-aware push/pop along the
                    // Main -> Gallery -> Viewer navigation line.
                    sharedAxisX(forward = targetState.depth > initialState.depth)
                }
            },
            label = "screen_transitions"
        ) { screen ->
            when (screen) {
                AppScreen.Main -> MainScreen(
                    isLoading = state.isLoading,
                    onArchiveSelected = { uri -> viewModel.loadArchive(uri) },
                    onOpenSettings = { viewModel.openSettings() }
                )

                AppScreen.Gallery -> GalleryScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.backToMain() },
                    onItemClick = { entry -> viewModel.openViewer(entry) },
                    onOpenSettings = { viewModel.openSettings() }
                )

                AppScreen.Viewer -> ViewerScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.backToGallery() }
                )

                AppScreen.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.backFromSettings() }
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (state.showPasswordDialog) {
            PasswordDialog(
                error = state.passwordError,
                onDismiss = { viewModel.dismissPasswordDialog() },
                onConfirm = { password -> viewModel.loadArchiveWithPassword(password) }
            )
        }
    }
}

/**
 * M3 shared-axis X transition for forward/backward navigation: the incoming
 * screen slides in from the direction of travel while fading, and the outgoing
 * screen slides away in the same direction. Emphasized 500ms duration with the
 * M3 standard easings.
 */
private fun sharedAxisX(forward: Boolean): ContentTransform {
    val enter: EnterTransition =
        slideInHorizontally(
            animationSpec = tween(SCREEN_TRANSITION_DURATION, easing = FastOutSlowInEasing)
        ) { width -> if (forward) width / 3 else -width / 3 } +
            fadeIn(
                animationSpec = tween(SCREEN_TRANSITION_DURATION, easing = LinearOutSlowInEasing)
            )
    val exit: ExitTransition =
        slideOutHorizontally(
            animationSpec = tween(SCREEN_TRANSITION_DURATION, easing = FastOutSlowInEasing)
        ) { width -> if (forward) -width / 3 else width / 3 } +
            fadeOut(
                animationSpec = tween(SCREEN_TRANSITION_DURATION, easing = FastOutLinearInEasing)
            )
    return enter togetherWith exit
}

/**
 * M3 fade-through transition for destinations without a directional
 * relationship: the outgoing screen fades out while the incoming one fades in.
 */
private fun fadeThrough(): ContentTransform =
    fadeIn(
        animationSpec = tween(SCREEN_TRANSITION_DURATION, easing = FastOutSlowInEasing)
    ) togetherWith
        fadeOut(
            animationSpec = tween(SCREEN_TRANSITION_DURATION, easing = FastOutLinearInEasing)
        )

/** Navigation depth for direction-aware transitions: Main -> Gallery -> Viewer. */
private val AppScreen.depth: Int
    get() = when (this) {
        AppScreen.Main -> 0
        AppScreen.Gallery -> 1
        AppScreen.Viewer -> 2
        AppScreen.Settings -> 1 // peer destination, always handled by fade-through
    }

private const val SCREEN_TRANSITION_DURATION = 500

@Composable
private fun PasswordDialog(
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Password Required", style = MaterialTheme.typography.headlineSmall)
        },
        text = {
            Box {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Archive password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        if (error != null) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank()
            ) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

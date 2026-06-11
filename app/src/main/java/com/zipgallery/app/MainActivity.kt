package com.zipgallery.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
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
            ZipGalleryTheme(themeMode = viewModel.state.themeMode) {

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

    Box(modifier = Modifier.fillMaxSize()) {
        when (state.screen) {
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
        shape = RoundedCornerShape(16.dp),
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

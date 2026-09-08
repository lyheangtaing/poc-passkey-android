package com.lyheang.pocpasskey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = androidx.compose.material3.lightColorScheme(
                    primary = Color(0xFF6A3DE8),
                    secondary = Color(0xFF006B5E),
                    surface = Color(0xFFFFF8F5)
                )
            ) {
                PasskeyApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasskeyApp(viewModel: PasskeyViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = checkNotNull(LocalActivity.current)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("POC with Passkey Android") })
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (state.signedInAccount != null) {
                SignedInScreen(
                    account = state.signedInAccount!!,
                    onSignOut = viewModel::signOut
                )
            } else {
                AuthScreen(
                    state = state,
                    onSignUp = { email, name -> viewModel.signUp(activity, email, name) },
                    onSignIn = { viewModel.signIn(activity) },
                    onClearMessage = viewModel::clearMessage,
                    onClearDatabase = viewModel::clearFakeBackend
                )
            }

            if (state.busy) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.25f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthScreen(
    state: PasskeyUiState,
    onSignUp: (String, String) -> Unit,
    onSignIn: () -> Unit,
    onClearMessage: () -> Unit,
    onClearDatabase: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var email by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Passwordless authentication",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Credential Manager owns the private key. The fake backend stores only the public key and verifies every response locally.",
            style = MaterialTheme.typography.bodyLarge
        )

        ConfigurationCard(state)

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sign up") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Sign in") })
        }

        if (tab == 0) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Display name") },
                singleLine = true
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true
            )
            Button(
                onClick = { onSignUp(email, displayName) },
                enabled = email.isNotBlank() && displayName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Create account and passkey")
            }
        } else {
            Text(
                "No email is needed. Credential Manager returns a discoverable passkey, and its credential ID tells the backend which account is signing in."
            )
            Button(
                onClick = onSignIn,
                enabled = state.accountCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign in with passkey")
            }
            if (state.accountCount == 0) {
                Text("Create an account first on this installation.", color = MaterialTheme.colorScheme.error)
            }
        }

        state.message?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearMessage) { Text("Close") }
                }
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Offline backend database", fontWeight = FontWeight.SemiBold)
                Text("${state.accountCount} account(s)", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onClearDatabase, enabled = state.accountCount > 0) {
                Text("Clear")
            }
        }
    }
}

@Composable
private fun ConfigurationCard(state: PasskeyUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Relying-party configuration", fontWeight = FontWeight.Bold)
            Text("RP ID: ${state.rpId}", style = MaterialTheme.typography.bodySmall)
            Text("Package: ${state.packageName}", style = MaterialTheme.typography.bodySmall)
            Text("Expected origin:", style = MaterialTheme.typography.bodySmall)
            Text(state.expectedOrigin, style = MaterialTheme.typography.labelSmall)
            if (!state.rpConfigured) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Replace the example RP ID and host assetlinks.json before testing a real passkey.",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SignedInScreen(account: AccountRecord, onSignOut: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("✓", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Signed in", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(account.displayName, style = MaterialTheme.typography.titleLarge)
        Text(account.email)
        Spacer(Modifier.height(8.dp))
        Text(
            "The offline backend verified the passkey signature.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
    }
}

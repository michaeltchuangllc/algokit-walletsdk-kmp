package com.michaeltchuang.walletsdk.service.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.service.demo.client.WalletServiceClient
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface AccountRegistrationType {
    @Serializable
    data object Algo25 : AccountRegistrationType
    @Serializable
    data object LedgerBle : AccountRegistrationType
    @Serializable
    data object NoAuth : AccountRegistrationType
    @Serializable
    data object HdKey : AccountRegistrationType
    @Serializable
    data object Falcon24 : AccountRegistrationType
}

@Serializable
data class AccountLite(
    val address: String,
    val customName: String,
    val registrationType: AccountRegistrationType,
    val balance: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WalletServiceDemoScreen()
                }
            }
        }
    }
}

@Composable
fun WalletServiceDemoScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true } }
    
    var serviceConnected by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf<List<AccountLite>>(emptyList()) }
    
    val walletClient = remember { WalletServiceClient(context) }
    
    fun connectToService() {
        loading = true
        errorMessage = null
        scope.launch {
            try {
                walletClient.bindAsync()
                serviceConnected = true
                errorMessage = null
            } catch (e: Exception) {
                serviceConnected = false
                errorMessage = "Failed to connect: ${e.message}\n\nIs wallet-sdk-service installed?"
            } finally {
                loading = false
            }
        }
    }
    
    fun fetchAccounts() {
        if (!serviceConnected) {
            errorMessage = "Not connected to service"
            return
        }
        
        loading = true
        errorMessage = null
        scope.launch {
            try {
                val accountsJson = walletClient.getAccountsWithBalances()
                accounts = json.decodeFromString<List<AccountLite>>(accountsJson)
                errorMessage = if (accounts.isEmpty()) "No accounts found" else null
            } catch (e: Exception) {
                errorMessage = "Failed to fetch accounts: ${e.message}"
            } finally {
                loading = false
            }
        }
    }
    
    // Auto-connect on start
    LaunchedEffect(Unit) {
        connectToService()
    }
    
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Title
            Text(
                text = "Wallet Service Demo",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (serviceConnected) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Service Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (serviceConnected) "✓ Connected" else "✗ Not Connected",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { connectToService() },
                    modifier = Modifier.weight(1f),
                    enabled = !loading
                ) {
                    Text("Connect")
                }
                
                Button(
                    onClick = { fetchAccounts() },
                    modifier = Modifier.weight(1f),
                    enabled = serviceConnected && !loading
                ) {
                    Text("Fetch Accounts")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Loading Indicator
            if (loading) {
                CircularProgressIndicator()
            }
            
            // Error Message
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMessage ?: "",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Accounts List
            if (accounts.isNotEmpty()) {
                Text(
                    text = "Accounts (${accounts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(accounts) { account ->
                        AccountCard(account)
                    }
                }
            }
        }
    }
}

@Composable
fun AccountCard(account: AccountLite) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = account.customName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = account.address.take(10) + "..." + account.address.takeLast(10),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Balance: ${account.balance ?: "0"} microAlgos",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Type: ${account.registrationType::class.simpleName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

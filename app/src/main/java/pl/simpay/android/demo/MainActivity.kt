package pl.simpay.android.demo

import android.os.Bundle
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import pl.simpay.android.checkout.mobile.CheckoutResult
import pl.simpay.android.demo.ui.theme.SimPayAndroidSDKTheme

/**
 * Demo shop with a single item.
 *
 * The whole integration is the [DemoCheckout.present] call below: open the sheet and
 * read the result. Nothing is configured on screen.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SimPayAndroidSDKTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    CartScreen(
                        activity = this,
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}

@Composable
private fun CartScreen(activity: FragmentActivity, modifier: Modifier = Modifier) {
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Twój koszyk", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("\uD83C\uDFA7", style = MaterialTheme.typography.titleLarge)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(DemoCart.PRODUCT_NAME, style = MaterialTheme.typography.titleMedium)
                    Text("1 szt.", style = MaterialTheme.typography.bodySmall)
                }
                Text(DemoCart.formattedAmount(), style = MaterialTheme.typography.titleMedium)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Do zapłaty",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                DemoCart.formattedAmount(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            onClick = {
                message = null
                DemoCheckout.present(activity) { outcome -> message = describe(outcome) }
            }
        ) {
            Text("Zapłać ${DemoCart.formattedAmount()}")
        }

        message?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Wording for the demo only.
 *
 * A real shop releases the order from the webhook, never from this callback: the sheet
 * reports what the payer saw, the backend decides what was actually paid.
 */
private fun describe(outcome: CheckoutResult): String = when (outcome) {
    is CheckoutResult.Succeeded -> "Płatność zakończona. Sklep potwierdzi zamówienie."
    is CheckoutResult.Pending -> "Płatność w toku. Czekamy na potwierdzenie."
    is CheckoutResult.Failed -> "Płatność nie powiodła się. Spróbuj ponownie."
    CheckoutResult.Cancelled -> "Płatność anulowana."
}


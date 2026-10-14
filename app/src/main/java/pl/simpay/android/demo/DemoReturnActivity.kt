package pl.simpay.android.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Handles the return deep link after a redirect payment.
 *
 * The gateway sends the payer to `simpay-demo://payment-return` (declared in the
 * manifest), which brings the app back to the foreground automatically instead of
 * leaving the user in a browser tab. Bringing [MainActivity] to the front with
 * CLEAR_TOP also removes the Custom Tab from the task.
 *
 * Returning here proves nothing about the payment: the sheet resumes and verifies the
 * transaction status against the API before reporting any result.
 */
class DemoReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}

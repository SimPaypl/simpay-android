package pl.simpay.android.checkout.mobile.internal.ui

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import pl.simpay.android.R
import pl.simpay.android.checkout.mobile.BlikCode
import pl.simpay.android.checkout.mobile.CheckoutResult
import pl.simpay.android.checkout.mobile.MobileCheckoutError
import pl.simpay.android.checkout.mobile.PaymentChannel
import pl.simpay.android.checkout.mobile.internal.flow.BlikEntryError
import pl.simpay.android.checkout.mobile.internal.flow.BlikLevel0
import pl.simpay.android.checkout.mobile.internal.flow.MobileCheckoutRegistry
import pl.simpay.android.checkout.mobile.internal.flow.MobileCheckoutState
import pl.simpay.android.checkout.mobile.internal.flow.MobileCheckoutViewModel

/**
 * The SimPay payment sheet.
 *
 * Built with plain Android views so an integrating app is never forced to use Compose.
 * Returning from the browser always leads to a status check, never to a success state.
 */
internal class SimPayCheckoutSheetFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CHECKOUT_ID = "checkout_id"

        fun newInstance(checkoutId: String): SimPayCheckoutSheetFragment =
            SimPayCheckoutSheetFragment().apply {
                arguments = bundleOf(ARG_CHECKOUT_ID to checkoutId)
            }
    }

    private lateinit var checkoutId: String
    private lateinit var viewModel: MobileCheckoutViewModel

    private lateinit var amountLabel: TextView
    private lateinit var merchantLabel: TextView
    private lateinit var contentContainer: LinearLayout

    private var blikInput: EditText? = null
    private var redirectLaunched = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkoutId = requireArguments().getString(ARG_CHECKOUT_ID).orEmpty()
        val pendingCheckout = MobileCheckoutRegistry.find(checkoutId)
        if (pendingCheckout == null) {
            dismissAllowingStateLoss()
            return
        }

        viewModel = ViewModelProvider(
            this,
            MobileCheckoutViewModel.factory(
                api = pendingCheckout.api,
                order = pendingCheckout.order,
                // Direct channels require the agent of the client that continues the
                // payment, so it is read from the device rather than hardcoded.
                userAgent = DeviceUserAgent.resolve(requireContext())
            )
        )[MobileCheckoutViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = buildRoot()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.controller.state.collect(::render)
            }
        }

        viewModel.controller.loadChannelsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        expandSheet()
    }

    /**
     * Opens the sheet fully instead of at the default peek height.
     *
     * The peek height is derived from the screen height, so in landscape it leaves only
     * a strip of the payment visible. Collapsing is also disabled: a half closed payment
     * sheet has no useful state, and the payer can still dismiss it by dragging down.
     *
     * The height stays WRAP_CONTENT. CoordinatorLayout measures the sheet with the
     * parent height as the upper bound, so tall content is capped by the screen and the
     * ScrollView built in [buildRoot] takes over the scrolling.
     */
    private fun expandSheet() {
        val sheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return

        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onResume() {
        super.onResume()

        if (redirectLaunched) {
            // Coming back from the browser proves nothing; always verify the status.
            redirectLaunched = false
            viewModel.controller.onReturnedFromBrowser()
        } else {
            viewModel.controller.resumeStatusCheckIfNeeded()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        finishWithCurrentResult()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity?.isChangingConfigurations == true) {
            return
        }
        finishWithCurrentResult()
    }

    private fun buildRoot(): View {
        val context = requireContext()
        val pendingCheckout = MobileCheckoutRegistry.find(checkoutId)

        val root = SheetViews.verticalContainer(context).apply {
            background = topRoundedBackground(SheetStyle.BACKGROUND, context.dp(24).toFloat())
            setPadding(context.dp(20), context.dp(10), context.dp(20), context.dp(20))
        }

        val grabber = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = context.dp(14)
            }
            background = roundedBackground(SheetStyle.BORDER, context.dp(2).toFloat())
        }

        merchantLabel = TextView(context).apply {
            text = pendingCheckout?.merchantDisplayName.orEmpty()
            setTextColor(SheetStyle.TEXT_PRIMARY)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }

        amountLabel = TextView(context).apply {
            val order = pendingCheckout?.order
            text = order?.let {
                AmountFormatter.format(context, it.amount, it.currency)
            }.orEmpty()
            setTextColor(SheetStyle.TEXT_PRIMARY)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }

        // Recipient on the left, amount due on the right, mirroring the SimPay gateway.
        val summary = SheetViews.card(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(
                SheetViews.verticalContainer(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    addView(SheetViews.caption(context, getString(R.string.simpay_recipient)))
                    addView(merchantLabel)
                }
            )
            addView(
                SheetViews.verticalContainer(context).apply {
                    addView(
                        SheetViews.caption(context, getString(R.string.simpay_amount_due)).apply {
                            gravity = Gravity.END
                        }
                    )
                    addView(amountLabel)
                }
            )
            addView(SheetViews.accentBar(context))
        }

        contentContainer = SheetViews.verticalContainer(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val secureNote = SheetViews.caption(
            context,
            getString(R.string.simpay_secure_note)
        ).apply {
            gravity = Gravity.CENTER
            // A drawn lock instead of the emoji, which renders differently on every vendor
            // skin and does not follow the caption colour.
            val lock = AppCompatResources.getDrawable(context, R.drawable.simpay_ic_lock_keyhole)
                ?.mutate()
                ?.apply {
                    setBounds(0, 0, context.dp(14), context.dp(14))
                    setTint(SheetStyle.TEXT_SECONDARY)
                }
            setCompoundDrawablesRelative(lock, null, null, null)
            compoundDrawablePadding = context.dp(6)
        }

        root.addView(grabber)
        root.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(SheetViews.spacer(context, 18))
        root.addView(contentContainer)
        root.addView(SheetViews.spacer(context, 18))
        root.addView(
            secureNote,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        return SheetViews.scroll(context, root)
    }

    private fun render(state: MobileCheckoutState) {
        val context = requireContext()
        contentContainer.removeAllViews()
        blikInput = null

        when (state) {
            MobileCheckoutState.LoadingChannels ->
                renderStatus(context, getString(R.string.simpay_loading_channels), showProgress = true)

            is MobileCheckoutState.Channels -> renderChannels(context, state.channels)

            is MobileCheckoutState.CreatingTransaction ->
                renderStatus(context, getString(R.string.simpay_preparing_payment), showProgress = true)

            is MobileCheckoutState.EnteringBlikCode -> renderBlikEntry(context, state)

            is MobileCheckoutState.SubmittingBlikCode ->
                renderStatus(context, getString(R.string.simpay_sending_code), showProgress = true)

            is MobileCheckoutState.AwaitingBankConfirmation ->
                renderStatus(
                    context,
                    getString(R.string.simpay_awaiting_bank),
                    showProgress = true,
                    detail = getString(R.string.simpay_awaiting_bank_detail)
                )

            is MobileCheckoutState.Redirecting -> {
                renderStatus(context, getString(R.string.simpay_opening_page), showProgress = true)
                launchRedirect(state.redirectUrl)
            }

            is MobileCheckoutState.CheckingStatus ->
                renderStatus(context, getString(R.string.simpay_checking_status), showProgress = true)

            is MobileCheckoutState.Succeeded -> renderSuccess(context)

            is MobileCheckoutState.Pending -> renderPending(context)

            is MobileCheckoutState.VerificationRequired ->
                renderTerminal(
                    context,
                    title = getString(R.string.simpay_verification_title),
                    detail = getString(R.string.simpay_verification_detail),
                    color = SheetStyle.TEXT_PRIMARY
                )

            is MobileCheckoutState.Expired ->
                renderTerminal(
                    context,
                    title = getString(R.string.simpay_expired_title),
                    detail = getString(R.string.simpay_expired_detail),
                    color = SheetStyle.DANGER
                )

            is MobileCheckoutState.Refunded ->
                renderTerminal(
                    context,
                    title = getString(R.string.simpay_refunded_title),
                    detail = null,
                    color = SheetStyle.TEXT_PRIMARY
                )

            is MobileCheckoutState.Failed -> renderFailure(context, state)
        }

        viewModel.rememberTransaction()
    }


    private fun renderChannels(context: android.content.Context, channels: List<PaymentChannel>) {
        if (channels.isEmpty()) {
            renderStatus(context, getString(R.string.simpay_channels_empty), showProgress = false)
            contentContainer.addView(SheetViews.spacer(context, 12))
            contentContainer.addView(
                SheetViews.secondaryButton(context, getString(R.string.simpay_action_try_again)).apply {
                    setOnClickListener { viewModel.controller.loadChannels(force = true) }
                }
            )
            return
        }

        contentContainer.addView(
            SheetViews.sectionTitle(context, getString(R.string.simpay_choose_method))
        )
        contentContainer.addView(SheetViews.spacer(context, 12))

        // BLIK first: it is the only method completed without leaving the app.
        val ordered = channels.sortedByDescending { BlikLevel0.supports(it) }

        ordered.forEach { channel ->
            val highlighted = BlikLevel0.supports(channel)
            val row = SheetViews.channelRow(
                context = context,
                // The API names the Level 0 channel "BLIK Level 0", which means nothing
                // to a payer; they look for the brand name.
                name = if (highlighted) getString(R.string.simpay_blik_name) else channel.name,
                subtitle = if (highlighted) {
                    getString(R.string.simpay_blik_subtitle)
                } else {
                    channel.type
                },
                highlighted = highlighted
            )
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = context.dp(10) }

            (row.getTag(SheetViews.TAG_LOGO) as? android.widget.ImageView)?.let { logo ->
                ChannelLogoLoader.load(viewLifecycleOwner.lifecycleScope, channel.img, logo)
            }

            row.setOnClickListener {
                // Controller ignores repeated selections while work is in flight.
                viewModel.controller.selectChannel(channel)
            }
            contentContainer.addView(row)
        }

        contentContainer.addView(SheetViews.spacer(context, 4))
        contentContainer.addView(
            SheetViews.secondaryButton(context, getString(R.string.simpay_action_cancel)).apply {
                setOnClickListener {
                    finishWith(CheckoutResult.Cancelled)
                    dismissAllowingStateLoss()
                }
            }
        )
    }

    private fun renderBlikEntry(
        context: android.content.Context,
        state: MobileCheckoutState.EnteringBlikCode
    ) {
        contentContainer.addView(
            SheetViews.sectionTitle(context, getString(R.string.simpay_blik_title))
        )
        contentContainer.addView(SheetViews.spacer(context, 6))
        contentContainer.addView(
            SheetViews.body(context, getString(R.string.simpay_blik_hint))
        )
        contentContainer.addView(SheetViews.spacer(context, 14))

        val payButton = SheetViews.primaryButton(context, getString(R.string.simpay_action_pay))
        SheetViews.setButtonEnabled(context, payButton, false)

        val input = SheetViews.blikInput(context) { value ->
            // The button becomes active only with exactly six digits.
            SheetViews.setButtonEnabled(
                context,
                payButton,
                BlikCode.isValid(value)
            )
        }
        blikInput = input

        contentContainer.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        state.error?.let { error ->
            val message = when (error) {
                BlikEntryError.InvalidFormat -> R.string.simpay_blik_invalid_code
                BlikEntryError.CodeRejected -> R.string.simpay_blik_rejected_code
            }
            contentContainer.addView(SheetViews.spacer(context, 10))
            contentContainer.addView(SheetViews.body(context, getString(message), SheetStyle.DANGER))

            // A rejected code closes the transaction, so the next attempt is a new one.
            if (state.transactionId == null && error == BlikEntryError.CodeRejected) {
                contentContainer.addView(SheetViews.spacer(context, 4))
                contentContainer.addView(
                    SheetViews.body(context, getString(R.string.simpay_blik_new_payment))
                )
            }
        }

        contentContainer.addView(SheetViews.spacer(context, 16))
        payButton.setOnClickListener {
            SheetViews.setButtonEnabled(context, payButton, false)
            viewModel.controller.submitBlikCode(input.text?.toString().orEmpty())
        }
        contentContainer.addView(
            payButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        contentContainer.addView(SheetViews.spacer(context, 8))
        contentContainer.addView(
            SheetViews.secondaryButton(context, getString(R.string.simpay_action_cancel)).apply {
                setOnClickListener {
                    finishWithCurrentResult()
                    dismissAllowingStateLoss()
                }
            }
        )
    }

    private fun renderStatus(
        context: android.content.Context,
        title: String,
        showProgress: Boolean,
        detail: String? = null
    ) {
        val block = SheetViews.verticalContainer(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(18), 0, context.dp(18))
        }
        if (showProgress) {
            block.addView(SheetViews.progress(context))
            block.addView(SheetViews.spacer(context, 14))
        }
        block.addView(
            SheetViews.body(context, title, SheetStyle.TEXT_PRIMARY).apply {
                typeface = Typeface.DEFAULT_BOLD
                textSize = 17f
                gravity = Gravity.CENTER
            }
        )
        detail?.let {
            block.addView(SheetViews.spacer(context, 6))
            block.addView(SheetViews.body(context, it).apply { gravity = Gravity.CENTER })
        }
        contentContainer.addView(block)
    }

    private fun renderSuccess(context: android.content.Context) {
        contentContainer.addView(
            SheetViews.illustration(context, R.drawable.simpay_ic_payment_success)
        )
        renderTerminal(
            context,
            title = getString(R.string.simpay_success_title),
            detail = getString(R.string.simpay_success_detail),
            color = SheetStyle.SUCCESS,
            closeLabel = getString(R.string.simpay_action_done),
            centered = true
        )
    }

    private fun renderPending(context: android.content.Context) {
        renderTerminal(
            context,
            title = getString(R.string.simpay_pending_title),
            detail = getString(R.string.simpay_pending_detail),
            color = SheetStyle.TEXT_PRIMARY
        )
    }

    private fun renderTerminal(
        context: android.content.Context,
        title: String,
        detail: String?,
        color: Int,
        closeLabel: String = getString(R.string.simpay_action_close),
        centered: Boolean = false
    ) {
        val alignment = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        contentContainer.addView(
            SheetViews.body(context, title, color).apply {
                typeface = Typeface.DEFAULT_BOLD
                textSize = 18f
                gravity = alignment
            }
        )
        detail?.let {
            contentContainer.addView(SheetViews.spacer(context, 6))
            contentContainer.addView(
                SheetViews.body(context, it).apply { gravity = alignment }
            )
        }
        contentContainer.addView(SheetViews.spacer(context, 18))
        contentContainer.addView(
            SheetViews.primaryButton(context, closeLabel).apply {
                setOnClickListener {
                    finishWithCurrentResult()
                    dismissAllowingStateLoss()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun renderFailure(
        context: android.content.Context,
        state: MobileCheckoutState.Failed
    ) {
        contentContainer.addView(
            SheetViews.illustration(context, R.drawable.simpay_ic_payment_failed)
        )
        contentContainer.addView(
            SheetViews.body(context, getString(R.string.simpay_failure_title), SheetStyle.DANGER).apply {
                typeface = Typeface.DEFAULT_BOLD
                textSize = 18f
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        contentContainer.addView(SheetViews.spacer(context, 6))
        contentContainer.addView(
            SheetViews.body(context, getString(describe(state.error))).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        contentContainer.addView(SheetViews.spacer(context, 18))

        // Retrying is offered only when repeating the action is provably safe.
        if (state.retryable && state.transactionId == null) {
            contentContainer.addView(
                SheetViews.primaryButton(context, getString(R.string.simpay_action_try_again)).apply {
                    setOnClickListener { viewModel.controller.loadChannels(force = true) }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            contentContainer.addView(SheetViews.spacer(context, 8))
        }

        contentContainer.addView(
            SheetViews.secondaryButton(context, getString(R.string.simpay_action_close)).apply {
                setOnClickListener {
                    finishWithCurrentResult()
                    dismissAllowingStateLoss()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /**
     * Maps an error to a localised explanation. API messages are never shown to the
     * payer: they are English, technical, and meant for the integrator.
     */
    @StringRes
    private fun describe(error: MobileCheckoutError): Int = when (error) {
        MobileCheckoutError.NoInternet -> R.string.simpay_error_no_internet
        MobileCheckoutError.Timeout -> R.string.simpay_error_timeout
        MobileCheckoutError.Indeterminate -> R.string.simpay_error_indeterminate
        MobileCheckoutError.MissingPayerIp -> R.string.simpay_error_missing_payer_ip
        MobileCheckoutError.MissingCountryCode -> R.string.simpay_error_missing_country
        MobileCheckoutError.RateLimited -> R.string.simpay_error_rate_limited
        MobileCheckoutError.BrowserUnavailable -> R.string.simpay_error_browser_unavailable
        MobileCheckoutError.TransactionNotFound -> R.string.simpay_error_transaction_not_found
        MobileCheckoutError.BlikRejected -> R.string.simpay_error_blik_rejected
        else -> R.string.simpay_error_generic
    }


    private fun launchRedirect(url: String) {
        if (redirectLaunched) {
            return
        }
        redirectLaunched = true

        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(requireContext(), uri)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                redirectLaunched = false
                finishWith(
                    CheckoutResult.Failed(
                        error = MobileCheckoutError.BrowserUnavailable,
                        transactionId = viewModel.controller.transactionId,
                        message = "No browser available"
                    )
                )
                dismissAllowingStateLoss()
            }
        }
    }

    private fun finishWithCurrentResult() {
        finishWith(viewModel.controller.currentResult())
    }

    private fun finishWith(result: CheckoutResult) {
        if (finished) {
            return
        }
        finished = true
        MobileCheckoutRegistry.finish(checkoutId, result)
    }
}


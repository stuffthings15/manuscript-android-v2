package com.echolink.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AccountActivity : Activity() {
    private lateinit var store: AndroidAccountSessionStore
    private lateinit var client: AccountApiClient
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var signIn: Button
    private lateinit var create: Button
    private lateinit var signOut: Button
    private var working = false
    private var verifyOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        verifyOnly = intent.getBooleanExtra(EXTRA_VERIFY_ONLY, false)
        store = AndroidAccountSessionStore(this)
        client = AccountApiClient(BuildConfig.MANUSCRIPT_ACCOUNT_SERVICE_URL)
        buildUi()
        store.load()?.let(::verifySession)
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(COLOR_BACKGROUND)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.account_title)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.account_explanation)
            textSize = 15f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(8), 0, dp(20))
        })
        email = field(R.string.account_email).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
        password = field(R.string.account_password).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
        content.addView(email, matchWidth(10))
        content.addView(password, matchWidth(12))
        signIn = button(R.string.account_sign_in) { authenticate(createAccount = false) }
        create = button(R.string.account_create) { authenticate(createAccount = true) }
        signOut = button(R.string.account_sign_out) {
            store.clear()
            status.text = getString(R.string.account_signed_out)
            signOut.visibility = Button.GONE
        }.apply { visibility = Button.GONE }
        content.addView(signIn, matchWidth(8))
        content.addView(create, matchWidth(8))
        content.addView(button(R.string.account_forgot_password) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                AccountApiContract.recoveryUrl(BuildConfig.MANUSCRIPT_ACCOUNT_SERVICE_URL),
            )))
        }, matchWidth(8))
        content.addView(signOut, matchWidth(8))
        content.addView(button(R.string.account_delete) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(AccountApiContract.deletionUrl(BuildConfig.MANUSCRIPT_ACCOUNT_SERVICE_URL)),
                ),
            )
        }, matchWidth(8))
        status = TextView(this).apply {
            text = getString(R.string.account_status_signed_out)
            textSize = 15f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(status, matchWidth())
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun authenticate(createAccount: Boolean) {
        if (working) return
        val emailValue = email.text.toString()
        val passwordValue = password.text.toString()
        setWorking(true, R.string.account_status_working)
        Thread {
            runCatching {
                if (createAccount) client.register(emailValue, passwordValue)
                else AccountRegistrationResult.Active(client.signIn(emailValue, passwordValue))
            }.onSuccess { result ->
                runOnUiThread {
                    password.text?.clear()
                    when (result) {
                        is AccountRegistrationResult.Active -> {
                            store.save(result.session)
                            verifySession(result.session)
                        }
                        is AccountRegistrationResult.VerificationRequired -> {
                            setWorking(false, R.string.account_verify_email)
                        }
                    }
                }
            }.onFailure {
                runOnUiThread { setWorking(false, R.string.account_failed) }
            }
        }.start()
    }

    private fun verifySession(session: AccountSession) {
        if (working) return
        setWorking(true, R.string.account_status_checking)
        Thread {
            runCatching { client.verifyEntitlement(session) }
                .onSuccess { verification ->
                    store.save(verification.session)
                    val entitlement = verification.entitlement
                    runOnUiThread {
                        setWorking(false, if (entitlement.active) {
                            R.string.account_active
                        } else {
                            R.string.account_inactive
                        })
                        signOut.visibility = Button.VISIBLE
                        if (entitlement.active && verifyOnly) {
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, verification.session.accountId))
                            finish()
                        }
                    }
                }
                .onFailure {
                    runOnUiThread { setWorking(false, R.string.account_check_failed) }
                }
        }.start()
    }

    private fun setWorking(value: Boolean, message: Int) {
        working = value
        email.isEnabled = !value
        password.isEnabled = !value
        signIn.isEnabled = !value
        create.isEnabled = !value
        signOut.isEnabled = !value
        status.text = getString(message)
    }

    private fun field(hint: Int): EditText = EditText(this).apply {
        setHint(hint)
        setHintTextColor(COLOR_MUTED)
        setTextColor(COLOR_TEXT)
        textSize = 16f
        minHeight = dp(52)
        isSaveEnabled = false
    }

    private fun button(label: Int, action: () -> Unit): Button = Button(this).apply {
        setText(label)
        isAllCaps = false
        minHeight = dp(52)
        setOnClickListener { action() }
    }

    private fun matchWidth(bottomMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.bottomMargin = dp(bottomMargin) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_VERIFY_ONLY = "com.remote.Manuscript.v2.VERIFY_ACCOUNT_ONLY"
        const val EXTRA_ACCOUNT_ID = "com.remote.Manuscript.v2.ACCOUNT_ID"
        const val REQUEST_VERIFY_ACCOUNT = 9031
        private val COLOR_BACKGROUND = Color.rgb(23, 18, 13)
        private val COLOR_TEXT = Color.rgb(247, 231, 198)
        private val COLOR_MUTED = Color.rgb(242, 214, 162)
    }
}

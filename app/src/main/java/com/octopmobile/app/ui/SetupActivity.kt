package com.octopmobile.app.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.octopmobile.app.R
import com.octopmobile.app.data.AppPrefs
import com.octopmobile.app.data.UrlNormalizer
import com.octopmobile.app.databinding.ActivitySetupBinding
import com.octopmobile.app.net.InstanceProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 配置实例地址。只有在 `/api/health` 校验通过后才会落库，
 * 避免用户把一个错的地址存下来、然后在主界面看到白屏。
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        lifecycleScope.launch {
            binding.hostInput.setText(prefs.currentHost().orEmpty())
            binding.allowCleartextSwitch.isChecked = prefs.allowCleartext.first()
        }

        binding.connectButton.setOnClickListener { connect() }
        binding.hostInput.setOnEditorActionListener { _, actionId, _ ->
            val submitted = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
            if (submitted) connect()
            submitted
        }
    }

    private fun connect() {
        when (val outcome = UrlNormalizer.normalize(binding.hostInput.text?.toString().orEmpty())) {
            is UrlNormalizer.Outcome.Invalid -> {
                showError(messageFor(outcome.reason))
            }

            is UrlNormalizer.Outcome.Valid -> {
                if (outcome.isCleartext && !binding.allowCleartextSwitch.isChecked) {
                    showError(getString(R.string.err_cleartext_disabled))
                    return
                }
                probe(outcome)
            }
        }
    }

    private fun probe(outcome: UrlNormalizer.Outcome.Valid) {
        setBusy(true)
        lifecycleScope.launch {
            when (val result = InstanceProbe.probe(outcome.baseUrl)) {
                is InstanceProbe.Result.Ok -> {
                    prefs.setAllowCleartext(binding.allowCleartextSwitch.isChecked)
                    prefs.setHost(outcome.baseUrl)
                    setBusy(false)
                    finish()
                }

                is InstanceProbe.Result.Fail -> {
                    setBusy(false)
                    showError(messageFor(result))
                }
            }
        }
    }

    private fun messageFor(reason: UrlNormalizer.Reason): String = when (reason) {
        UrlNormalizer.Reason.EMPTY -> getString(R.string.err_empty)
        UrlNormalizer.Reason.MALFORMED -> getString(R.string.err_bad_url)
        UrlNormalizer.Reason.UNSUPPORTED_SCHEME -> getString(R.string.err_bad_scheme)
        UrlNormalizer.Reason.HAS_PATH -> getString(R.string.err_has_path)
    }

    private fun messageFor(fail: InstanceProbe.Result.Fail): String = when (fail.reason) {
        InstanceProbe.Reason.DNS -> getString(R.string.err_dns)
        InstanceProbe.Reason.TIMEOUT -> getString(R.string.err_timeout)
        InstanceProbe.Reason.TLS -> getString(R.string.err_tls)
        InstanceProbe.Reason.HTTP_STATUS ->
            getString(R.string.err_http_status, fail.detail?.toIntOrNull() ?: 0)

        InstanceProbe.Reason.NOT_OCTOP -> getString(R.string.err_not_octop)
        InstanceProbe.Reason.NETWORK -> getString(R.string.err_network)
        InstanceProbe.Reason.UNKNOWN -> getString(R.string.err_unknown, fail.detail.orEmpty())
    }

    private fun setBusy(busy: Boolean) {
        binding.connectButton.isEnabled = !busy
        binding.hostInput.isEnabled = !busy
        binding.allowCleartextSwitch.isEnabled = !busy
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.connectButton.text =
            if (busy) getString(R.string.setup_connecting) else getString(R.string.setup_connect)

        if (busy) {
            binding.errorText.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.hostInput.windowToken, 0)
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }
}

package com.octopmobile.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.octopmobile.app.BuildConfig
import com.octopmobile.app.Const
import com.octopmobile.app.R
import com.octopmobile.app.data.AppPrefs
import com.octopmobile.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.versionText.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        binding.projectButton.setOnClickListener { openProjectPage() }
        binding.changeHostButton.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        binding.logoutButton.setOnClickListener { logout() }

        lifecycleScope.launch {
            val host = prefs.currentHost()
            binding.hostValue.text =
                if (host.isNullOrBlank()) getString(R.string.settings_no_host) else host

            binding.allowCleartextSwitch.isChecked = prefs.allowCleartext.first()
            binding.allowCleartextSwitch.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { prefs.setAllowCleartext(checked) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val host = prefs.currentHost()
            binding.hostValue.text =
                if (host.isNullOrBlank()) getString(R.string.settings_no_host) else host
        }
    }

    private fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        toast(getString(R.string.settings_logged_out))
    }

    private fun openProjectPage() {
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(Const.PROJECT_URL))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.err_no_app, "https"))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

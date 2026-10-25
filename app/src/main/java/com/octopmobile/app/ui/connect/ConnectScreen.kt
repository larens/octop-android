package com.octopmobile.app.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.octopmobile.app.OctopApp
import com.octopmobile.app.R
import com.octopmobile.app.data.UrlNormalizer
import com.octopmobile.app.net.InstanceProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 连接向导：填实例地址 → `/api/health` 校验 → 落库（docs/spec.md §11 M1，本页在 M0 先落地）。
 *
 * 地址不通过校验就绝不写 DataStore —— 否则主界面会以一个「白屏」的形式暴露配置错误。
 */
@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as OctopApp).container }
    val scope = rememberCoroutineScope()

    var input by rememberSaveable { mutableStateOf("") }
    var allowCleartext by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // 回填已配置的地址，改地址时不用重新敲一遍
        input = container.prefs.currentHost().orEmpty()
        allowCleartext = container.prefs.allowCleartext.first()
    }

    fun normalizeMessage(reason: UrlNormalizer.Reason): String = context.getString(
        when (reason) {
            UrlNormalizer.Reason.EMPTY -> R.string.err_empty
            UrlNormalizer.Reason.MALFORMED -> R.string.err_bad_url
            UrlNormalizer.Reason.UNSUPPORTED_SCHEME -> R.string.err_bad_scheme
            UrlNormalizer.Reason.HAS_PATH -> R.string.err_has_path
        },
    )

    fun probeMessage(failure: InstanceProbe.Result.Fail): String = when (failure.reason) {
        InstanceProbe.Reason.DNS -> context.getString(R.string.err_dns)
        InstanceProbe.Reason.TIMEOUT -> context.getString(R.string.err_timeout)
        InstanceProbe.Reason.TLS -> context.getString(R.string.err_tls)
        InstanceProbe.Reason.NOT_OCTOP -> context.getString(R.string.err_not_octop)
        InstanceProbe.Reason.NETWORK -> context.getString(R.string.err_network)
        InstanceProbe.Reason.UNKNOWN -> context.getString(R.string.err_unknown, failure.detail.orEmpty())
        InstanceProbe.Reason.HTTP_STATUS ->
            context.getString(R.string.err_http_status, failure.detail?.toIntOrNull() ?: 0)
    }

    fun connect() {
        when (val outcome = UrlNormalizer.normalize(input)) {
            is UrlNormalizer.Outcome.Invalid -> error = normalizeMessage(outcome.reason)

            is UrlNormalizer.Outcome.Valid -> {
                if (outcome.isCleartext && !allowCleartext) {
                    error = context.getString(R.string.err_cleartext_disabled)
                    return
                }
                busy = true
                error = null
                scope.launch {
                    when (val result = InstanceProbe.probe(outcome.baseUrl)) {
                        is InstanceProbe.Result.Ok -> {
                            container.prefs.setHost(outcome.baseUrl)
                            busy = false
                            onConnected()
                        }

                        is InstanceProbe.Result.Fail -> {
                            busy = false
                            error = probeMessage(result)
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
            isError = error != null,
            label = { Text(stringResource(R.string.settings_host_label)) },
            placeholder = { Text(stringResource(R.string.setup_host_hint)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { if (!busy) connect() }),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_allow_cleartext),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_allow_cleartext_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = allowCleartext,
                enabled = !busy,
                onCheckedChange = { checked ->
                    allowCleartext = checked
                    scope.launch { container.prefs.setAllowCleartext(checked) }
                },
            )
        }

        val message = error
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { connect() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    text = stringResource(R.string.setup_connecting),
                    modifier = Modifier.padding(start = 12.dp),
                )
            } else {
                Text(stringResource(R.string.setup_connect))
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.setup_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

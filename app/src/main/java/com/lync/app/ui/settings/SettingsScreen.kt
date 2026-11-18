package com.lync.app.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.lync.app.BuildConfig
import com.lync.app.R
import com.lync.app.ui.LocalAppContainer
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.LocalThemeController
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.pressable
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens

@Composable
fun SettingsScreen() {
    val c = Campus.colors
    val container = LocalAppContainer.current
    val nav = LocalNavigator.current
    val theme = LocalThemeController.current
    var themeMode by remember { mutableStateOf(container.session.themeMode) }
    val lang = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { java.util.Locale.getDefault().language }

    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), { nav.back() })
            Text(stringResource(R.string.profile_settings), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(start = 12.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.Gutter)) {
            Section(stringResource(R.string.settings_appearance))
            Group {
                listOf("system" to R.string.settings_theme_system, "light" to R.string.settings_theme_light, "dark" to R.string.settings_theme_dark).forEachIndexed { i, (mode, label) ->
                    if (i > 0) Divider()
                    Choice(stringResource(label), themeMode == mode) { themeMode = mode; theme.set(mode) }
                }
            }

            Section(stringResource(R.string.profile_language))
            Group {
                Choice("English", !lang.startsWith("fr")) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
                Divider()
                Choice("Français", lang.startsWith("fr")) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr")) }
            }

            Section(stringResource(R.string.settings_developer))
            Group {
                NavRow(stringResource(R.string.settings_developer_mode)) { nav.indoorDev() }
            }

            Spacer(Modifier.height(32.dp))
            Text("Lync ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Campus.colors.textMuted, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 4.dp))
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.RadiusControl)).background(Campus.colors.surface)) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Campus.colors.border))
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    val c = Campus.colors
    Row(Modifier.fillMaxWidth().pressable(onClick, 0.99f).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textMuted)
    }
}

@Composable
private fun Choice(title: String, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    val c = Campus.colors
    Row(Modifier.fillMaxWidth().pressable(onClick, 0.99f).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
        }
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (selected) c.text else c.border), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = c.bg, modifier = Modifier.size(14.dp))
        }
    }
}

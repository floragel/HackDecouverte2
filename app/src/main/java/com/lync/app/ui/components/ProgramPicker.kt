package com.lync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lync.app.R
import com.lync.app.data.ProgramsCatalog
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens

/** Looks like a field; opens a searchable sheet with every program of the university. */
@Composable
fun ProgramField(value: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Campus.colors
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.RadiusControl)).background(c.surface).pressable({ open = true }, 0.98f).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value ?: stringResource(R.string.onb_program), style = MaterialTheme.typography.bodyLarge, color = if (value == null) c.textMuted else c.text,
            modifier = Modifier.weight(1f), maxLines = 1)
        Icon(Icons.Rounded.ExpandMore, null, tint = c.textMuted)
    }
    if (open) ProgramSheet(value, onDismiss = { open = false }) { onPick(it); open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgramSheet(current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = Campus.colors
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val results = remember(query) { ProgramsCatalog.search(context, query) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Text(stringResource(R.string.program_pick_title), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(horizontal = Tokens.Gutter))
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.padding(horizontal = Tokens.Gutter).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(Tokens.RadiusControl)).background(c.surface).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.program_search), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxWidth().height(460.dp)) {
                items(results, key = { it.slug }) { p ->
                    val on = p.label == current || p.name == current
                    Row(Modifier.fillMaxWidth().pressable({ onPick(p.name) }, 0.99f).padding(horizontal = Tokens.Gutter, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                            if (p.degree.isNotBlank()) Text(p.degree, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                        if (on) Icon(Icons.Rounded.Check, null, tint = c.accent)
                    }
                }
            }
        }
    }
}

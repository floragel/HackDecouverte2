package com.lync.app.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lync.app.R
import com.lync.app.data.model.ProfileEdit
import com.lync.app.ui.LocalAppContainer
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.ProgramField
import com.lync.app.ui.components.pressable
import com.lync.app.ui.compose.Field
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen() {
    val c = Campus.colors
    val repo = LocalAppContainer.current.repository
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = repo.me.value ?: return

    var name by remember { mutableStateOf(me.fullName) }
    var bio by remember { mutableStateOf(me.bio.orEmpty()) }
    var program by remember { mutableStateOf(me.program) }
    var year by remember { mutableStateOf(me.year) }
    val interests = remember { mutableStateListOf(*me.interests.toTypedArray()) }
    var newInterest by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) avatar = it }

    val save: () -> Unit = {
        saving = true
        scope.launch {
            val bytes = avatar?.let { uri -> withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } }
            val r = repo.updateProfile(ProfileEdit(name, bio, program, year, interests.toList(), bytes))
            saving = false
            if (r.isSuccess) nav.back()
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), { nav.back() })
            Text(stringResource(R.string.profile_edit), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Pill(stringResource(R.string.action_save), { if (!saving && name.isNotBlank()) save() }, style = if (name.isNotBlank()) PillStyle.Filled else PillStyle.Tonal)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.Gutter)) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.align(Alignment.CenterHorizontally).pressable({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })) {
                if (avatar != null) AsyncImage(avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.size(104.dp).clip(CircleShape))
                else Avatar(me.avatarUrl, me.initials, 104.dp)
                Box(Modifier.align(Alignment.BottomEnd).size(34.dp).clip(CircleShape).background(c.bg).padding(3.dp).clip(CircleShape).background(c.text), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PhotoCamera, null, tint = c.bg, modifier = Modifier.size(16.dp))
                }
            }
            Label(stringResource(R.string.onb_full_name))
            Field(name, { name = it.take(50) }, stringResource(R.string.onb_full_name))
            Label(stringResource(R.string.edit_bio))
            Field(bio, { bio = it.take(150) }, stringResource(R.string.edit_bio_hint), minLines = 2)
            Label(stringResource(R.string.onb_program))
            ProgramField(program, { program = it })
            Label(stringResource(R.string.onb_year))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..4).forEach { y -> Pill("$y", { year = y }, style = if (year == y) PillStyle.Filled else PillStyle.Tonal) }
                Pill("4+", { year = 5 }, style = if (year == 5) PillStyle.Filled else PillStyle.Tonal)
            }
            Label(stringResource(R.string.profile_interests))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                interests.forEach { tag ->
                    Row(Modifier.clip(CircleShape).background(c.surface).pressable({ interests.remove(tag) }).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tag, style = MaterialTheme.typography.labelLarge, color = c.text)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.Close, null, tint = c.textMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(newInterest, { newInterest = it.take(24) }, stringResource(R.string.edit_add_interest)) }
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.edit_add), {
                    val t = newInterest.trim()
                    if (t.isNotEmpty() && t !in interests && interests.size < 8) interests.add(t)
                    newInterest = ""
                }, style = PillStyle.Tonal)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Campus.colors.textMuted, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

package com.lync.app.ui.onboarding

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import com.lync.app.ui.components.Wordmark
import com.lync.app.ui.components.ProgramField
import com.lync.app.ui.theme.Tokens
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lync.app.R
import com.lync.app.data.CampusRepository
import com.lync.app.data.SignUpForm
import com.lync.app.data.model.LocationSharing
import com.lync.app.data.model.University
import com.lync.app.ui.campusViewModel
import com.lync.app.ui.components.DemoBadge
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.PrimaryButton
import com.lync.app.ui.components.pressable
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.toColorOr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnbStep { Welcome, University, About, Location, SignIn }

data class OnbState(
    val step: OnbStep = OnbStep.Welcome,
    val universities: List<University> = emptyList(),
    val university: University? = null,
    val fullName: String = "",
    val email: String = "",
    val password: String = "",
    val program: String = "",
    val year: Int? = 1,
    val loading: Boolean = false,
    val error: String? = null,
)

class OnboardingViewModel(private val repo: CampusRepository) : ViewModel() {
    private val _state = MutableStateFlow(OnbState())
    val state = _state.asStateFlow()
    val isDemo = repo.isDemo

    init {
        viewModelScope.launch {
            runCatching { repo.universities() }.onSuccess { list -> _state.update { it.copy(universities = list) } }
        }
    }

    fun go(step: OnbStep) = _state.update { it.copy(step = step, error = null) }
    fun back() = _state.update { s -> s.copy(step = if (s.step == OnbStep.SignIn) OnbStep.Welcome else OnbStep.entries[(s.step.ordinal - 1).coerceAtLeast(0)], error = null) }
    fun pick(u: University) = _state.update { it.copy(university = u) }
    fun edit(f: (OnbState) -> OnbState) = _state.update(f)

    val aboutValid: Boolean
        get() = with(_state.value) {
            fullName.isNotBlank() && (isDemo || (email.contains('@') && password.length >= 6))
        }

    fun signIn() {
        val s = _state.value
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repo.signIn(s.email, s.password)
                .onSuccess { p -> runCatching { repo.university(p.universityId) }.getOrNull()?.let { u -> _state.update { it.copy(university = u) } } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun finish(sharing: LocationSharing) {
        val s = _state.value
        val uni = s.university ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val email = s.email.ifBlank { "${s.fullName.replace(" ", ".").lowercase()}@live.${uni.emailDomain}" }
            repo.signUp(SignUpForm(uni, s.fullName, email, s.password, s.program, s.year))
                .onSuccess { runCatching { repo.setLocationSharing(sharing) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message, step = OnbStep.About) } }
        }
    }
}

@Composable
fun OnboardingFlow(onUniversityColors: (String, String) -> Unit) {
    val vm = campusViewModel { OnboardingViewModel(it.repository) }
    val s by vm.state.collectAsStateWithLifecycle()

    BackHandler(enabled = s.step != OnbStep.Welcome) { vm.back() }

    // Re-tint the whole app live as soon as a university is picked
    LaunchedEffect(s.university) { s.university?.let { onUniversityColors(it.primaryColor, it.secondaryColor) } }

    Box(Modifier.fillMaxSize().background(Campus.colors.bg)) {
        AnimatedContent(
            targetState = s.step,
            transitionSpec = {
                val fwd = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(320)) { if (fwd) it / 4 else -it / 4 } + fadeIn(tween(320))) togetherWith
                    (slideOutHorizontally(tween(320)) { if (fwd) -it / 4 else it / 4 } + fadeOut(tween(200)))
            },
            label = "onb",
        ) { step ->
            when (step) {
                OnbStep.Welcome -> Welcome(vm.isDemo, onSignIn = { vm.go(OnbStep.SignIn) }) { vm.go(OnbStep.University) }
                OnbStep.SignIn -> SignInStep(s, vm::back, vm::edit) { vm.signIn() }
                OnbStep.University -> UniversityStep(s, vm::back, vm::pick) { vm.go(OnbStep.About) }
                OnbStep.About -> AboutStep(s, vm.isDemo, vm::back, vm::edit, vm.aboutValid) { vm.go(OnbStep.Location) }
                OnbStep.Location -> LocationStep(s.loading, vm::back) { vm.finish(it) }
            }
        }
    }
}

@Composable
private fun StepScaffold(
    title: String,
    onBack: (() -> Unit)?,
    bottom: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.height(44.dp)) {
            if (onBack != null) {
                Box(Modifier.size(44.dp).clip(CircleShape).pressable(onBack), contentAlignment = Alignment.CenterStart) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Campus.colors.text)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, color = Campus.colors.text)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
        bottom()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Welcome(isDemo: Boolean, onSignIn: () -> Unit, onStart: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painterResource(R.drawable.onboarding_hero), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 0.62f to Tokens.Night.copy(alpha = 0.55f), 1f to Tokens.Night.copy(alpha = 0.96f))
            )
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Wordmark(Color.White)
                Spacer(Modifier.weight(1f))
                if (isDemo) DemoBadge()
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.onb_title), style = MaterialTheme.typography.displayLarge, color = Color.White)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.onb_subtitle), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.78f))
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(Tokens.RadiusControl)).background(Color.White).pressable(onStart, 0.98f),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.onb_start), style = MaterialTheme.typography.titleMedium, color = Tokens.Night) }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(48.dp).pressable(onSignIn), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.onb_signin), style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun SignInStep(s: OnbState, onBack: () -> Unit, edit: ((OnbState) -> OnbState) -> Unit, onSubmit: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.signin_title),
        onBack = onBack,
        bottom = { PrimaryButton(stringResource(R.string.signin_button), onSubmit, enabled = s.email.contains('@') && s.password.isNotEmpty(), loading = s.loading) },
    ) {
        Field(stringResource(R.string.onb_email), s.email, { v -> edit { it.copy(email = v) } }, KeyboardType.Email)
        Field(stringResource(R.string.onb_password), s.password, { v -> edit { it.copy(password = v) } }, KeyboardType.Password, password = true)
        s.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UniversityStep(s: OnbState, onBack: () -> Unit, onPick: (University) -> Unit, onNext: () -> Unit) {
    val c = Campus.colors
    StepScaffold(
        title = stringResource(R.string.onb_pick_uni),
        onBack = onBack,
        bottom = { PrimaryButton(stringResource(R.string.onb_continue), onNext, enabled = s.university != null) },
    ) {
        s.universities.forEach { u ->
            val selected = s.university?.id == u.id
            val uniColor = u.primaryColor.toColorOr(c.accent)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) uniColor.copy(alpha = 0.08f) else c.surface)
                    .border(1.5.dp, if (selected) uniColor else c.surface, RoundedCornerShape(18.dp))
                    .pressable({ onPick(u) }, 0.98f)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(uniColor, u.secondaryColor.toColorOr(uniColor)))),
                    contentAlignment = Alignment.Center,
                ) { Text(u.shortName.take(1), color = Color.White, style = MaterialTheme.typography.titleLarge) }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(u.name, style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text("@${u.emailDomain}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                if (selected) Icon(Icons.Rounded.Check, null, tint = uniColor)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.onb_more_soon), style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false) {
    val c = Campus.colors
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.border, focusedLabelColor = c.accent, cursorColor = c.accent,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    )
}

@Composable
private fun AboutStep(
    s: OnbState, isDemo: Boolean, onBack: () -> Unit, edit: ((OnbState) -> OnbState) -> Unit, valid: Boolean, onNext: () -> Unit,
) {
    val c = Campus.colors
    StepScaffold(
        title = stringResource(R.string.onb_about_you),
        onBack = onBack,
        bottom = { PrimaryButton(stringResource(R.string.onb_continue), onNext, enabled = valid) },
    ) {
        Field(stringResource(R.string.onb_full_name), s.fullName, { v -> edit { it.copy(fullName = v) } })
        if (!isDemo) {
            Field(stringResource(R.string.onb_email), s.email, { v -> edit { it.copy(email = v) } }, KeyboardType.Email)
            s.university?.let {
                Text(stringResource(R.string.onb_email_hint, "@${it.emailDomain}"), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp).fillMaxWidth())
            }
            Field(stringResource(R.string.onb_password), s.password, { v -> edit { it.copy(password = v) } }, KeyboardType.Password, password = true)
        }
        ProgramField(s.program.ifBlank { null }, { v -> edit { it.copy(program = v) } }, Modifier.padding(bottom = 12.dp))
        Text(stringResource(R.string.onb_year), style = MaterialTheme.typography.labelLarge, color = c.textMuted, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..4).forEach { y ->
                Pill("$y", { edit { it.copy(year = y) } }, style = if (s.year == y) PillStyle.Filled else PillStyle.Tonal)
            }
        }
        s.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LocationStep(loading: Boolean, onBack: () -> Unit, onDone: (LocationSharing) -> Unit) {
    val c = Campus.colors
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        onDone(if (grants.values.any { it }) LocationSharing.FRIENDS else LocationSharing.OFF)
    }
    StepScaffold(
        title = stringResource(R.string.onb_location_title),
        onBack = onBack,
        bottom = {
            PrimaryButton(stringResource(R.string.onb_location_allow), {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, loading = loading)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(48.dp).pressable({ onDone(LocationSharing.OFF) }), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.onb_location_skip), color = c.textMuted, style = MaterialTheme.typography.titleSmall)
            }
        },
    ) {
        Text(stringResource(R.string.onb_location_body), style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
        Spacer(Modifier.height(40.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(160.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Box(Modifier.size(84.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.LocationOn, null, tint = c.accent, modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

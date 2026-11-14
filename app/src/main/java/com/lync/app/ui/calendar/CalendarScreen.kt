package com.lync.app.ui.calendar

import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lync.app.R
import com.lync.app.data.model.CalendarDaySummary
import com.lync.app.data.model.CalendarFilter
import com.lync.app.data.model.CalendarItem
import com.lync.app.data.model.CalendarItemKind
import com.lync.app.data.model.Profile
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.campusViewModel
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.PrimaryButton
import com.lync.app.ui.components.ScreenHeader
import com.lync.app.ui.components.Segmented
import com.lync.app.ui.components.pressable
import com.lync.app.ui.motion.rememberHaptics
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

@Composable
fun CalendarScreen() {
    val vm = campusViewModel { CalendarViewModel(it.calendar) }
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Campus.colors
    val nav = LocalNavigator.current
    val haptics = rememberHaptics()
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.let { stream ->
                haptics.confirm()
                vm.importStream(stream)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // Screen Header
        ScreenHeader(
            title = stringResource(R.string.calendar_title),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isToday = s.selectedDate == LocalDate.now()
                    if (!isToday) {
                        Pill(
                            text = stringResource(R.string.calendar_today),
                            onClick = {
                                haptics.tick()
                                vm.goToToday()
                            },
                            style = PillStyle.Tonal,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    CircleIconButton(
                        icon = Icons.Rounded.CloudDownload,
                        contentDescription = stringResource(R.string.calendar_import),
                        onClick = {
                            haptics.tick()
                            vm.showImportSheet(true)
                        }
                    )
                }
            }
        )

        // Status banner if imported or updated
        s.status?.let { status ->
            val msg = when (status) {
                is CalendarStatusMessage.Imported -> stringResource(R.string.calendar_msg_imported, status.count)
                is CalendarStatusMessage.Synced -> stringResource(R.string.calendar_msg_synced, status.count)
                is CalendarStatusMessage.Cleared -> stringResource(R.string.calendar_msg_cleared)
                is CalendarStatusMessage.ErrorImport -> stringResource(R.string.calendar_msg_error_import)
                is CalendarStatusMessage.ErrorUrl -> stringResource(R.string.calendar_msg_error_url)
                is CalendarStatusMessage.Error -> status.text
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tokens.Gutter, vertical = 6.dp)
                    .clip(RoundedCornerShape(Tokens.RadiusControl))
                    .background(c.accentSoft)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text,
                    modifier = Modifier.weight(1f)
                )
                CircleIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_close),
                    onClick = { vm.dismissStatus() },
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Month & Navigation strip
        MonthNavigator(
            referenceDate = s.selectedDate,
            onPrev = {
                haptics.tick()
                vm.prevWeek()
            },
            onNext = {
                haptics.tick()
                vm.nextWeek()
            }
        )

        // 7-day week bar
        WeekStrip(
            weekDays = s.weekDays,
            selectedDate = s.selectedDate,
            summaries = s.daySummaries,
            onSelectDate = {
                haptics.tick()
                vm.selectDate(it)
            }
        )

        Spacer(Modifier.height(10.dp))

        // Category Filter (All / Courses / Social)
        val filterOptions = listOf(
            stringResource(R.string.calendar_filter_all),
            stringResource(R.string.calendar_filter_courses),
            stringResource(R.string.calendar_filter_social),
        )
        Segmented(
            options = filterOptions,
            selected = s.filter.ordinal,
            onSelect = {
                haptics.tick()
                vm.setFilter(CalendarFilter.entries[it])
            },
            modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = 4.dp)
        )

        // Day summary bar
        DayHeaderBar(
            date = s.selectedDate,
            coursesCount = s.coursesCount,
            socialCount = s.socialCount
        )

        // List of Events for the Day
        val items = s.filteredItems
        if (items.isEmpty()) {
            CalendarEmptyState(
                date = s.selectedDate,
                onImportClick = {
                    haptics.tick()
                    vm.showImportSheet(true)
                }
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Tokens.Gutter, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    CalendarCard(
                        item = item,
                        onClick = {
                            haptics.tick()
                            vm.selectItemForDetail(item)
                        }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // Modal Sheet for Import
    if (s.showImportSheet) {
        ImportCalendarSheet(
            onDismiss = { vm.showImportSheet(false) },
            onPickFile = { filePickerLauncher.launch("*/*") },
            onSyncConcordia = {
                haptics.confirm()
                vm.syncConcordiaSchedule()
            },
            onImportUrl = { url ->
                haptics.confirm()
                vm.importUrl(url)
            },
            onClear = {
                haptics.confirm()
                vm.clearImported()
            }
        )
    }

    // Modal Sheet for Item Detail
    s.selectedItemForDetail?.let { item ->
        CalendarEventDetailSheet(
            item = item,
            onDismiss = { vm.selectItemForDetail(null) },
            onOpenMap = {
                vm.selectItemForDetail(null)
                nav.map()
            },
            onOpenEvent = { eventId ->
                vm.selectItemForDetail(null)
                nav.event(eventId)
            },
            onOpenPerson = { personId ->
                vm.selectItemForDetail(null)
                nav.user(personId)
            }
        )
    }
}

@Composable
private fun MonthNavigator(
    referenceDate: LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val c = Campus.colors
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val currentLocale = remember(configuration) { configuration.locales[0] }
    val monthTitle = remember(referenceDate, currentLocale) {
        val monthName = referenceDate.month.getDisplayName(TextStyle.FULL, currentLocale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(currentLocale) else it.toString() }
        "$monthName ${referenceDate.year}"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = monthTitle,
            style = MaterialTheme.typography.titleLarge,
            color = c.text
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CircleIconButton(
                icon = Icons.Rounded.ChevronLeft,
                contentDescription = null,
                onClick = onPrev,
                modifier = Modifier.size(36.dp)
            )
            CircleIconButton(
                icon = Icons.Rounded.ChevronRight,
                contentDescription = null,
                onClick = onNext,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
private fun WeekStrip(
    weekDays: List<LocalDate>,
    selectedDate: LocalDate,
    summaries: Map<LocalDate, CalendarDaySummary>,
    onSelectDate: (LocalDate) -> Unit,
) {
    val c = Campus.colors
    val today = LocalDate.now()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val currentLocale = remember(configuration) { configuration.locales[0] }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Gutter),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        weekDays.forEach { date ->
            val isSelected = date == selectedDate
            val isToday = date == today
            val summary = summaries[date]

            val bg by animateColorAsState(
                targetValue = if (isSelected) c.text else c.surface,
                label = "dayBg"
            )
            val fgText by animateColorAsState(
                targetValue = if (isSelected) c.bg else c.text,
                label = "dayFg"
            )
            val fgMuted by animateColorAsState(
                targetValue = if (isSelected) c.bg.copy(alpha = 0.7f) else c.textMuted,
                label = "dayMuted"
            )

            val dayLetter = date.dayOfWeek.getDisplayName(TextStyle.SHORT, currentLocale)
                .take(3)
                .replaceFirstChar { it.uppercase(currentLocale) }
                .replaceFirstChar { it.uppercase() }

            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(Tokens.RadiusControl))
                    .background(bg)
                    .then(
                        if (isToday && !isSelected) Modifier.border(1.5.dp, c.accent, RoundedCornerShape(Tokens.RadiusControl))
                        else Modifier
                    )
                    .pressable({ onSelectDate(date) }, 0.94f)
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = dayLetter,
                    style = MaterialTheme.typography.labelSmall,
                    color = fgMuted
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${date.dayOfMonth}",
                    style = MaterialTheme.typography.titleLarge,
                    color = fgText,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                // Indicators: Gold dot for courses, Bordeaux dot for social
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(6.dp)
                ) {
                    if (summary?.hasCourses == true) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) c.bg else c.accent2)
                        )
                    }
                    if (summary?.hasSocial == true) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) c.bg else c.accent)
                        )
                    }
                    if (summary == null || (!summary.hasCourses && !summary.hasSocial)) {
                        Spacer(Modifier.size(5.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeaderBar(
    date: LocalDate,
    coursesCount: Int,
    socialCount: Int,
) {
    val c = Campus.colors
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val currentLocale = remember(configuration) { configuration.locales[0] }
    val dateFull = remember(date, currentLocale) {
        val dtf = DateTimeFormatter.ofPattern("EEEE d MMMM", currentLocale)
        date.format(dtf).replaceFirstChar { it.uppercase(currentLocale) }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = dateFull,
            style = MaterialTheme.typography.titleMedium,
            color = c.text,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (coursesCount > 0) {
                val coursesLabel = if (coursesCount == 1) stringResource(R.string.calendar_tag_course_one, 1)
                                   else stringResource(R.string.calendar_tag_courses, coursesCount)
                Text(
                    text = coursesLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.accent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(c.accentSoft)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            if (socialCount > 0) {
                Text(
                    text = stringResource(R.string.calendar_tag_social, socialCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.text,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(c.surfaceAlt)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun CalendarCard(
    item: CalendarItem,
    onClick: () -> Unit,
) {
    val c = Campus.colors
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val currentLocale = remember(configuration) { configuration.locales[0] }
    val timeFormat = remember(currentLocale) { SimpleDateFormat("HH:mm", currentLocale) }
    val startStr = timeFormat.format(Date(item.startsAtMillis))
    val endStr = timeFormat.format(Date(item.endsAtMillis))

    val isCourse = item.kind == CalendarItemKind.COURSE
    val indicatorColor = if (isCourse) c.accent2 else c.accent

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.RadiusMedia))
            .background(c.surface)
            .pressable(onClick, 0.98f)
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Time Column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(52.dp)
        ) {
            Text(
                text = startStr,
                style = MaterialTheme.typography.titleMedium,
                color = c.text,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = endStr,
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted
            )
            Spacer(Modifier.height(6.dp))
            // Left color bar
            Box(
                Modifier
                    .width(4.dp)
                    .height(26.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
        }

        Spacer(Modifier.width(12.dp))

        // Content Column
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val badgeText = when (item.kind) {
                    CalendarItemKind.COURSE -> item.courseCode ?: stringResource(R.string.calendar_badge_course)
                    CalendarItemKind.SOCIAL_EVENT -> stringResource(R.string.calendar_badge_event)
                    CalendarItemKind.SOCIAL_PLAN -> stringResource(R.string.calendar_badge_plan)
                    CalendarItemKind.CUSTOM -> stringResource(R.string.calendar_badge_imported)
                }
                val badgeBg = if (isCourse) c.accentSoft else c.surfaceAlt
                val badgeFg = if (isCourse) c.accent else c.text

                Text(
                    text = "${item.emoji}  $badgeText",
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeFg,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )

                if (item.isImported) {
                    Text(
                        text = stringResource(R.string.calendar_badge_imported),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textMuted,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(c.surfaceAlt)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = c.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            item.location?.let { loc ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Place,
                        null,
                        tint = c.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = loc,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Attendees / Classmates
            if (item.attendees.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                        item.attendees.take(3).forEach { p ->
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(c.surface)
                                    .padding(1.dp)
                            ) {
                                Avatar(p.avatarUrl, p.initials, 22.dp)
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val attendeesText = if (isCourse) {
                        if (item.attendees.size == 1) {
                            stringResource(R.string.calendar_attendee_classmate_one, item.attendees[0].firstName)
                        } else {
                            stringResource(R.string.calendar_attendee_classmate_many, item.attendees[0].firstName, item.attendees.size - 1)
                        }
                    } else {
                        if (item.attendees.size == 1) {
                            stringResource(R.string.calendar_attendee_friend_one, item.attendees[0].firstName)
                        } else {
                            stringResource(R.string.calendar_attendee_friend_many, item.attendees[0].firstName, item.attendees.size - 1)
                        }
                    }
                    Text(
                        text = attendeesText,
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarEmptyState(
    date: LocalDate,
    onImportClick: () -> Unit,
) {
    val c = Campus.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Gutter, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(c.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = c.textMuted,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.calendar_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = c.text,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.calendar_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(20.dp))
        Pill(
            text = stringResource(R.string.calendar_import),
            onClick = onImportClick,
            style = PillStyle.Filled,
            icon = Icons.Rounded.CloudDownload
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportCalendarSheet(
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onSyncConcordia: () -> Unit,
    onImportUrl: (String) -> Unit,
    onClear: () -> Unit,
) {
    val c = Campus.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var urlText by remember { mutableStateOf("") }
    var showUrlInput by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Tokens.RadiusCard, topEnd = Tokens.RadiusCard),
        containerColor = c.bg
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Gutter)
                .padding(bottom = 36.dp)
        ) {
            Text(
                text = stringResource(R.string.calendar_import),
                style = MaterialTheme.typography.displaySmall,
                color = c.text
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.calendar_import_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted
            )

            Spacer(Modifier.height(20.dp))

            // Option 1: File picker
            ImportOptionRow(
                icon = Icons.Rounded.UploadFile,
                title = stringResource(R.string.calendar_import_file),
                subtitle = stringResource(R.string.calendar_import_file_desc),
                onClick = onPickFile
            )

            Spacer(Modifier.height(10.dp))

            // Option 2: Sync Concordia semester courses
            ImportOptionRow(
                icon = Icons.Rounded.School,
                title = stringResource(R.string.calendar_import_uni),
                subtitle = stringResource(R.string.calendar_import_uni_desc),
                onClick = onSyncConcordia,
                highlight = true
            )

            Spacer(Modifier.height(10.dp))

            // Option 3: URL Webcal / iCal
            ImportOptionRow(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.calendar_import_url),
                subtitle = stringResource(R.string.calendar_import_url_hint),
                onClick = { showUrlInput = !showUrlInput }
            )

            AnimatedVisibility(showUrlInput) {
                Column(Modifier.padding(top = 10.dp)) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        placeholder = { Text(stringResource(R.string.calendar_import_url_hint), color = c.textMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Tokens.RadiusControl),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = c.accent,
                            unfocusedBorderColor = c.border,
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text
                        ),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton(
                        text = stringResource(R.string.calendar_import_action),
                        onClick = { onImportUrl(urlText) },
                        enabled = urlText.isNotBlank()
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = c.border, thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))

            // Option 4: Clear imported
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Tokens.RadiusControl))
                    .pressable(onClear)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.calendar_clear_imported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted
                )
            }
        }
    }
}

@Composable
private fun ImportOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    val c = Campus.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.RadiusControl))
            .background(if (highlight) c.accentSoft else c.surface)
            .pressable(onClick, 0.98f)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (highlight) c.accent else c.surfaceAlt),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (highlight) c.onAccent else c.text,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = c.text,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = c.textMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarEventDetailSheet(
    item: CalendarItem,
    onDismiss: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenEvent: (String) -> Unit,
    onOpenPerson: (String) -> Unit,
) {
    val c = Campus.colors
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val currentLocale = remember(configuration) { configuration.locales[0] }

    val timeFormat = remember(currentLocale) { SimpleDateFormat("HH:mm", currentLocale) }
    val dateFormat = remember(currentLocale) { SimpleDateFormat("EEEE d MMMM yyyy", currentLocale) }
    val startStr = timeFormat.format(Date(item.startsAtMillis))
    val endStr = timeFormat.format(Date(item.endsAtMillis))
    val dateStr = dateFormat.format(Date(item.startsAtMillis)).replaceFirstChar { it.uppercase(currentLocale) }

    val isCourse = item.kind == CalendarItemKind.COURSE

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Tokens.RadiusCard, topEnd = Tokens.RadiusCard),
        containerColor = c.bg
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Gutter)
                .padding(bottom = 36.dp)
        ) {
            // Badge & Kind
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${item.emoji}  " + when (item.kind) {
                        CalendarItemKind.COURSE -> item.courseCode ?: stringResource(R.string.calendar_badge_course)
                        CalendarItemKind.SOCIAL_EVENT -> stringResource(R.string.calendar_badge_event)
                        CalendarItemKind.SOCIAL_PLAN -> stringResource(R.string.calendar_badge_plan)
                        CalendarItemKind.CUSTOM -> stringResource(R.string.calendar_badge_imported)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isCourse) c.accent else c.text,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isCourse) c.accentSoft else c.surfaceAlt)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Title
            Text(
                text = item.title,
                style = MaterialTheme.typography.displaySmall,
                color = c.text
            )

            item.subtitle?.let { sub ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.textMuted
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = c.border, thickness = 0.5.dp)
            Spacer(Modifier.height(16.dp))

            // Time & Date
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(c.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Schedule, null, tint = c.text, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(text = dateStr, style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text(
                        text = "$startStr – $endStr (${stringResource(R.string.calendar_duration_minutes, item.durationMinutes)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted
                    )
                }
            }

            // Location
            item.location?.let { loc ->
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Tokens.RadiusControl))
                        .pressable(onOpenMap)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(c.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Place, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(text = loc, style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text(
                            text = stringResource(R.string.calendar_open_map),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.accent
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        null,
                        tint = c.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Attendees / Classmates
            if (item.attendees.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(if (isCourse) R.string.calendar_classmates_section else R.string.calendar_friends_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textMuted
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.attendees.forEach { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Tokens.RadiusControl))
                                .background(c.surface)
                                .pressable({ onOpenPerson(p.id) })
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(p.avatarUrl, p.initials, 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.fullName, style = MaterialTheme.typography.titleSmall, color = c.text)
                                Text("@${p.username}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Action: View Campus Event if linked
            item.campusEventId?.let { eventId ->
                PrimaryButton(
                    text = stringResource(R.string.calendar_view_event),
                    onClick = { onOpenEvent(eventId) }
                )
                Spacer(Modifier.height(10.dp))
            }

            // Add to system calendar button
            Pill(
                text = stringResource(R.string.events_add_calendar),
                onClick = {
                    val intent = Intent(Intent.ACTION_INSERT)
                        .setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, item.title)
                        .putExtra(CalendarContract.Events.EVENT_LOCATION, item.location.orEmpty())
                        .putExtra(CalendarContract.Events.DESCRIPTION, item.subtitle.orEmpty())
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.startsAtMillis)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, item.endsAtMillis)
                    context.startActivity(intent)
                },
                style = PillStyle.Outline,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp)
            )
        }
    }
}

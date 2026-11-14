package com.lync.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lync.app.data.calendar.CalendarRepository
import com.lync.app.data.model.CalendarDaySummary
import com.lync.app.data.model.CalendarFilter
import com.lync.app.data.model.CalendarItem
import com.lync.app.data.model.CalendarItemKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.time.DayOfWeek
import java.time.LocalDate

sealed interface CalendarStatusMessage {
    data class Imported(val count: Int) : CalendarStatusMessage
    data class Synced(val count: Int) : CalendarStatusMessage
    data object Cleared : CalendarStatusMessage
    data class Error(val text: String) : CalendarStatusMessage
    data object ErrorImport : CalendarStatusMessage
    data object ErrorUrl : CalendarStatusMessage
}

data class CalendarState(
    val selectedDate: LocalDate = LocalDate.now(),
    val weekDays: List<LocalDate> = emptyList(),
    val daySummaries: Map<LocalDate, CalendarDaySummary> = emptyMap(),
    val items: List<CalendarItem> = emptyList(),
    val filter: CalendarFilter = CalendarFilter.ALL,
    val isLoading: Boolean = false,
    val showImportSheet: Boolean = false,
    val selectedItemForDetail: CalendarItem? = null,
    val status: CalendarStatusMessage? = null,
) {
    val filteredItems: List<CalendarItem>
        get() = when (filter) {
            CalendarFilter.ALL -> items
            CalendarFilter.COURSES -> items.filter { it.kind == CalendarItemKind.COURSE }
            CalendarFilter.SOCIAL -> items.filter { it.kind == CalendarItemKind.SOCIAL_EVENT || it.kind == CalendarItemKind.SOCIAL_PLAN }
        }

    val coursesCount: Int get() = items.count { it.kind == CalendarItemKind.COURSE }
    val socialCount: Int get() = items.count { it.kind == CalendarItemKind.SOCIAL_EVENT || it.kind == CalendarItemKind.SOCIAL_PLAN }
}

class CalendarViewModel(
    private val repo: CalendarRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarState())
    val state = _state.asStateFlow()

    init {
        val today = LocalDate.now()
        val week = computeWeekDays(today)
        _state.update { it.copy(selectedDate = today, weekDays = week) }
        loadCurrentData()
    }

    private fun computeWeekDays(reference: LocalDate): List<LocalDate> {
        val monday = reference.with(DayOfWeek.MONDAY)
        return (0..6).map { monday.plusDays(it.toLong()) }
    }

    fun selectDate(date: LocalDate) {
        val currentWeek = _state.value.weekDays
        val newWeek = if (date in currentWeek) currentWeek else computeWeekDays(date)
        _state.update { it.copy(selectedDate = date, weekDays = newWeek) }
        loadCurrentData()
    }

    fun nextWeek() {
        val currentMonday = _state.value.weekDays.firstOrNull() ?: LocalDate.now()
        val nextMonday = currentMonday.plusWeeks(1)
        val newWeek = computeWeekDays(nextMonday)
        val newSelected = nextMonday
        _state.update { it.copy(selectedDate = newSelected, weekDays = newWeek) }
        loadCurrentData()
    }

    fun prevWeek() {
        val currentMonday = _state.value.weekDays.firstOrNull() ?: LocalDate.now()
        val prevMonday = currentMonday.minusWeeks(1)
        val newWeek = computeWeekDays(prevMonday)
        val newSelected = prevMonday
        _state.update { it.copy(selectedDate = newSelected, weekDays = newWeek) }
        loadCurrentData()
    }

    fun goToToday() {
        selectDate(LocalDate.now())
    }

    fun setFilter(filter: CalendarFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun showImportSheet(show: Boolean) {
        _state.update { it.copy(showImportSheet = show, status = null) }
    }

    fun selectItemForDetail(item: CalendarItem?) {
        _state.update { it.copy(selectedItemForDetail = item) }
    }

    fun dismissStatus() {
        _state.update { it.copy(status = null) }
    }

    fun importStream(stream: InputStream) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val result = repo.importFromStream(stream)
            _state.update {
                it.copy(
                    isLoading = false,
                    showImportSheet = false,
                    status = result.fold(
                        onSuccess = { count -> CalendarStatusMessage.Imported(count) },
                        onFailure = { err -> err.localizedMessage?.let { msg -> CalendarStatusMessage.Error(msg) } ?: CalendarStatusMessage.ErrorImport }
                    )
                )
            }
            loadCurrentData()
        }
    }

    fun importUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val result = repo.importFromUrl(url.trim())
            _state.update {
                it.copy(
                    isLoading = false,
                    showImportSheet = false,
                    status = result.fold(
                        onSuccess = { count -> CalendarStatusMessage.Imported(count) },
                        onFailure = { CalendarStatusMessage.ErrorUrl }
                    )
                )
            }
            loadCurrentData()
        }
    }

    fun syncConcordiaSchedule() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val result = repo.syncDefaultUniversitySchedule()
            _state.update {
                it.copy(
                    isLoading = false,
                    showImportSheet = false,
                    status = result.fold(
                        onSuccess = { count -> CalendarStatusMessage.Synced(count) },
                        onFailure = { CalendarStatusMessage.ErrorImport }
                    )
                )
            }
            loadCurrentData()
        }
    }

    fun clearImported() {
        viewModelScope.launch {
            repo.clearImportedEvents()
            _state.update { it.copy(status = CalendarStatusMessage.Cleared) }
            loadCurrentData()
        }
    }

    fun loadCurrentData() {
        viewModelScope.launch {
            val date = _state.value.selectedDate
            val week = _state.value.weekDays
            val items = repo.getItemsForDate(date)
            val summaries = repo.getDaySummaries(week)
            _state.update { it.copy(items = items, daySummaries = summaries) }
        }
    }
}

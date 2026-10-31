package io.droyagent.app.nativeui.presentation

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeProductPresentationHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NativeProductPresentationViewModel(application: Application) : AndroidViewModel(application) {
    private val host = NativeProductPresentationHost(application)
    private val mutable = MutableStateFlow(NativeProductPresentationState())
    val state = mutable.asStateFlow()
    private var observing = false
    private var reading: Job? = null
    private var generation = 0L
    private var logo: Bitmap? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (NativeProductPresentationHost.observes(key)) refresh()
    }

    fun startObserving() {
        if (observing) return
        observing = true
        host.startObserving(listener)
        refresh()
    }
    fun stopObserving() {
        if (observing) { observing = false; host.stopObserving(listener) }
        generation++; reading?.cancel(); reading = null
        mutable.update { it.copy(loading = false) }
    }
    fun refresh() {
        val current = ++generation
        reading?.cancel()
        reading = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            var unpublished: Bitmap? = null
            try {
                val snapshot = withContext(Dispatchers.IO) { host.snapshot() }
                val image = logo ?: withContext(Dispatchers.IO) { host.loadLogo().also { unpublished = it } }
                ensureActive()
                if (generation == current) {
                    logo = image; unpublished = null
                    mutable.value = NativeProductPresentationState(NativeProductPresentation.from(snapshot), image.asImageBitmap(), loading = false)
                }
            } catch (failure: NativeProductPresentationHost.Failure) {
                if (generation == current) mutable.update { it.copy(loading = false, error = failure.error) }
            } finally { unpublished?.recycle() }
        }
    }
    override fun onCleared() {
        stopObserving()
        // A composed frame may still reference a published bitmap; its normal GC lifetime is retained.
        logo = null
        mutable.update { it.copy(logo = null) }
        super.onCleared()
    }
}

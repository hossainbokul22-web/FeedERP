package com.feederp.ui.master

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** সব ViewModel এর সাধারণ ত্রুটি/বার্তা সামলানো: ব্যবসায়িক ত্রুটি (বাংলা বার্তা) UI তে দেখানো হয়। */
abstract class BaseViewModel : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }
    protected fun showMessage(text: String) { _message.value = text }

    /** [block] এর ত্রুটি ধরে [message] এ রাখে; সফল হলে [onDone] ডাকে। */
    protected fun launchSafe(onDone: (() -> Unit)? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                onDone?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "অজানা ত্রুটি"
            }
        }
    }
}

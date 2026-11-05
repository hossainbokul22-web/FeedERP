package com.feederp.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import com.feederp.ui.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.feederp.ui.theme.tr
import javax.inject.Inject

data class LoginUiState(
    val loading: Boolean = true,
    val setupMode: Boolean = false,
    val username: String = "",
    val displayName: String = "",
    val pin: String = "",
    val confirmPin: String = "",
    val error: String? = null,
    val busy: Boolean = false,
    val loggedIn: Boolean = false,
    val remember: Boolean = true,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    val languageSettings: com.feederp.ui.theme.LanguageSettings,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init { recheck() }

    /** সিঙ্কের পর হাব থেকে ইউজার এসে থাকলে "প্রথমবার সেটআপ" আর দেখানো হবে না। */
    fun recheck() {
        viewModelScope.launch {
            val setup = auth.needsSetup()
            if (!setup && auth.tryRestore()) {
                _state.update { it.copy(loading = false, setupMode = false, loggedIn = true) }
                return@launch
            }
            _state.update { it.copy(loading = false, setupMode = setup, username = it.username.ifBlank { auth.lastUsername() }) }
        }
    }

    fun onRemember(v: Boolean) = _state.update { it.copy(remember = v) }
    fun onUsername(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onDisplayName(v: String) = _state.update { it.copy(displayName = v, error = null) }
    fun onPin(v: String) = _state.update { it.copy(pin = v.filter { c -> c.isDigit() }.take(AuthRules.MAX_PIN), error = null) }
    fun onConfirmPin(v: String) = _state.update { it.copy(confirmPin = v.filter { c -> c.isDigit() }.take(AuthRules.MAX_PIN), error = null) }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            if (s.setupMode) {
                val problem = AuthRules.setupError(s.username, s.displayName, s.pin, s.confirmPin)
                if (problem != null) {
                    _state.update { it.copy(busy = false, error = problem) }
                    return@launch
                }
                val created = auth.createFirstOwner(s.username, s.displayName, s.pin)
                if (created.isFailure) {
                    _state.update { it.copy(busy = false, error = created.exceptionOrNull()?.message ?: "সেটআপ ব্যর্থ") }
                    return@launch
                }
            }
            when (val r = auth.login(s.username, s.pin, s.remember)) {
                LoginResult.Success -> _state.update { it.copy(busy = false, loggedIn = true, pin = "", confirmPin = "") }
                LoginResult.InvalidCredentials -> _state.update { it.copy(busy = false, pin = "", error = "ইউজারনেম বা PIN ভুল") }
                LoginResult.Inactive -> _state.update { it.copy(busy = false, pin = "", error = "এই ইউজার নিষ্ক্রিয়। মালিকের সাথে যোগাযোগ করুন") }
                is LoginResult.Locked -> _state.update { it.copy(busy = false, pin = "", error = "অনেকবার ভুল হয়েছে। ${r.secondsLeft} সেকেন্ড পরে চেষ্টা করুন") }
            }
        }
    }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onOpenSync: () -> Unit = {}, vm: LoginViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.recheck() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(state.loggedIn) { if (state.loggedIn) onLoggedIn() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.loading) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
            return@Surface
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.TextButton(
                onClick = { vm.languageSettings.toggle() },
                modifier = Modifier.align(Alignment.End),
            ) { Text(tr("English", "বাংলা")) }
            Text(tr("ফিড ERP", "Feed ERP"), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.setupMode) tr("প্রথমবার চালু: মালিকের অ্যাকাউন্ট তৈরি করুন", "First run: create the owner account") else tr("লগইন করুন", "Sign in"),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = state.username, onValueChange = vm::onUsername,
                label = { Text(tr("ইউজারনেম", "Username")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (state.setupMode) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.displayName, onValueChange = vm::onDisplayName,
                    label = { Text(tr("আপনার নাম", "Your name")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.pin, onValueChange = vm::onPin,
                label = { Text(tr("PIN (${AuthRules.MIN_PIN}-${AuthRules.MAX_PIN} অঙ্ক)", "PIN (${AuthRules.MIN_PIN}-${AuthRules.MAX_PIN} digits)")) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.setupMode) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.confirmPin, onValueChange = vm::onConfirmPin,
                    label = { Text(tr("PIN আবার লিখুন", "Confirm PIN")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Checkbox(checked = state.remember, onCheckedChange = vm::onRemember)
                Text(tr("এই ফোনে লগইন মনে রাখুন (৩০ দিন)", "Keep me signed in on this phone (30 days)"), style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = vm::submit, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.setupMode) tr("অ্যাকাউন্ট তৈরি ও প্রবেশ", "Create account & enter") else tr("প্রবেশ", "Enter"))
            }
            if (state.setupMode) {
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.OutlinedButton(onClick = onOpenSync, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("এটা নতুন ফোন? হাবের সাথে যুক্ত হয়ে ইউজার আনুন", "New phone? Connect to the hub to bring users"))
                }
                Text(
                    "দ্বিতীয় বা পরের ফোনে নিজে আলাদা মালিক তৈরি করবেন না; আগে হাবের সাথে সিঙ্ক করুন, তাহলে সব ইউজার চলে আসবে।",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

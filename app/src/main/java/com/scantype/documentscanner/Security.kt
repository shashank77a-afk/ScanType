package com.scantype.documentscanner

import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The PIN is never stored: only a salted PBKDF2 hash is kept on the device. */
object Pin {
    private fun factory(): SecretKeyFactory =
        try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") } catch (e: Exception) { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1") }

    fun newSalt(): String = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), 60000, 256)
        return Base64.encodeToString(factory().generateSecret(spec).encoded, Base64.NO_WRAP)
    }

    fun verify(prefs: Prefs, pin: String): Boolean = prefs.lockHash.isNotEmpty() && hash(pin, prefs.lockSalt) == prefs.lockHash
}

object Biometric {
    fun available(a: FragmentActivity): Boolean =
        BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(a: FragmentActivity, onOk: () -> Unit) {
        if (!available(a)) return
        val p = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock ScanType").setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build())
    }
}

@Composable
fun LockScreen(prefs: Prefs, activity: FragmentActivity, onUnlock: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (prefs.lockBio) Biometric.prompt(activity, onUnlock) }
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.scantype_logo), "ScanType", Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
        Spacer(Modifier.height(16.dp))
        Text("ScanType is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8); err = false }, singleLine = true, isError = err,
            label = { Text("PIN") }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        if (err) Text("Wrong PIN. Please try again.", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button({ if (Pin.verify(prefs, pin)) onUnlock() else { err = true; pin = "" } }) { Text("Unlock") }
        if (prefs.lockBio && Biometric.available(activity))
            OutlinedButton({ Biometric.prompt(activity, onUnlock) }) { Text("Use fingerprint") }
    }
}

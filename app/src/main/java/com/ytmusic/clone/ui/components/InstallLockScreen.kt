package com.ytmusic.clone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ytmusic.clone.data.local.InstallAccessGate
import com.ytmusic.clone.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InstallLockScreen(
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var lockoutSeconds by remember {
        mutableLongStateOf(InstallAccessGate.getRemainingLockoutSeconds(context))
    }

    // Live countdown when brute-force lockout is active
    LaunchedEffect(lockoutSeconds) {
        while (lockoutSeconds > 0L) {
            delay(1000L)
            lockoutSeconds = (lockoutSeconds - 1L).coerceAtLeast(0L)
            if (lockoutSeconds == 0L) {
                errorText = null
            }
        }
    }

    fun submit() {
        if (isVerifying || lockoutSeconds > 0L) return
        if (password.isBlank()) {
            errorText = "Please enter the access password"
            return
        }
        isVerifying = true
        errorText = null
        val input = password
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                InstallAccessGate.attemptUnlock(context, input)
            }
            isVerifying = false
            when (result) {
                is InstallAccessGate.UnlockAttemptResult.Success -> {
                    password = ""
                    onUnlocked()
                }
                is InstallAccessGate.UnlockAttemptResult.WrongPassword -> {
                    password = ""
                    errorText = if (result.attemptsRemainingBeforeLockout > 0) {
                        "Incorrect password (${result.attemptsRemainingBeforeLockout} attempts left)"
                    } else {
                        "Incorrect password"
                    }
                }
                is InstallAccessGate.UnlockAttemptResult.LockedOut -> {
                    password = ""
                    lockoutSeconds = result.remainingSeconds
                    errorText = "Too many attempts. Try again in ${result.remainingSeconds}s"
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(YtBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = YtSurface,
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(YtPrimaryRed.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = YtPrimaryRed,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Private Access",
                    color = YtTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter the installation password to unlock YT Music on this device.",
                    color = YtTextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(22.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        if (errorText != null && lockoutSeconds == 0L) errorText = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !isVerifying && lockoutSeconds == 0L,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = YtTextSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = YtPrimaryRed,
                        unfocusedBorderColor = YtTextSecondary.copy(alpha = 0.4f),
                        focusedTextColor = YtTextPrimary,
                        unfocusedTextColor = YtTextPrimary,
                        focusedLabelColor = YtPrimaryRed,
                        unfocusedLabelColor = YtTextSecondary,
                        cursorColor = YtPrimaryRed
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (lockoutSeconds > 0L) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Locked out. Try again in ${lockoutSeconds}s",
                        color = YtPrimaryRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else if (errorText != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = errorText!!,
                        color = YtPrimaryRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                Button(
                    onClick = { submit() },
                    enabled = !isVerifying && lockoutSeconds == 0L,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = YtPrimaryRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text = "Unlock App",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

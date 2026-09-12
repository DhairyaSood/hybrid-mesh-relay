package com.hybridmesh.relay.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hybridmesh.relay.ui.viewmodel.ProfileViewModel

@Composable
fun ProfileScreen(
    onBack: () -> Unit
) {
    val viewModel: ProfileViewModel = viewModel()

    val identity by viewModel.identity.collectAsState()

    var deviceName by rememberSaveable(
        identity.deviceName
    ) {
        mutableStateOf(
            identity.deviceName
        )
    }

    val context = LocalContext.current
    val clipboardManager =
        LocalClipboardManager.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 20.dp,
                    vertical = 16.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(18.dp)
        ) {

            Text(
                text = "DEVICE PROFILE",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Identity used by this device on the mesh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            IdentityHeader(
                deviceName = identity.deviceName,
                nodeId = identity.nodeId
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(
                        RoundedCornerShape(16.dp)
                    )
                    .background(
                        MaterialTheme.colorScheme.surface
                    )
                    .border(
                        width = 1.dp,
                        color =
                            MaterialTheme.colorScheme.outlineVariant,
                        shape =
                            RoundedCornerShape(16.dp)
                    )
                    .padding(18.dp),
                verticalArrangement =
                    Arrangement.spacedBy(18.dp)
            ) {

                Text(
                    text = "LOCAL IDENTITY",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = deviceName,
                    onValueChange = {
                        deviceName = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text("Device name")
                    },
                    supportingText = {
                        Text(
                            "This is how your device can appear to nearby peers."
                        )
                    }
                )

                Button(
                    onClick = {
                        viewModel.updateDeviceName(
                            deviceName
                        )

                        Toast.makeText(
                            context,
                            "Device name updated",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor =
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("SAVE CHANGES")
                }

                HorizontalDivider()

                ProfileInfoRow(
                    label = "Node ID",
                    value = identity.nodeId,
                    actionText = "COPY",
                    onAction = {

                        clipboardManager.setText(
                            AnnotatedString(
                                identity.nodeId
                            )
                        )

                        Toast.makeText(
                            context,
                            "Node ID copied",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )

                ProfileInfoRow(
                    label = "Device type",
                    value = identity.deviceType.name
                )

                ProfileInfoRow(
                    label = "Connection",
                    value = "LOCAL DEVICE"
                )

                ProfileInfoRow(
                    label = "App version",
                    value = viewModel.appVersion
                )
            }

            Spacer(
                modifier = Modifier.weight(1f)
            )

            Text(
                text =
                    "Your Node ID is generated locally and stored on this device.",
                style = MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("DONE")
            }
        }
    }
}

@Composable
private fun IdentityHeader(
    deviceName: String,
    nodeId: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(20.dp)
            )
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .padding(20.dp),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .width(56.dp)
                .height(56.dp)
                .clip(
                    RoundedCornerShape(16.dp)
                )
                .background(
                    MaterialTheme.colorScheme.primary
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "HM",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color =
                    MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(
            modifier = Modifier.width(16.dp)
        )

        Column(
            modifier = Modifier.weight(1f)
        ) {

            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Text(
                text = nodeId,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProfileInfoRow(
    label: String,
    value: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Column(
            modifier = Modifier.weight(1f)
        ) {

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(
                modifier = Modifier.height(3.dp)
            )

            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }

        if (
            actionText != null &&
            onAction != null
        ) {
            Text(
                text = actionText,
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(
                        onClick = onAction
                    )
                    .padding(
                        horizontal = 10.dp,
                        vertical = 8.dp
                    ),
                style =
                    MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color =
                    MaterialTheme.colorScheme.primary
            )
        }
    }
}
package com.hybridmesh.relay.ui.components

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun NodeIdText(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    BasicText(
        text = value,
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, "Node ID copied", Toast.LENGTH_SHORT).show()
            }
        ),
        style = style.copy(color = color),
        maxLines = maxLines
    )
}

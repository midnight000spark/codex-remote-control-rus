package com.haochen.codexremote

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
internal fun MainActivity.ConversationImageViewerDialog(
    state: ConversationImageViewerState,
    onDismiss: () -> Unit,
    onLongPress: (String, Int, Int) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    buildConversationImageViewerWebView(
                        context = context,
                        source = state.source,
                        onLongPress = onLongPress,
                    )
                },
                update = { view ->
                    if (view.tag != state.source) {
                        loadConversationImageViewer(view, state.source)
                    }
                },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                color = Color(0x99000000),
                shape = androidx.compose.foundation.shape.CircleShape,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = "Закрыть просмотр изображения",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MainActivity.ConversationLongPressDialog(
    state: ConversationLongPressState,
    onDismiss: () -> Unit,
    onCopyImage: () -> Unit,
    onSaveImage: () -> Unit,
    onOpenLink: () -> Unit,
    onCopyLink: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val menuWidth = 148.dp
    val menuHeight = 89.dp
    val edgeMargin = 8.dp
    val requestedX = with(density) { state.anchorX.toDp() } + 6.dp
    val requestedY = with(density) { state.anchorY.toDp() } + 6.dp
    val maxX = (configuration.screenWidthDp.dp - menuWidth - edgeMargin).coerceAtLeast(edgeMargin)
    val maxY = configuration.screenHeightDp.dp - edgeMargin
    val menuX = requestedX.coerceIn(edgeMargin, maxX)
    val menuY =
        if (requestedY + menuHeight <= maxY) {
            requestedY
        } else {
            (requestedY - menuHeight - 12.dp).coerceAtLeast(edgeMargin)
        }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(0f) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Surface(
                modifier = Modifier
                    .offset(x = menuX, y = menuY)
                    .width(menuWidth),
                color = uiSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (state.kind == ConversationLongPressKind.Image) {
                        ConversationLongPressMenuItem("Копировать изображение", onCopyImage)
                        HorizontalDivider(color = uiBorder)
                        ConversationLongPressMenuItem("Сохранить изображение", onSaveImage)
                    } else {
                        ConversationLongPressMenuItem("Открыть ссылку", onOpenLink)
                        HorizontalDivider(color = uiBorder)
                        ConversationLongPressMenuItem("Копировать ссылку", onCopyLink)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainActivity.ConversationLongPressMenuItem(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, color = uiText, fontSize = 14.sp)
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun buildConversationImageViewerWebView(
    context: android.content.Context,
    source: String,
    onLongPress: (String, Int, Int) -> Unit,
): WebView =
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(AndroidColor.BLACK)
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        settings.javaScriptEnabled = false
        settings.loadsImagesAutomatically = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = WebViewClient()
        var longPressRawX = 0
        var longPressRawY = 0
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                longPressRawX = event.rawX.toInt()
                longPressRawY = event.rawY.toInt()
            }
            false
        }
        setOnLongClickListener {
            onLongPress(source, longPressRawX, longPressRawY)
            true
        }
        loadConversationImageViewer(this, source)
    }

private fun loadConversationImageViewer(webView: WebView, source: String) {
    webView.tag = source
    val html =
        """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=0.25, maximum-scale=8, user-scalable=yes" />
          <style>
            html, body { width: 100%; height: 100%; margin: 0; background: #000; }
            body { display: flex; align-items: center; justify-content: center; overflow: auto; }
            img { display: block; max-width: 100vw; max-height: 100vh; width: auto; height: auto; object-fit: contain; }
          </style>
        </head>
        <body><img src="${escapeConversationHtmlAttribute(source)}" /></body>
        </html>
        """.trimIndent()
    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

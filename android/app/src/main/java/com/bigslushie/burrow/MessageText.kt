package com.bigslushie.burrow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle

private const val thinkOpen = "<think>"
private const val thinkClose = "</think>"

private fun markerLength(text: String, index: Int): Int = when {
    text.startsWith("***", index) -> 3
    text.startsWith("**", index) -> 2
    text[index] == '*' -> 1
    else -> 0
}

fun styledMessage(text: String, actionColor: Color, markerColor: Color? = null): AnnotatedString = buildAnnotatedString {
    var bold = false
    var italic = false
    var index = 0
    while (index < text.length) {
        val marker = markerLength(text, index)
        if (marker > 0) {
            if (markerColor != null) withStyle(SpanStyle(color = markerColor)) { append(text, index, index + marker) }
            if (marker != 2) italic = !italic
            if (marker >= 2) bold = !bold
            index += marker
            continue
        }
        val nextMarker = text.indexOf('*', index).takeIf { it >= 0 } ?: text.length
        val style = SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            color = if (italic) actionColor else Color.Unspecified,
        )
        withStyle(style) { append(text, index, nextMarker) }
        index = nextMarker
    }
}

data class ReplyParts(val thinking: String, val answer: String)

fun splitReply(raw: String): ReplyParts {
    val thinking = StringBuilder()
    val answer = StringBuilder()
    var rest = raw
    while (true) {
        val open = rest.indexOf(thinkOpen)
        if (open < 0) {
            answer.append(rest)
            break
        }
        answer.append(rest, 0, open)
        val close = rest.indexOf(thinkClose, open)
        if (close < 0) {
            thinking.append(rest, open + thinkOpen.length, rest.length)
            break
        }
        thinking.append(rest, open + thinkOpen.length, close)
        rest = rest.substring(close + thinkClose.length)
    }
    return ReplyParts(thinking.toString(), answer.toString().trimStart())
}

class LiveFormatting(private val actionColor: Color, private val markerColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styledMessage(text.text, actionColor, markerColor), OffsetMapping.Identity)
}

fun copyPrivately(context: Context, text: String) {
    val clip = ClipData.newPlainText("message", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private const val sentenceEnds = ".!?…"
private const val sentenceClosers = "\"'”’*)"

fun toLastSentence(text: String): String {
    val lastEnd = text.indexOfLast { it in sentenceEnds }
    if (lastEnd < 0) return text
    var end = lastEnd + 1
    while (end < text.length && text[end] in sentenceClosers) end++
    val trimmed = text.substring(0, end)
    return if (trimmed.count { it == '*' } % 2 == 1) "$trimmed*" else trimmed
}

package dev.southstar21.volum.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.textStyle

@Composable
fun BareField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    size: TextUnit = T.Subhead,
    weight: FontWeight = Semibold,
    align: TextAlign = TextAlign.Start,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    password: Boolean = false,
    /** The IME action (Done, Go, Search or Send). Null keeps the system's default for [imeAction]: Done closes the keyboard. */
    onDone: (() -> Unit)? = null,
    singleLine: Boolean = true,
    /** What the field is for, read by screen readers (its visible label sits beside it, not inside). */
    label: String? = null,
    /** An autofill hint: email, password, a new password. */
    contentType: ContentType? = null,
) {
    val c = Volum.colors
    val style = textStyle(size, weight, c.ink, align = align)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics {
            label?.let { contentDescription = it }
            contentType?.let { this.contentType = it }
        },
        textStyle = style,
        singleLine = singleLine,
        cursorBrush = SolidColor(c.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = if (onDone == null) KeyboardActions.Default else KeyboardActions(onDone = { onDone() }, onGo = { onDone() }, onSearch = { onDone() }, onSend = { onDone() }),
        decorationBox = { inner ->
            Box(contentAlignment = if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart) {
                // The full mute colour: any lighter falls under 4.5:1 on the field and milky surfaces.
                if (value.isEmpty()) BasicText(placeholder, style = style.copy(color = c.mute))
                inner()
            }
        },
    )
}

/** A labelled field row inside a grouped milky/glass box. */
@Composable
fun LabeledField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false, imeAction: ImeAction = ImeAction.Next, contentType: ContentType? = null, onDone: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp)) {
        // The visible label is read as the field's own description instead.
        Mute(label, Modifier.clearAndSetSemantics {}, size = T.Caption, weight = Semibold)
        Spacer(Modifier.height(2.dp))
        BareField(value, onValueChange, Modifier.fillMaxWidth(), keyboardType = keyboardType, password = password, imeAction = imeAction, onDone = onDone, label = label, contentType = contentType)
    }
}

/** A neumorphic pressed-in field with an optional leading icon and trailing slot. */
@Composable
fun NeuField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    size: TextUnit = T.Subhead,
    align: TextAlign = TextAlign.Start,
    height: Dp = 62.dp,
    label: String? = null,
    onDone: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(height).neuIn(RoundedCornerShape(22.dp)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { Icon(it, Volum.colors.mute, size = 20.dp) }
        BareField(value, onValueChange, Modifier.weight(1f), placeholder, size, keyboardType = keyboardType, password = password, imeAction = imeAction, onDone = onDone, align = align, label = label ?: placeholder.ifEmpty { null })
        trailing?.invoke()
    }
}

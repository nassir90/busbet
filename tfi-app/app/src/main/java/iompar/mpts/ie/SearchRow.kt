package iompar.mpts.ie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * A search result line shared by the home-screen stop/route search and the notification sheet's
 * stop search, so both read identically: a one-line label with the stop's route chips trailing it.
 *
 * Material3's ListItem enforces a 56dp minimum height and a bodyLarge headline, which is far more
 * room than a one-line stop name needs in a compact dropdown.
 */
@Composable
fun CompactSearchRow(
    label: String,
    onClick: () -> Unit,
    services: List<String>? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Text(
            withArrows(label),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            inlineContent = ARROW_CONTENT,
            modifier = Modifier.weight(1f),
        )
        if (!services.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            val shown = services.take(5)
            // The "⋯" overflow marker sits on the inside, before the chips (opposite the label run).
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (services.size > shown.size) EllipsisMark()
                shown.forEach { ServiceChip(it) }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

private const val ARROW_ID = "arrow"

/**
 * [label] with each " → " drawn as a Material arrow icon. The font's "→" glyph sits small and
 * low next to the text; the icon is sized and centred to the line. Labels stay plain strings with
 * "→" in them (they are stored in search history), so only the drawing changes.
 */
private fun withArrows(label: String): AnnotatedString = buildAnnotatedString {
    label.split(" → ").forEachIndexed { i, part ->
        if (i > 0) {
            append(" ")
            appendInlineContent(ARROW_ID, "→")
            append(" ")
        }
        append(part)
    }
}

private val ARROW_CONTENT = mapOf(
    ARROW_ID to InlineTextContent(
        Placeholder(width = 1.em, height = 1.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(),
        )
    },
)

@Composable
private fun EllipsisMark() {
    Text(
        "⋯",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ServiceChip(text: String) {
    // Luas lines wear their own colour; every other route uses the theme's.
    val line = lineColors(text)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = line?.let { Color(it.content) } ?: MaterialTheme.colorScheme.onPrimary,
        maxLines = 1,
        modifier = Modifier
            .background(line?.let { Color(it.container) } ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

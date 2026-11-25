package org.adaptivedpi.android.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class StatusLevel { Working, Fixing, Unavailable, Error }

/** Значок-капсула с подписью состояния в одном из смысловых цветов. */
@Composable
fun StatusBadge(label: String, level: StatusLevel, modifier: Modifier = Modifier) {
    val status = AdaptiveDpiTheme.statusColors
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (level) {
        StatusLevel.Working -> status.workingContainer to status.onWorkingContainer
        StatusLevel.Fixing -> status.fixingContainer to status.onFixingContainer
        StatusLevel.Unavailable -> status.unavailableContainer to status.onUnavailableContainer
        StatusLevel.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

/**
 * Большая круглая кнопка запуска. Круг и подпись образуют одну нажимаемую область.
 * Включённое состояние выделено цветом «работает».
 */
@Composable
fun PowerButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = AdaptiveDpiTheme.statusColors
    val scheme = MaterialTheme.colorScheme
    val container: Color
    val content: Color
    when {
        active -> {
            container = status.working
            content = status.onWorking
        }
        else -> {
            container = scheme.primary
            content = scheme.onPrimary
        }
    }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(
            modifier = Modifier.size(POWER_BUTTON_SIZE).clip(CircleShape).background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(AdaptiveIcons.Power),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(POWER_ICON_SIZE),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Строка списка: значок, заголовок, подзаголовок; с переходом, если задан [onClick]. */
@Composable
fun ListRowItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(clickable)
            .defaultMinSize(minHeight = ROW_MIN_HEIGHT)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClick != null) {
            Icon(
                painter = painterResource(AdaptiveIcons.ChevronRight),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val POWER_BUTTON_SIZE = 144.dp
private val POWER_ICON_SIZE = 64.dp
private val ROW_MIN_HEIGHT = 48.dp
private const val DISABLED_ALPHA = 0.38f

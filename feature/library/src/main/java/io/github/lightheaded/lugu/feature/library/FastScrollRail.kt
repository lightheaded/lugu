package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lightheaded.lugu.core.model.ItemSort
import kotlin.math.roundToInt

/** Everything that does not file under a letter shares one bucket, as a phone book does. */
internal const val OTHER_INITIAL = '#'

/**
 * The letter a row files under.
 *
 * Digits, quotation marks and the rest collapse into one bucket rather than each getting a
 * rail entry of their own: a rail with twelve punctuation marks on it is longer than the
 * alphabet it exists to shorten.
 */
internal fun initialOf(key: String): Char {
    val first = key.firstOrNull { !it.isWhitespace() } ?: return OTHER_INITIAL
    return if (first.isLetter()) first.uppercaseChar() else OTHER_INITIAL
}

/**
 * The rail, taken from the list in the order the list is already in.
 *
 * Derived from the sorted rows rather than from a fixed A–Z, so the rail never offers a
 * letter nothing files under, and never has to be told which way round the ordering runs.
 * Sorting by author puts unattributed items last; taking the letters in list order puts
 * their bucket last too, with no second ordering rule to keep in step with the first.
 */
internal fun fastScrollLetters(keys: List<String>): List<Char> =
    keys.map(::initialOf).distinct()

/** Where the rail jumps to: the first row filing under the letter, or nowhere. */
internal fun firstIndexOfLetter(keys: List<String>, letter: Char): Int =
    keys.indexOfFirst { initialOf(it) == letter }

/**
 * Whether an A–Z rail is worth the width it costs, and honest about what it does.
 *
 * Two conditions, and both matter. The list has to be long enough that scrolling it is a
 * chore — under [MIN_ITEMS] a flick reaches the end anyway, and a rail is then only
 * clutter over the covers. And the ordering has to be alphabetical: on "recently added" or
 * "length" the rows are not in letter order at all, so a rail of letters would promise a
 * structure the list does not have and jump somewhere arbitrary. It is a lie, not a
 * shortcut, and it is hidden rather than made unreliable.
 *
 * [orderedAlphabetically] is asked as a plain question rather than taken as an [ItemSort],
 * because the author, series and narrator lists want the same rail and have no sort at all:
 * a name is the only thing they could be ordered by, so the answer there is a constant. A
 * signature naming the grid's enum would have forced those lists into a second rail, which
 * is how two lists a fortnight apart end up scrolling differently.
 */
internal fun fastScrollEarnsItsPlace(
    itemCount: Int,
    letterCount: Int,
    orderedAlphabetically: Boolean,
): Boolean = itemCount >= MIN_ITEMS && letterCount >= MIN_LETTERS && orderedAlphabetically

/** The grid's orderings that put rows in letter order, which is the rail's precondition. */
internal val ItemSort.isAlphabetical: Boolean
    get() = this == ItemSort.TITLE || this == ItemSort.AUTHOR

private const val MIN_ITEMS = 40
private const val MIN_LETTERS = 3

/**
 * A dragable index down the edge of a long list (upstream app#544).
 *
 * Takes the letters and reports the one being pointed at, and knows nothing about grids,
 * scroll offsets or item indices. That keeps the arithmetic that maps a letter back to a
 * row where the rows are — the alternative, handing this a `LazyGridState`, would make a
 * general control that only ever works for one list.
 *
 * The touch target is the whole width of the rail rather than each letter in turn. Twenty-
 * six letters cannot each have a 48dp slot on a phone, so the letters are a scale to read
 * and the rail is the thing you hit — which is also how a finger uses it: one long drag,
 * not twenty-six aimed taps.
 */
@Composable
fun FastScrollRail(
    letters: List<Char>,
    currentLetter: Char?,
    onLetterSelected: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return

    var railHeightPx by remember { mutableIntStateOf(0) }
    var touchYPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // The gesture detectors outlive a recomposition, so the callback and the letter list
    // are read through the latest values rather than the ones captured when they started.
    val latestLetters by rememberUpdatedState(letters)
    val latestSelected by rememberUpdatedState(onLetterSelected)
    val select: (Float) -> Unit = { y ->
        val height = railHeightPx
        if (height > 0) {
            touchYPx = y.coerceIn(0f, height.toFloat())
            val slot = (touchYPx / height * latestLetters.size).toInt()
            latestSelected(latestLetters[slot.coerceIn(0, latestLetters.lastIndex)])
        }
    }

    Box(modifier = modifier.fillMaxHeight()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(FAST_SCROLL_RAIL_WIDTH)
                // Merged, so the rail is announced as one control. Read letter by letter it
                // is twenty-six nodes saying nothing, in front of the list they index.
                .semantics(mergeDescendants = true) {
                    contentDescription = "Alphabet index. Drag to jump to a letter."
                }
                .onSizeChanged { railHeightPx = it.height }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { start ->
                            dragging = true
                            select(start.y)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            select(change.position.y)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { position -> select(position.y) }
                },
        ) {
            letters.forEach { letter ->
                Text(
                    letter.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (letter == currentLetter) FontWeight.Bold else FontWeight.Normal,
                    color = if (letter == currentLetter) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    // Weighted rather than fixed so the rail always spans exactly the list
                    // it indexes: a rail shorter than the list points at the wrong rows,
                    // and one longer than it runs off the bottom of the screen.
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Under the finger, not on the rail: on a phone the letter being pointed at is the
        // one the hand is covering, which is the whole reason a contacts list shows it in a
        // bubble beside the thumb.
        if (dragging && currentLetter != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(x = 0, y = (touchYPx - BUBBLE_SIZE.toPx() / 2f).roundToInt()) }
                    .padding(end = FAST_SCROLL_RAIL_WIDTH)
                    .size(BUBBLE_SIZE),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(currentLetter.toString(), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/**
 * Wide enough to be hit rather than aimed at, which is more than the letters need.
 *
 * The list it indexes reserves the same width at its end, so the rail sits beside the
 * covers rather than on top of them.
 */
internal val FAST_SCROLL_RAIL_WIDTH = 48.dp

private val BUBBLE_SIZE = 56.dp

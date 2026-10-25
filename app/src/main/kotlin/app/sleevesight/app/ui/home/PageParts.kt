package app.sleevesight.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R

/**
 * On Home, under *Separate pages* (#447), the way to an item's page, in place of its fold: [name]
 * and `›`, drawn in [style] (its colour too) as the fold's heading is, the whole line a button with a
 * 48 dp target. A screen reader reads it as [name], a heading with [heading], and names its action
 * "open page".
 */
@Composable
internal fun PageLink(
    name: String,
    tag: String,
    style: TextStyle,
    heading: Boolean = false,
    onClick: () -> Unit,
) {
    val symbol = stringResource(R.string.home_page_symbol)
    val action = stringResource(R.string.home_page_open)
    Text(
        "$name $symbol",
        style = style,
        modifier =
            Modifier
                .testTag(tag)
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
                // The name in place of the drawn line, whose `›` is not read.
                .clearAndSetSemantics {
                    if (heading) heading()
                    contentDescription = name
                }.wrapContentHeight(Alignment.CenterVertically)
                .padding(end = 4.dp),
    )
}

/**
 * A page of Home's (#447): a bar with the back arrow, which goes back to where the page was opened
 * from ([onBack]), as Android's back does, and [title], which a screen reader reads as a heading;
 * under it, [content], scrolling. In the colours it is drawn in (the album art's), the bar and the
 * page in the colour of Home's box, so the item reads as the box opened to the whole screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageFrame(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.surfaceContainer,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(PAGE_TITLE_TAG).semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(PAGE_BACK_TAG)) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.page_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surfaceContainer),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** The test tags of a page's title and its back arrow, and of the ways to each page from Home. */
const val PAGE_TITLE_TAG: String = "page:title"
const val PAGE_BACK_TAG: String = "page:back"
const val SOURCES_PAGE_LINK_TAG: String = "page-link:sources"
const val GENRES_PAGE_LINK_TAG: String = "page-link:genres"

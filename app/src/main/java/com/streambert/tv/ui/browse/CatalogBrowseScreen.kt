package com.streambert.tv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streambert.tv.data.model.CatalogItem
import com.streambert.tv.data.model.CatalogRow
import com.streambert.tv.ui.components.LoadingIndicator
import com.streambert.tv.ui.components.StandardRow
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Shared UI state for a "browse a catalog" screen (service or genre). */
data class BrowseUiState(
    val loading: Boolean = true,
    val title: String = "",
    val hero: CatalogItem? = null,
    val rows: List<CatalogRow> = emptyList(),
    /**
     * Optional explicit hero image (a full URL) that overrides the [hero]
     * item's artwork — used by the person screen to show the cast member's own
     * portrait photo as the backdrop. Null → fall back to [hero]'s backdrop.
     */
    val heroImageUrl: String? = null,
    val error: String? = null
)

/** Reusable screen that renders a titled hero + rows for Service/Genre browsing. */
@Composable
fun CatalogBrowseScreen(
    state: BrowseUiState,
    onSelect: (CatalogItem) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }

            state.error != null && state.rows.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(48.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    state.error!!,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleMedium
                )
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRetry) { Text("Retry") }
                    Button(onClick = onBack) { Text("Back") }
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp)
            ) {
                item { Header(state = state, onBack = onBack) }
                items(state.rows, key = { it.title }) { row ->
                    StandardRow(title = row.title, items = row.items, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun Header(state: BrowseUiState, onBack: () -> Unit) {
    // A person screen supplies heroImageUrl (a portrait 2:3 headshot); catalog
    // screens fall back to the featured item's 16:9 backdrop.
    val portrait = state.heroImageUrl != null
    val heroModel = state.heroImageUrl ?: state.hero?.backdropUrl ?: state.hero?.posterUrl
    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
    ) {
        AsyncImage(
            model = heroModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Person photos are portrait — anchor to the top so the face isn't
            // cropped out of the wide hero; movie backdrops stay centered.
            alignment = if (portrait) Alignment.TopCenter else Alignment.Center,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // Lighter top for a person headshot (keep the face visible);
                    // heavier top for movie backdrops. Both fade dark at the
                    // bottom for the name.
                    if (portrait) Brush.verticalGradient(
                        0f to Color(0x330B0B0F),
                        0.55f to Color(0x4D0B0B0F),
                        1f to Color(0xF20B0B0F)
                    ) else Brush.verticalGradient(
                        0f to Color(0xB30B0B0F),
                        0.5f to Color(0x660B0B0F),
                        1f to Color(0xF20B0B0F)
                    )
                )
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp)
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(28.dp))
        }
        Text(
            state.title,
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, end = 48.dp, bottom = 20.dp)
        )
    }
}

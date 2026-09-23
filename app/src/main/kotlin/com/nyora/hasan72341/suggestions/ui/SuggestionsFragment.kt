package com.nyora.hasan72341.suggestions.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import coil3.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.core.nav.router
import com.nyora.hasan72341.core.ui.BaseFragment
import com.nyora.hasan72341.core.util.ext.consumeAllContentInsets
import com.nyora.hasan72341.core.util.ext.contentInsets
import com.nyora.hasan72341.databinding.FragmentComposeBinding
import com.nyora.hasan72341.suggestions.domain.MangaSuggestionV2
import com.nyora.hasan72341.mihon.parsers.model.Manga
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SuggestionsFragment : BaseFragment<FragmentComposeBinding>() {

    private val viewModel by viewModels<SuggestionsViewModel>()

    override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentComposeBinding {
        return FragmentComposeBinding.inflate(inflater, container, false)
    }

    override fun onViewBindingCreated(binding: FragmentComposeBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colorScheme = if (isSystemInDarkTheme()) {
                        darkColorScheme()
                    } else {
                        lightColorScheme()
                    }
                ) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        SuggestionsScreen(viewModel)
                    }
                }
            }
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val barsInsets = insets.contentInsets
        viewBinding?.composeView?.updatePadding(
            left = barsInsets.left,
            top = barsInsets.top,
            right = barsInsets.right,
            bottom = barsInsets.bottom,
        )
        return insets.consumeAllContentInsets()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SuggestionsScreen(viewModel: SuggestionsViewModel) {
        val suggestions by viewModel.suggestions.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        var selectedSuggestion by remember { mutableStateOf<MangaSuggestionV2?>(null) }
        val sheetState = rememberModalBottomSheetState()
        val scope = rememberCoroutineScope()

        if (selectedSuggestion != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedSuggestion = null },
                sheetState = sheetState
            ) {
                SourcePickerContent(
                    suggestion = selectedSuggestion!!,
                    onMangaClick = { manga ->
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                selectedSuggestion = null
                                router.openDetails(manga)
                            }
                        }
                    }
                )
            }
        }
        
        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading && suggestions.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (!isLoading && suggestions.isEmpty()) {
                Text(
                    text = "No suggestions found. Check your internet connection.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    item {
                        Text(
                            text = "Trending",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(suggestions.take(15)) { suggestion ->
                                TrendingCard(suggestion) {
                                    handleSuggestionClick(suggestion) { selectedSuggestion = it }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = "Popular",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    items(suggestions.drop(15)) { suggestion ->
                        PopularItem(suggestion) {
                            handleSuggestionClick(suggestion) { selectedSuggestion = it }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun TrendingCard(suggestion: MangaSuggestionV2, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .width(150.dp)
                .clickable { onClick() },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column {
                AsyncImage(
                    model = suggestion.anilistMedia.coverImage.preferred(),
                    contentDescription = null,
                    modifier = Modifier
                        .height(210.dp)
                        .fillMaxWidth(),
                    contentScale = ContentScale.Crop
                )
                Text(
                    text = suggestion.anilistMedia.title.preferred(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp),
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 18.sp
                )
            }
        }
    }

    @Composable
    fun PopularItem(suggestion: MangaSuggestionV2, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onClick() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = suggestion.anilistMedia.coverImage.preferred(),
                contentDescription = null,
                modifier = Modifier
                    .size(70.dp, 100.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = suggestion.anilistMedia.title.preferred(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = suggestion.anilistMedia.genres.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (suggestion.matches.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = "Available on ${suggestion.matches.size} sources",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (suggestion.isLoadingMatches) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .width(100.dp)
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    @Composable
    fun SourcePickerContent(suggestion: MangaSuggestionV2, onMangaClick: (Manga) -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Select Source",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )
            suggestion.matches.forEach { manga ->
                ListItem(
                    headlineContent = { Text(manga.title) },
                    supportingContent = { Text(manga.source.name) },
                    modifier = Modifier.clickable { onMangaClick(manga) }
                )
            }
        }
    }
    
    private fun handleSuggestionClick(suggestion: MangaSuggestionV2, showPicker: (MangaSuggestionV2) -> Unit) {
        when {
            suggestion.matches.size == 1 -> {
                router.openDetails(suggestion.matches.first())
            }
            suggestion.matches.size > 1 -> {
                showPicker(suggestion)
            }
            else -> {
                router.openSearch(suggestion.anilistMedia.title.preferred())
            }
        }
    }
}

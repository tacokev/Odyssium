package com.skintclub.odyssey.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skintclub.innertube.YouTube
import com.skintclub.innertube.models.YTItem
import com.skintclub.innertube.models.filterExplicit
import com.skintclub.odyssey.constants.HideExplicitKey
import com.skintclub.odyssey.db.MusicDatabase
import com.skintclub.odyssey.db.entities.SearchHistory
import com.skintclub.odyssey.utils.dataStore
import com.skintclub.odyssey.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlineSearchSuggestionViewModel
    @Inject
    constructor(
        @ApplicationContext val context: Context,
        database: MusicDatabase,
    ) : ViewModel() {
        val query = MutableStateFlow("")
        private val _viewState = MutableStateFlow(SearchSuggestionViewState())
        val viewState = _viewState.asStateFlow()

        init {
            viewModelScope.launch {
                query
                    .flatMapLatest { query ->
                        if (query.isEmpty()) {
                            database.searchHistory().map { history ->
                                SearchSuggestionViewState(
                                    history = history,
                                )
                            }
                        } else {
                            val result = YouTube.searchSuggestions(query).getOrNull()
                            database
                                .searchHistory(query)
                                .map { it.take(3) }
                                .map { history ->
                                    SearchSuggestionViewState(
                                        history = history,
                                        suggestions =
                                            result
                                                ?.queries
                                                ?.filter { query ->
                                                    history.none { it.query == query }
                                                }.orEmpty(),
                                        items =
                                            result
                                                ?.recommendedItems
                                                ?.filterExplicit(
                                                    context.dataStore.get(
                                                        HideExplicitKey,
                                                        false,
                                                    ),
                                                ).orEmpty(),
                                    )
                                }
                        }
                    }.collect {
                        _viewState.value = it
                    }
            }
        }
    }

data class SearchSuggestionViewState(
    val history: List<SearchHistory> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val items: List<YTItem> = emptyList(),
)

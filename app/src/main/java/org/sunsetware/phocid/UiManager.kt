@file:OptIn(ExperimentalCoroutinesApi::class)

package org.sunsetware.phocid

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.sunsetware.phocid.data.LibraryIndex
import org.sunsetware.phocid.data.PersistentUiState
import org.sunsetware.phocid.data.PlayerTimerSettings
import org.sunsetware.phocid.data.PlaylistManager
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.ui.views.CollectionViewInfo
import org.sunsetware.phocid.ui.views.LibraryScreenCollectionViewState
import org.sunsetware.phocid.ui.views.LibraryScreenHomeViewItem
import org.sunsetware.phocid.ui.views.LibraryScreenHomeViewState
import org.sunsetware.phocid.ui.views.PlaylistCollectionViewInfo
import org.sunsetware.phocid.utils.*

@Stable
abstract class Dialog {
    @Composable abstract fun Compose(viewModel: MainViewModel)
}

@Stable
abstract class TopLevelScreen {
    @Composable abstract fun Compose(viewModel: MainViewModel)
}

class UiManager(
    private val context: Context,
    coroutineScope: CoroutineScope,
    private val preferences: StateFlow<Preferences>,
    private val libraryIndex: StateFlow<LibraryIndex>,
    private val playlistManager: PlaylistManager,
) : AutoCloseable {
    private val _topLevelScreenStack = MutableStateFlow(emptyList<TopLevelScreen>())
    val topLevelScreenStack = _topLevelScreenStack.asStateFlow()

    private val _dialog = MutableStateFlow(null as Dialog?)
    val dialog = _dialog.asStateFlow()

    val libraryScreenSearchQuery = MutableStateFlow("")

    val libraryScreenHomeViewState =
        LibraryScreenHomeViewState(
            coroutineScope,
            preferences,
            libraryIndex,
            playlistManager,
            libraryScreenSearchQuery,
        )

    private val _libraryScreenCollectionViewStack =
        MutableStateFlow(emptyList<LibraryScreenCollectionViewState>())
    val libraryScreenCollectionViewStack = _libraryScreenCollectionViewStack.asStateFlow()

    val libraryScreenCollectionViewPurgeJob =
        coroutineScope.launch {
            _libraryScreenCollectionViewStack
                .flatMapLatest { stack ->
                    val last = stack.lastOrNull()
                    last?.info?.map { if (it == null) last else null } ?: MutableStateFlow(null)
                }
                .onEach { purgeTarget ->
                    if (purgeTarget != null)
                        _libraryScreenCollectionViewStack.update { stack ->
                            stack.filter { it !== purgeTarget }
                        }
                }
                .collect()
        }

    val playerScreenDragState = BinaryDragState()

    val overrideStatusBarLightColor = MutableStateFlow(null as Boolean?)

    val playerTimerSettings = AtomicReference(PlayerTimerSettings())

    private val libraryScreenActiveMultiSelectState =
        _libraryScreenCollectionViewStack.combine(
            coroutineScope,
            libraryScreenHomeViewState.activeMultiSelectState,
        ) { collectionViewStack, homeViewMultiSelectState ->
            collectionViewStack.lastOrNull()?.multiSelectState ?: homeViewMultiSelectState
        }

    private val libraryScreenActiveMultiSelectItems =
        libraryScreenActiveMultiSelectState.flatMapLatest(coroutineScope) {
            it?.items ?: MutableStateFlow(SelectableList<LibraryScreenHomeViewItem>(emptyList()))
        }

    val backHandlerEnabled =
        _dialog
            .map(coroutineScope) { it != null }
            .combine(coroutineScope, _topLevelScreenStack) { enabled, topLevelScreenStack ->
                enabled || topLevelScreenStack.isNotEmpty()
            }
            .combine(coroutineScope, playerScreenDragState.targetValue) { enabled, playerVisibility
                ->
                enabled || playerVisibility == 1f
            }
            .combine(coroutineScope, libraryScreenActiveMultiSelectItems) {
                enabled,
                libraryScreenActiveMultiSelectItems ->
                enabled || libraryScreenActiveMultiSelectItems.selection.isNotEmpty()
            }
            .combine(coroutineScope, _libraryScreenCollectionViewStack) {
                enabled,
                collectionViewStack ->
                enabled || collectionViewStack.isNotEmpty()
            }

    private val saveManager =
        SaveManager(
            context,
            coroutineScope,
            flow {
                    while (currentCoroutineContext().isActive) {
                        emit(
                            PersistentUiState(
                                libraryScreenHomeViewState.pagerState.currentPage,
                                playerTimerSettings.get(),
                            )
                        )
                        delay(1.seconds)
                    }
                }
                .distinctUntilChanged(),
            UI_STATE_FILE_NAME,
            false,
        )

    init {
        val persistentState =
            loadCbor<PersistentUiState>(context, UI_STATE_FILE_NAME, false) ?: PersistentUiState()
        coroutineScope.launch {
            libraryScreenHomeViewState.pagerState.scrollToPage(
                persistentState.libraryScreenHomeViewPage
            )
        }
        playerTimerSettings.set(persistentState.playerTimerSettings)
    }

    override fun close() {
        saveManager.close()
        libraryScreenCollectionViewPurgeJob.cancel()
    }

    fun back() {
        when {
            _dialog.value != null -> {
                _dialog.update { null }
            }
            _topLevelScreenStack.value.isNotEmpty() -> {
                _topLevelScreenStack.update { it.dropLast(1) }
            }
            playerScreenDragState.targetValue.value == 1f -> {
                playerScreenDragState.animateTo(0f)
            }
            libraryScreenActiveMultiSelectItems.value.selection.isNotEmpty() -> {
                libraryScreenActiveMultiSelectState.value?.clearSelection()
            }
            _libraryScreenCollectionViewStack.value.isNotEmpty() -> {
                var closedView = null as LibraryScreenCollectionViewState?
                _libraryScreenCollectionViewStack.update {
                    closedView = it.lastOrNull()
                    it.dropLast(1)
                }
                closedView?.close()
            }
        }
    }

    fun openCollectionView(selector: (LibraryIndex) -> CollectionViewInfo?) {
        val viewScope = MainScope()
        val state =
            LibraryScreenCollectionViewState(
                viewScope,
                preferences,
                libraryIndex.map(viewScope, false, selector),
            )
        _libraryScreenCollectionViewStack.update { it + state }
        playerScreenDragState.animateTo(0f)
    }

    fun openPlaylistCollectionView(key: UUID) {
        val viewScope = MainScope()
        val state =
            LibraryScreenCollectionViewState(
                viewScope,
                preferences,
                playlistManager.playlists.map(viewScope) { playlists ->
                    playlists[key]?.let { PlaylistCollectionViewInfo(key, it) }
                },
            )
        _libraryScreenCollectionViewStack.update { it + state }
        playerScreenDragState.animateTo(0f)
    }

    fun openTopLevelScreen(screen: TopLevelScreen) {
        _topLevelScreenStack.update { it + screen }
    }

    fun closeTopLevelScreen(screen: TopLevelScreen) {
        _topLevelScreenStack.update { it - screen }
    }

    fun openDialog(dialog: Dialog) {
        _dialog.update { dialog as Dialog? }
    }

    fun closeDialog() {
        _dialog.update { null }
    }

    fun toast(text: String, shortDuration: Boolean = true) {
        Toast.makeText(context, text, if (shortDuration) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
            .show()
    }
}

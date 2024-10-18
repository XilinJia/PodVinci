package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.net.download.DownloadStatus
import ac.mdiq.podcini.net.download.service.DownloadServiceInterface
import ac.mdiq.podcini.playback.base.InTheatre
import ac.mdiq.podcini.playback.base.InTheatre.curQueue
import ac.mdiq.podcini.playback.base.MediaPlayerBase.Companion.status
import ac.mdiq.podcini.storage.database.Episodes
import ac.mdiq.podcini.storage.database.Episodes.episodeFromStreamInfo
import ac.mdiq.podcini.storage.database.Episodes.setPlayState
import ac.mdiq.podcini.storage.database.Feeds.addToMiscSyndicate
import ac.mdiq.podcini.storage.database.Feeds.addToYoutubeSyndicate
import ac.mdiq.podcini.storage.database.Queues
import ac.mdiq.podcini.storage.database.Queues.addToQueueSync
import ac.mdiq.podcini.storage.database.Queues.removeFromAllQueuesQuiet
import ac.mdiq.podcini.storage.database.Queues.removeFromQueue
import ac.mdiq.podcini.storage.database.RealmDB.realm
import ac.mdiq.podcini.storage.database.RealmDB.upsert
import ac.mdiq.podcini.storage.database.RealmDB.upsertBlk
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.model.Feed.Companion.MAX_SYNTHETIC_ID
import ac.mdiq.podcini.storage.model.Feed.Companion.newId
import ac.mdiq.podcini.storage.utils.DurationConverter
import ac.mdiq.podcini.storage.utils.ImageResourceUtils
import ac.mdiq.podcini.ui.actions.EpisodeActionButton
import ac.mdiq.podcini.ui.actions.SwipeAction
import ac.mdiq.podcini.ui.activity.MainActivity
import ac.mdiq.podcini.ui.fragment.EpisodeInfoFragment
import ac.mdiq.podcini.ui.fragment.FeedInfoFragment
import ac.mdiq.podcini.ui.utils.LocalDeleteModal
import ac.mdiq.podcini.util.EventFlow
import ac.mdiq.podcini.util.FlowEvent
import ac.mdiq.podcini.util.Logd
import ac.mdiq.podcini.util.MiscFormatter.formatAbbrev
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.extractor.services.youtube.YoutubeParsingHelper.isYoutubeServiceURL
import ac.mdiq.vista.extractor.services.youtube.YoutubeParsingHelper.isYoutubeURL
import ac.mdiq.vista.extractor.stream.StreamInfo
import android.net.Uri
import android.text.format.Formatter
import android.util.Log
import android.util.TypedValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.documentfile.provider.DocumentFile
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import io.realm.kotlin.notifications.SingleQueryChange
import io.realm.kotlin.notifications.UpdatedObject
import kotlinx.coroutines.*
import java.io.File
import java.net.URL
import java.util.*
import kotlin.math.roundToInt

@Composable
fun InforBar(text: MutableState<String>, leftAction: MutableState<SwipeAction>, rightAction: MutableState<SwipeAction>, actionConfig: () -> Unit) {
    val textColor = MaterialTheme.colorScheme.onSurface
    Logd("InforBar", "textState: ${text.value}")
    Row {
        Icon(painter = painterResource(leftAction.value.getActionIcon()), tint = textColor, contentDescription = "left_action_icon",
            modifier = Modifier.width(24.dp).height(24.dp).clickable(onClick = actionConfig))
        Icon(painter = painterResource(R.drawable.baseline_arrow_left_alt_24), tint = textColor, contentDescription = "left_arrow", modifier = Modifier.width(24.dp).height(24.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(text.value, color = textColor, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        Icon(painter = painterResource(R.drawable.baseline_arrow_right_alt_24), tint = textColor, contentDescription = "right_arrow", modifier = Modifier.width(24.dp).height(24.dp))
        Icon(painter = painterResource(rightAction.value.getActionIcon()), tint = textColor, contentDescription = "right_action_icon",
            modifier = Modifier.width(24.dp).height(24.dp).clickable(onClick = actionConfig))
    }
}

var queueChanged by mutableIntStateOf(0)

@Stable
class EpisodeVM(var episode: Episode) {
    var positionState by mutableStateOf(episode.media?.position?:0)
    var playedState by mutableStateOf(episode.isPlayed())
    var isPlayingState by mutableStateOf(false)
    var ratingState by mutableIntStateOf(episode.rating)
    var inProgressState by mutableStateOf(episode.isInProgress)
    var downloadState by mutableIntStateOf(if (episode.media?.downloaded == true) DownloadStatus.State.COMPLETED.ordinal else DownloadStatus.State.UNKNOWN.ordinal)
    var actionButton by mutableStateOf<EpisodeActionButton?>(null)
    var actionRes by mutableIntStateOf(R.drawable.ic_questionmark)
    var showAltActionsDialog by mutableStateOf(false)
    var dlPercent by mutableIntStateOf(0)
    var inQueueState by mutableStateOf(curQueue.contains(episode))
    var isSelected by mutableStateOf(false)
    var prog by mutableFloatStateOf(0f)

    private var episodeMonitor: Job? by mutableStateOf(null)
    private var mediaMonitor: Job? by mutableStateOf(null)

    fun stopMonitoring() {
        episodeMonitor?.cancel()
        mediaMonitor?.cancel()
        episodeMonitor = null
        mediaMonitor = null
        Logd("EpisodeVM", "cancel monitoring")
    }

    fun startMonitoring() {
        if (episodeMonitor == null) {
            episodeMonitor = CoroutineScope(Dispatchers.Default).launch {
                val item_ = realm.query(Episode::class).query("id == ${episode.id}").first()
                Logd("EpisodeVM", "start monitoring episode: ${episode.title}")
                val episodeFlow = item_.asFlow()
                episodeFlow.collect { changes: SingleQueryChange<Episode> ->
                    when (changes) {
                        is UpdatedObject -> {
                            Logd("EpisodeVM", "episodeMonitor UpdatedObject ${changes.obj.title} ${changes.changedFields.joinToString()}")
                            if (episode.id == changes.obj.id) {
                                withContext(Dispatchers.Main) {
                                    playedState = changes.obj.isPlayed()
                                    ratingState = changes.obj.rating
                                    episode = changes.obj     // direct assignment doesn't update member like media??
                                }
                                Logd("EpisodeVM", "episodeMonitor $playedState $playedState ")
                            } else Logd("EpisodeVM", "episodeMonitor index out bound")
                        }
                        else -> {}
                    }
                }
            }
        }
        if (mediaMonitor == null) {
            mediaMonitor = CoroutineScope(Dispatchers.Default).launch {
                val item_ = realm.query(Episode::class).query("id == ${episode.id}").first()
                Logd("EpisodeVM", "start monitoring media: ${episode.title}")
                val episodeFlow = item_.asFlow(listOf("media.*"))
                episodeFlow.collect { changes: SingleQueryChange<Episode> ->
                    when (changes) {
                        is UpdatedObject -> {
                            Logd("EpisodeVM", "mediaMonitor UpdatedObject ${changes.obj.title} ${changes.changedFields.joinToString()}")
                            if (episode.id == changes.obj.id) {
                                withContext(Dispatchers.Main) {
                                    positionState = changes.obj.media?.position ?: 0
                                    inProgressState = changes.obj.isInProgress
                                    Logd("EpisodeVM", "mediaMonitor $positionState $inProgressState ${episode.title}")
                                    episode = changes.obj
//                                    Logd("EpisodeVM", "mediaMonitor downloaded: ${changes.obj.media?.downloaded} ${episode.media?.downloaded}")
                                }
                            } else Logd("EpisodeVM", "mediaMonitor index out bound")
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
fun ChooseRatingDialog(selected: List<Episode>, onDismissRequest: () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (rating in Rating.entries.reversed()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp).clickable {
                        for (item in selected) Episodes.setRating(item, rating.code)
                        onDismissRequest()
                    }) {
                        Icon(imageVector = ImageVector.vectorResource(id = rating.res), "")
                        Text(rating.name, Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PutToQueueDialog(selected: List<Episode>, onDismissRequest: () -> Unit) {
    val queues = realm.query(PlayQueue::class).find()
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            val scrollState = rememberScrollState()
            Column(modifier = Modifier.verticalScroll(scrollState).padding(16.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                var removeChecked by remember { mutableStateOf(false) }
                var toQueue by remember { mutableStateOf(curQueue) }
                for (q in queues) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = toQueue == q, onClick = { toQueue = q })
                        Text(q.name)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeChecked, onCheckedChange = { removeChecked = it })
                    Text(text = stringResource(R.string.remove_from_other_queues), style = MaterialTheme.typography.bodyLarge.merge(), modifier = Modifier.padding(start = 10.dp))
                }
                Row {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (removeChecked) {
                            val toRemove = mutableSetOf<Long>()
                            val toRemoveCur = mutableListOf<Episode>()
                            selected.forEach { e ->
                                if (curQueue.contains(e)) toRemoveCur.add(e)
                            }
                            selected.forEach { e ->
                                for (q in queues) {
                                    if (q.contains(e)) {
                                        toRemove.add(e.id)
                                        break
                                    }
                                }
                            }
                            if (toRemove.isNotEmpty()) runBlocking { removeFromAllQueuesQuiet(toRemove.toList()) }
                            if (toRemoveCur.isNotEmpty()) EventFlow.postEvent(FlowEvent.QueueEvent.removed(toRemoveCur))
                        }
                        selected.forEach { e ->
                            runBlocking { addToQueueSync(false, e, toQueue) }
                        }
                        onDismissRequest()
                    }) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}

@Composable
fun ShelveDialog(selected: List<Episode>, onDismissRequest: () -> Unit) {
    val synthetics = realm.query(Feed::class).query("id >= 100 && id <= 1000").find()
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            val scrollState = rememberScrollState()
            Column(modifier = Modifier.verticalScroll(scrollState).padding(16.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                var removeChecked by remember { mutableStateOf(false) }
                var toFeed by remember { mutableStateOf<Feed?>(null) }
                for (f in synthetics) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = toFeed == f, onClick = { toFeed = f })
                        Text(f.title ?: "No title")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeChecked, onCheckedChange = { removeChecked = it })
                    Text(text = stringResource(R.string.remove_from_current_feed), style = MaterialTheme.typography.bodyLarge.merge(), modifier = Modifier.padding(start = 10.dp))
                }
                if (toFeed != null) Row {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        val eList: MutableList<Episode> = mutableListOf()
                        for (e in selected) {
                            var e_ = e
                            if (!removeChecked || (e.feedId != null && e.feedId!! >= MAX_SYNTHETIC_ID)) {
                                e_ = realm.copyFromRealm(e)
                                e_.id = newId()
                                e_.media?.id = e_.id
                            } else {
                                val feed = realm.query(Feed::class).query("id == $0", e_.feedId).first().find()
                                if (feed != null) {
                                    upsertBlk(feed) {
                                        it.episodes.remove(e_)
                                    }
                                }
                            }
                            upsertBlk(e_) {
                                it.feed = toFeed
                                it.feedId = toFeed!!.id
                                eList.add(it)
                            }
                        }
                        upsertBlk(toFeed!!) {
                            it.episodes.addAll(eList)
                        }
                        onDismissRequest()
                    }) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EpisodeLazyColumn(activity: MainActivity, vms: SnapshotStateList<EpisodeVM>, feed: Feed? = null,
                      refreshCB: (()->Unit)? = null, leftSwipeCB: ((Episode) -> Unit)? = null, rightSwipeCB: ((Episode) -> Unit)? = null,
                      actionButton_: ((Episode)-> EpisodeActionButton)? = null) {
    val TAG = "EpisodeLazyColumn"
    var selectMode by remember { mutableStateOf(false) }
    var selectedSize by remember { mutableStateOf(0) }
    val selected = remember { mutableStateListOf<Episode>() }
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    var longPressIndex by remember { mutableIntStateOf(-1) }
    val dls = remember { DownloadServiceInterface.get() }
    val context = LocalContext.current

    val showConfirmYoutubeDialog = remember { mutableStateOf(false) }
    val youtubeUrls = remember { mutableListOf<String>() }
    ConfirmAddYoutubeEpisode(youtubeUrls, showConfirmYoutubeDialog.value, onDismissRequest = {
        showConfirmYoutubeDialog.value = false
    })

    var showChooseRatingDialog by remember { mutableStateOf(false) }
    if (showChooseRatingDialog) ChooseRatingDialog(selected) { showChooseRatingDialog = false }

    var showPutToQueueDialog by remember { mutableStateOf(false) }
    if (showPutToQueueDialog) PutToQueueDialog(selected) { showPutToQueueDialog = false }

    var showShelveDialog by remember { mutableStateOf(false) }
    if (showShelveDialog) ShelveDialog(selected) { showShelveDialog = false }

    @Composable
    fun EraseEpisodesDialog(onDismissRequest: () -> Unit) {
        val message = stringResource(R.string.erase_episodes_confirmation_msg)
        val textColor = MaterialTheme.colorScheme.onSurface
        var textState by remember { mutableStateOf(TextFieldValue("")) }
        val context = LocalContext.current

        Dialog(onDismissRequest = onDismissRequest) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(message + ": ${selected.size}")
                    Text(stringResource(R.string.feed_delete_reason_msg))
                    BasicTextField(value = textState, onValueChange = { textState = it },
                        textStyle = TextStyle(fontSize = 16.sp, color = textColor),
                        modifier = Modifier.fillMaxWidth().height(100.dp).padding(start = 10.dp, end = 10.dp, bottom = 10.dp).border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    )
                    Button(onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                for (e in selected) {
                                    val sLog = SubscriptionLog(e.id, e.title?:"", e.media?.downloadUrl?:"", e.link?:"", SubscriptionLog.Type.Media.name)
                                    upsert(sLog) {
                                        it.rating = e.rating
                                        it.comment = e.comment
                                        it.comment += "\nReason to remove:\n" + textState.text
                                        it.cancelDate = Date().time
                                    }
                                }
                                realm.write {
                                    for (e in selected) {
                                        val url = e.media?.fileUrl
                                        when {
                                            url != null && url.startsWith("content://") -> DocumentFile.fromSingleUri(context, Uri.parse(url))?.delete()
                                            url != null -> File(url).delete()
                                        }
                                        findLatest(feed!!)?.episodes?.remove(e)
                                        findLatest(e)?.let { delete(it) }
                                    }
                                }
                                EventFlow.postStickyEvent(FlowEvent.FeedUpdatingEvent(false))
                            } catch (e: Throwable) { Log.e("EraseEpisodesDialog", Log.getStackTraceString(e)) }
                        }
                        onDismissRequest()
                    }) {
                        Text("Confirm")
                    }
                }
            }
        }
    }

    var showEraseDialog by remember { mutableStateOf(false) }
    if (showEraseDialog) EraseEpisodesDialog(onDismissRequest = { showEraseDialog = false })

    @Composable
    fun EpisodeSpeedDial(modifier: Modifier = Modifier) {
        var isExpanded by remember { mutableStateOf(false) }
        val options = mutableListOf<@Composable () -> Unit>(
            { Row(modifier = Modifier.padding(horizontal = 16.dp)
                .clickable {
                    isExpanded = false
                    selectMode = false
                    Logd(TAG, "ic_delete: ${selected.size}")
                    LocalDeleteModal.deleteEpisodesWarnLocal(activity, selected)
                }, verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_delete), "Delete media")
                Text(stringResource(id = R.string.delete_episode_label)) } },
            { Row(modifier = Modifier.padding(horizontal = 16.dp)
                .clickable {
                    isExpanded = false
                    selectMode = false
                    Logd(TAG, "ic_download: ${selected.size}")
                    for (episode in selected) {
                        if (episode.media != null && episode.feed != null && !episode.feed!!.isLocalFeed) DownloadServiceInterface.get()
                            ?.download(activity, episode)
                    }
                }, verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_download), "Download")
                Text(stringResource(id = R.string.download_label)) } },
            { Row(modifier = Modifier.padding(horizontal = 16.dp)
                .clickable {
                    isExpanded = false
                    selectMode = false
                    Logd(TAG, "ic_mark_played: ${selected.size}")
                    setPlayState(Episode.PlayState.UNSPECIFIED.code, false, *selected.toTypedArray())
                }, verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_mark_played), "Toggle played state")
                Text(stringResource(id = R.string.toggle_played_label)) } },
            { Row(modifier = Modifier.padding(horizontal = 16.dp)
                .clickable {
                    isExpanded = false
                    selectMode = false
                    Logd(TAG, "ic_playlist_remove: ${selected.size}")
                    removeFromQueue(*selected.toTypedArray())
                }, verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_playlist_remove), "Remove from active queue")
                Text(stringResource(id = R.string.remove_from_queue_label)) } },
            { Row(modifier = Modifier.padding(horizontal = 16.dp)
                .clickable {
                    isExpanded = false
                    selectMode = false
                    Logd(TAG, "ic_playlist_play: ${selected.size}")
                    Queues.addToQueue(true, *selected.toTypedArray())
                }, verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_playlist_play), "Add to active queue")
                Text(stringResource(id = R.string.add_to_queue_label)) } },
            { Row(modifier = Modifier.padding(horizontal = 16.dp)
                .clickable {
                    isExpanded = false
                    selectMode = false
                    Logd(TAG, "shelve_label: ${selected.size}")
                    showShelveDialog = true
                }, verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.baseline_shelves_24), "Shelve")
                Text(stringResource(id = R.string.shelve_label)) } },
            { Row(modifier = Modifier.padding(horizontal = 16.dp)
                .clickable {
                    isExpanded = false
                    selectMode = false
                    Logd(TAG, "ic_playlist_play: ${selected.size}")
                    showPutToQueueDialog = true
                }, verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_playlist_play), "Add to queue...")
                Text(stringResource(id = R.string.put_in_queue_label)) } },
            { Row(modifier = Modifier.padding(horizontal = 16.dp)
                .clickable {
                    selectMode = false
                    Logd(TAG, "ic_star: ${selected.size}")
                    showChooseRatingDialog = true
                    isExpanded = false
                }, verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_star), "Set rating")
                Text(stringResource(id = R.string.set_rating_label)) } },
        )
        if (selected.isNotEmpty() && selected[0].isRemote.value)
            options.add {
                Row(modifier = Modifier.padding(horizontal = 16.dp)
                    .clickable {
                        isExpanded = false
                        selectMode = false
                        Logd(TAG, "reserve: ${selected.size}")
                        CoroutineScope(Dispatchers.IO).launch {
                            youtubeUrls.clear()
                            for (e in selected) {
                                Logd(TAG, "downloadUrl: ${e.media?.downloadUrl}")
                                val url = URL(e.media?.downloadUrl ?: "")
                                if ((isYoutubeURL(url) && url.path.startsWith("/watch")) || isYoutubeServiceURL(url)) {
                                    youtubeUrls.add(e.media!!.downloadUrl!!)
                                } else addToMiscSyndicate(e)
                            }
                            Logd(TAG, "youtubeUrls: ${youtubeUrls.size}")
                            withContext(Dispatchers.Main) {
                                showConfirmYoutubeDialog.value = youtubeUrls.isNotEmpty()
                            }
                        }
                    }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AddCircle, "Reserve episodes")
                    Text(stringResource(id = R.string.reserve_episodes_label))
                }
            }
        if (feed != null && feed.id <= MAX_SYNTHETIC_ID) {
            options.add {
                Row(modifier = Modifier.padding(horizontal = 16.dp)
                    .clickable {
                        isExpanded = false
                        selectMode = false
                        showEraseDialog = true
                        Logd(TAG, "reserve: ${selected.size}")
                    }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AddCircle, "Erase episodes")
                    Text(stringResource(id = R.string.erase_episodes_label))
                }
            }
        }

        val scrollState = rememberScrollState()
        Column(modifier = modifier.verticalScroll(scrollState), verticalArrangement = Arrangement.Bottom) {
            if (isExpanded) options.forEachIndexed { _, button ->
                FloatingActionButton(modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).height(40.dp),
                    containerColor = Color.LightGray,
                    onClick = {}) { button() }
            }
            FloatingActionButton(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.secondary,
                onClick = { isExpanded = !isExpanded }) { Icon(Icons.Filled.Edit, "Edit") }
        }
    }

    var refreshing by remember { mutableStateOf(false)}
    PullToRefreshBox(modifier = Modifier.fillMaxWidth(), isRefreshing = refreshing, indicator = {}, onRefresh = {
        refreshing = true
        refreshCB?.invoke()
        refreshing = false
    }) {
        LazyColumn(state = lazyListState, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(vms, key = {index, vm -> vm.episode.id}) { index, vm ->
                vm.startMonitoring()
                DisposableEffect(Unit) {
                    onDispose {
                        Logd(TAG, "cancelling monitoring $index")
                        vm.stopMonitoring()
                    }
                }
                LaunchedEffect(vm.actionButton) {
                    Logd(TAG, "LaunchedEffect init actionButton")
                    if (vm.actionButton == null) {
                        vm.actionButton = if (actionButton_ != null) actionButton_(vm.episode) else EpisodeActionButton.forItem(vm.episode)
                        vm.actionRes = vm.actionButton!!.getDrawable()
                    }
                }
                val velocityTracker = remember { VelocityTracker() }
                val offsetX = remember { Animatable(0f) }
                Box(modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectHorizontalDragGestures(onDragStart = { velocityTracker.resetTracking() },
                            onHorizontalDrag = { change, dragAmount ->
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                coroutineScope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                            },
                            onDragEnd = {
                                coroutineScope.launch {
                                    val velocity = velocityTracker.calculateVelocity().x
                                    if (velocity > 1000f || velocity < -1000f) {
                                        Logd(TAG, "velocity: $velocity")
//                                        if (velocity > 0) rightSwipeCB?.invoke(vms[index].episode)
//                                        else leftSwipeCB?.invoke(vms[index].episode)
                                        if (velocity > 0) rightSwipeCB?.invoke(vm.episode)
                                        else leftSwipeCB?.invoke(vm.episode)
                                    }
                                    offsetX.animateTo(targetValue = 0f, animationSpec = tween(500))
                                }
                            }
                        )
                    }.offset { IntOffset(offsetX.value.roundToInt(), 0) }
                ) {
                    LaunchedEffect(key1 = selectMode, key2 = selectedSize) {
                        vm.isSelected = selectMode && vm.episode in selected
//                        Logd(TAG, "LaunchedEffect $index $isSelected ${selected.size}")
                    }
                    fun toggleSelected() {
                        vm.isSelected = !vm.isSelected
                        if (vm.isSelected) selected.add(vms[index].episode)
                        else selected.remove(vms[index].episode)
                    }
                    val textColor = MaterialTheme.colorScheme.onSurface
                    Column {
                        val dur = remember { vm.episode.media?.getDuration() ?: 0 }
                        val durText = remember { DurationConverter.getDurationStringLong(dur) }
                        Row(Modifier.background(if (vm.isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                            if (false) {
                                val typedValue = TypedValue()
                                LocalContext.current.theme.resolveAttribute(R.attr.dragview_background, typedValue, true)
                                Icon(painter = painterResource(typedValue.resourceId), tint = textColor, contentDescription = "drag handle",
                                    modifier = Modifier.width(16.dp).align(Alignment.CenterVertically))
                            }
                            Logd(TAG, "episode.imageUrl: ${vm.episode.imageUrl}")
                            ConstraintLayout(modifier = Modifier.width(56.dp).height(56.dp)) {
                                val (imgvCover, checkMark) = createRefs()
                                val imgLoc = remember { ImageResourceUtils.getEpisodeListImageLocation(vm.episode) }
                                Logd(TAG, "imgLoc: $imgLoc")
                                val painter = rememberAsyncImagePainter(model = ImageRequest.Builder(context).data(imgLoc)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .placeholder(R.mipmap.ic_launcher)
                                    .error(R.mipmap.ic_launcher).build())
                                Image(painter = painter, contentDescription = "imgvCover",
                                    modifier = Modifier.width(56.dp).height(56.dp)
                                        .constrainAs(imgvCover) {
                                            top.linkTo(parent.top)
                                            bottom.linkTo(parent.bottom)
                                            start.linkTo(parent.start)
                                        }.clickable(onClick = {
                                            Logd(TAG, "icon clicked!")
                                            if (selectMode) toggleSelected()
                                            else if (vm.episode.feed != null) activity.loadChildFragment(FeedInfoFragment.newInstance(vm.episode.feed!!))
                                        })
                                )
                                val alpha = if (vm.playedState) 1.0f else 0f
                                if (vm.playedState) Icon(painter = painterResource(R.drawable.ic_check), tint = textColor, contentDescription = "played_mark",
                                    modifier = Modifier.background(Color.Green).alpha(alpha).constrainAs(checkMark) {
                                        bottom.linkTo(parent.bottom)
                                        end.linkTo(parent.end)
                                    })
                            }
                            Column(Modifier.weight(1f).padding(start = 6.dp, end = 6.dp)
                                .combinedClickable(onClick = {
                                    Logd(TAG, "clicked: ${vm.episode.title}")
                                    if (selectMode) toggleSelected()
                                    else activity.loadChildFragment(EpisodeInfoFragment.newInstance(vm.episode))
                                }, onLongClick = {
                                    selectMode = !selectMode
                                    vm.isSelected = selectMode
                                    selected.clear()
                                    if (selectMode) {
                                        selected.add(vms[index].episode)
                                        longPressIndex = index
                                    } else {
                                        selectedSize = 0
                                        longPressIndex = -1
                                    }
                                    Logd(TAG, "long clicked: ${vm.episode.title}")
                                })) {
                                LaunchedEffect(key1 = queueChanged) {
                                    if (index >= vms.size) return@LaunchedEffect
                                    vms[index].inQueueState = curQueue.contains(vms[index].episode)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (vm.episode.media?.getMediaType() == MediaType.VIDEO)
                                        Icon(painter = painterResource(R.drawable.ic_videocam), tint = textColor, contentDescription = "isVideo",
                                            modifier = Modifier.width(14.dp).height(14.dp))
                                    val ratingIconRes = Rating.fromCode(vm.ratingState).res
                                    if (vm.ratingState != Rating.UNRATED.code)
                                        Icon(painter = painterResource(ratingIconRes), tint = MaterialTheme.colorScheme.tertiary, contentDescription = "rating",
                                            modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer).width(14.dp).height(14.dp))
                                    if (vm.inQueueState)
                                        Icon(painter = painterResource(R.drawable.ic_playlist_play), tint = textColor, contentDescription = "ivInPlaylist",
                                            modifier = Modifier.width(14.dp).height(14.dp))
                                    val curContext = LocalContext.current
                                    val dateSizeText = " · " + formatAbbrev(curContext, vm.episode.getPubDate()) + " · " + durText + " · " +
                                            if ((vm.episode.media?.size ?: 0) > 0) Formatter.formatShortFileSize(curContext, vm.episode.media?.size ?: 0) else ""
                                    Text(dateSizeText, color = textColor, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(vm.episode.title ?: "", color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            fun isDownloading(): Boolean {
                                return vms[index].downloadState > DownloadStatus.State.UNKNOWN.ordinal && vms[index].downloadState < DownloadStatus.State.COMPLETED.ordinal
                            }
                            if (actionButton_ == null) {
                                LaunchedEffect(vms[index].downloadState) {
                                    if (index >= vms.size) return@LaunchedEffect
                                    if (isDownloading()) vm.dlPercent = dls?.getProgress(vms[index].episode.media?.downloadUrl ?: "") ?: 0
                                    Logd(TAG, "LaunchedEffect $index downloadState: ${vms[index].downloadState} ${vm.episode.media?.downloaded} ${vm.dlPercent}")
                                    vm.actionButton = EpisodeActionButton.forItem(vm.episode)
                                    vm.actionRes = vm.actionButton!!.getDrawable()
                                }
                                LaunchedEffect(key1 = status) {
                                    if (index >= vms.size) return@LaunchedEffect
                                    Logd(TAG, "LaunchedEffect $index isPlayingState: ${vms[index].isPlayingState} ${vms[index].episode.title}")
                                    vm.actionButton = EpisodeActionButton.forItem(vm.episode)
                                    Logd(TAG, "LaunchedEffect vm.actionButton: ${vm.actionButton?.getLabel()}")
                                    vm.actionRes = vm.actionButton!!.getDrawable()
                                }
//                            LaunchedEffect(vm.isPlayingState) {
//                                Logd(TAG, "LaunchedEffect isPlayingState: $index ${vms[index].isPlayingState} ${vm.isPlayingState}")
//                                vms[index].actionButton = EpisodeActionButton.forItem(vms[index].episode)
//                                vms[index].actionRes = vm.actionButton.getDrawable()
//                            }
                            }
                            Box(modifier = Modifier.width(40.dp).height(40.dp).padding(end = 10.dp)
                                .align(Alignment.CenterVertically).pointerInput(Unit) {
                                detectTapGestures(onLongPress = { vm.showAltActionsDialog = true }, onTap = {
                                    vms[index].actionButton?.onClick(activity)
                                })
                            }, contentAlignment = Alignment.Center) {
//                            actionRes = actionButton.getDrawable()
                                Icon(painter = painterResource(vm.actionRes), tint = textColor, contentDescription = null, modifier = Modifier.width(28.dp).height(32.dp))
                                if (isDownloading() && vm.dlPercent >= 0) CircularProgressIndicator(progress = { 0.01f * vm.dlPercent },
                                    strokeWidth = 4.dp, color = textColor, modifier = Modifier.width(30.dp).height(35.dp))
                            }
                            if (vm.showAltActionsDialog) vm.actionButton?.AltActionsDialog(activity, vm.showAltActionsDialog,
                                onDismiss = { vm.showAltActionsDialog = false })
                        }
                        if (vm.inProgressState || InTheatre.isCurMedia(vm.episode.media)) {
                            val pos = vm.positionState
                            vm.prog = if (dur > 0 && pos >= 0 && dur >= pos) 1.0f * pos / dur else 0f
                            Logd(TAG, "$index vm.prog: ${vm.prog}")
                            Row {
                                Text(DurationConverter.getDurationStringLong(vm.positionState), color = textColor, style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(progress = { vm.prog }, modifier = Modifier.weight(1f).height(4.dp).align(Alignment.CenterVertically))
                                Text(durText, color = textColor, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (selectMode) {
            Row(modifier = Modifier.align(Alignment.TopEnd).width(150.dp).height(45.dp).background(Color.LightGray), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(R.drawable.baseline_arrow_upward_24), tint = Color.Black, contentDescription = null, modifier = Modifier.width(35.dp).height(35.dp).padding(end = 10.dp)
                    .clickable(onClick = {
                        selected.clear()
                        for (i in 0..longPressIndex) {
                            selected.add(vms[i].episode)
                        }
                        selectedSize = selected.size
                        Logd(TAG, "selectedIds: ${selected.size}")
                    }))
                Icon(painter = painterResource(R.drawable.baseline_arrow_downward_24), tint = Color.Black, contentDescription = null, modifier = Modifier.width(35.dp).height(35.dp).padding(end = 10.dp)
                    .clickable(onClick = {
                        selected.clear()
                        for (i in longPressIndex..<vms.size) {
                            selected.add(vms[i].episode)
                        }
                        selectedSize = selected.size
                        Logd(TAG, "selectedIds: ${selected.size}")
                    }))
                var selectAllRes by remember { mutableIntStateOf(R.drawable.ic_select_all) }
                Icon(painter = painterResource(selectAllRes), tint = Color.Black, contentDescription = null, modifier = Modifier.width(35.dp).height(35.dp)
                    .clickable(onClick = {
                       if (selectedSize != vms.size) {
                           selected.clear()
                           for (vm in vms) {
                               selected.add(vm.episode)
                           }
                           selectAllRes = R.drawable.ic_select_none
                       } else {
                           selected.clear()
                           longPressIndex = -1
                           selectAllRes = R.drawable.ic_select_all
                       }
                        selectedSize = selected.size
                        Logd(TAG, "selectedIds: ${selected.size}")
                    }))
            }
            EpisodeSpeedDial(modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 16.dp, start = 16.dp))
        }
    }
}

@Composable
fun ConfirmAddYoutubeEpisode(sharedUrls: List<String>, showDialog: Boolean, onDismissRequest: () -> Unit) {
    val TAG = "confirmAddEpisode"
    var showToast by remember { mutableStateOf(false) }
    var toastMassege by remember { mutableStateOf("")}
    if (showToast) CustomToast(message = toastMassege, onDismiss = { showToast = false })

    if (showDialog) {
        Dialog(onDismissRequest = { onDismissRequest() }) {
            Card(modifier = Modifier.wrapContentSize(align = Alignment.Center).padding(16.dp), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
                    var audioOnly by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(checked = audioOnly, onCheckedChange = { audioOnly = it })
                        Text(text = stringResource(R.string.pref_video_mode_audio_only), style = MaterialTheme.typography.bodyLarge.merge())
                    }
                    var showComfirmButton by remember { mutableStateOf(true) }
                    if (showComfirmButton) {
                        Button(onClick = {
                            showComfirmButton = false
                            CoroutineScope(Dispatchers.IO).launch {
                                for (url in sharedUrls) {
                                    val log = realm.query(ShareLog::class).query("url == $0", url).first().find()
                                    try {
                                        val info = StreamInfo.getInfo(Vista.getService(0), url)
                                        val episode = episodeFromStreamInfo(info)
                                        val status = addToYoutubeSyndicate(episode, !audioOnly)
                                        if (log != null) upsert(log) { it.status = status }
                                    } catch (e: Throwable) {
                                        toastMassege = "Receive share error: ${e.message}"
                                        Log.e(TAG, toastMassege)
                                        if (log != null) upsert(log) { it.details = e.message?: "error" }
                                        withContext(Dispatchers.Main) { showToast = true }
                                    }
                                }
                                withContext(Dispatchers.Main) { onDismissRequest() }
                            }
                        }) {
                            Text("Confirm")
                        }
                    } else CircularProgressIndicator(progress = { 0.6f }, strokeWidth = 4.dp, modifier = Modifier.padding(start = 20.dp, end = 20.dp).width(30.dp).height(30.dp))
                }
            }
        }
    }
}

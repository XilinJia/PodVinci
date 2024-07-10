package ac.mdiq.podcini.ui.actions.swipeactions

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.EpisodeFilter
import ac.mdiq.podcini.ui.utils.LocalDeleteModal.deleteEpisodesWarnLocal
import android.content.Context
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi

class DeleteSwipeAction : SwipeAction {
    override fun getId(): String {
        return SwipeAction.DELETE
    }

    override fun getActionIcon(): Int {
        return R.drawable.ic_delete
    }

    override fun getActionColor(): Int {
        return R.attr.icon_red
    }

    override fun getTitle(context: Context): String {
        return context.getString(R.string.delete_episode_label)
    }

    @UnstableApi override fun performAction(item: Episode, fragment: Fragment, filter: EpisodeFilter) {
        if (!item.isDownloaded && item.feed?.isLocalFeed != true) return

        deleteEpisodesWarnLocal(fragment.requireContext(), listOf(item))
    }

    override fun willRemove(filter: EpisodeFilter, item: Episode): Boolean {
        return filter.showDownloaded && (item.isDownloaded || item.feed?.isLocalFeed == true)
    }
}

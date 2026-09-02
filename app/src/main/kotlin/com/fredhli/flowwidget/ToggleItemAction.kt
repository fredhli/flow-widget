package com.fredhli.flowwidget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll

/**
 * Expand-mode row tap (round 3 item 4a). Runs in this app's process via Glance's
 * ActionCallbackBroadcastReceiver — no activity, no browser: toggle the expanded row in
 * the DataStore (which also marks the item read) and repaint every widget instance, so
 * two placed widgets never disagree about which row is open.
 */
class ToggleItemAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[KEY_ITEM_ID] ?: return
        FlowStore.get(context).toggleExpanded(id)
        FlowWidget().updateAll(context)
    }

    companion object {
        val KEY_ITEM_ID = ActionParameters.Key<String>("item_id")
    }
}

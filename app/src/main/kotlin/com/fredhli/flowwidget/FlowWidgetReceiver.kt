package com.fredhli.flowwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Widget lifecycle: keep the 30-minute fetch scheduled while any widget exists. */
class FlowWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = FlowWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        FlowWork.schedulePeriodic(context)
        FlowWork.fetchNow(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        FlowWork.schedulePeriodic(context)
        FlowWork.fetchNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        FlowWork.cancelPeriodic(context)
    }
}

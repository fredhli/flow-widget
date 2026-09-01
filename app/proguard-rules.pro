# R8 rules for the Flow widget.
#
# Why shrink at all: the app renders RemoteViews and never composes a Compose screen, but
# glance-appwidget brings the Compose runtime and parts of ui-graphics / ui-unit with it,
# and R8 is what stops the unused remainder shipping. Unminified this is tens of MB of dex
# in a shell around one widget, pushed through Dropbox to every synced device on every
# rebuild.
#
# (This paragraph used to blame glance-material3 "for the ColorProviders call alone". No
# such call exists — the palette is explicit day/night ColorProviders from glance core —
# and both material3 dependencies were dropped at review. Minification is still worth
# keeping; the reason above is the real one.)

# Shrink, but never rename. Every reflective lookup in this app is by class name —
# WorkManager's WorkerFactory, Glance's ActionCallback dispatch, the manifest's component
# names, AndroidX Startup's initializers — and keeping the original names means all of
# them still resolve without a rule per library. The size win here is tree-shaking the
# unused Compose toolkit, not obfuscation, so this costs essentially nothing.
-dontobfuscate

# Our own classes: entry points reached by name from the manifest, from WorkManager and
# from Glance. Blanket-kept because there are only ten of them and the whole package is a
# rounding error against what R8 removes.
-keep class com.fredhli.flowwidget.** { *; }

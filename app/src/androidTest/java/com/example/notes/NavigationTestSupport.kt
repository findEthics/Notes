package com.example.notes

import androidx.navigation.NavController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

internal fun launchNote(id: Long): ActivityScenario<MainActivity> = launchDestination("note/$id")
internal fun launchTask(id: Long? = null): ActivityScenario<MainActivity> = launchDestination("task?id=${id ?: -1}")

private fun launchDestination(route: String): ActivityScenario<MainActivity> {
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    androidx.test.espresso.Espresso.onIdle()
    scenario.onActivity { it.navController.navigate(route) }
    androidx.test.espresso.Espresso.onIdle()
    var opened = false
    val deadline = android.os.SystemClock.uptimeMillis() + 5000
    while (!opened && android.os.SystemClock.uptimeMillis() < deadline) {
        androidx.test.espresso.Espresso.onIdle()
        scenario.onActivity { opened = it.currentEditor != null }
        if (!opened) Thread.sleep(20)
    }
    check(opened) { "Editor destination did not compose: $route" }
    return scenario
}

/** Observe back-stack entries, not Activity launches: replacement editors share one Activity. */
internal class NavigationMonitor(name: String) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var listener: NavController.OnDestinationChangedListener
    @Volatile var hits = 0
        private set
    @Volatile private var opened: MainActivity? = null
    init {
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            val initialId = activity.navController.currentBackStackEntry?.id
            listener = NavController.OnDestinationChangedListener { controller, destination, _ ->
                if (controller.currentBackStackEntry?.id != initialId &&
                    destination.route?.startsWith(if (name.contains("Note")) "note/" else "task?") == true) {
                    hits++; opened = activity
                }
            }
            activity.navController.addOnDestinationChangedListener(listener)
        }
    }
    fun await(): MainActivity? {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (opened == null && android.os.SystemClock.uptimeMillis() < deadline) {
            androidx.test.espresso.Espresso.onIdle()
            Thread.sleep(20)
        }
        androidx.test.espresso.Espresso.onIdle()
        return opened
    }
    fun reset() { opened = null }
    override fun close() { instrumentation.runOnMainSync { activity.navController.removeOnDestinationChangedListener(listener) } }
}

internal fun navigationMonitor(name: String) = NavigationMonitor(name)

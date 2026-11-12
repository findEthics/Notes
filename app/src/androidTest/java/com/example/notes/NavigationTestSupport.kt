package com.example.notes

import androidx.navigation.NavController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

internal fun launchNote(id: Long): ActivityScenario<MainActivity> = launchDestination("note/$id")
internal fun launchTask(id: Long? = null): ActivityScenario<MainActivity> {
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    androidx.test.espresso.Espresso.onIdle()
    scenario.onActivity { it.openTask(id) }
    androidx.test.espresso.Espresso.onIdle()
    scenario.onActivity {
        check(it.navController.currentDestination?.route == "list")
        check((it.currentEditor as? TaskScreen)?.editingTaskId == (id ?: -1L))
    }
    return scenario
}

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

/** Notes use navigation; inline to-dos replace the editor session on the list destination. */
internal class NavigationMonitor(name: String) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var listener: NavController.OnDestinationChangedListener
    private val inline = name == TaskScreen::class.java.name
    private var lastTask: TaskScreen? = null
    @Volatile private var count = 0
    val hits: Int get() { observeInline(); return count }
    @Volatile private var opened: MainActivity? = null
    init {
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            lastTask = activity.currentEditor as? TaskScreen
            val initialId = activity.navController.currentBackStackEntry?.id
            listener = NavController.OnDestinationChangedListener { controller, destination, _ ->
                if (!inline && controller.currentBackStackEntry?.id != initialId &&
                    destination.route?.startsWith("note/") == true) {
                    count++; opened = activity
                }
            }
            activity.navController.addOnDestinationChangedListener(listener)
        }
    }
    private fun observeInline() {
        if (!inline) return
        instrumentation.runOnMainSync {
            val editor = activity.currentEditor as? TaskScreen
            if (editor != null && editor !== lastTask) {
                check(activity.navController.currentDestination?.route == "list")
                count++; opened = activity
            }
            lastTask = editor
        }
    }
    fun await(): MainActivity? {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (opened == null && android.os.SystemClock.uptimeMillis() < deadline) {
            androidx.test.espresso.Espresso.onIdle()
            observeInline()
            if (opened == null) Thread.sleep(20)
        }
        androidx.test.espresso.Espresso.onIdle()
        return opened
    }
    fun reset() { observeInline(); opened = null }
    override fun close() { instrumentation.runOnMainSync { activity.navController.removeOnDestinationChangedListener(listener) } }
}

internal fun navigationMonitor(name: String) = NavigationMonitor(name)

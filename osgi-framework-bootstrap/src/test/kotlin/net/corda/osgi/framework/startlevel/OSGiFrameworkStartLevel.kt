package net.corda.osgi.framework.startlevel

import org.osgi.framework.Bundle
import org.osgi.framework.FrameworkEvent
import org.osgi.framework.FrameworkListener
import org.osgi.framework.launch.Framework
import org.osgi.framework.startlevel.FrameworkStartLevel
import java.util.concurrent.atomic.AtomicInteger

class OSGiFrameworkStartLevel(
    private val framework: Framework,
    private val initialStartLevel: Int
) : FrameworkStartLevel {

    companion object {
        const val OFF = 0
        const val ON = 1
    }

    private val startLevelAtomic = AtomicInteger(OFF)

    private val initialStartLevelAtomic = AtomicInteger(initialStartLevel)

    override fun getBundle(): Bundle {
        return framework
    }

    override fun getStartLevel(): Int {
        return startLevelAtomic.get()
    }

    override fun setStartLevel(startlevel: Int, vararg listeners: FrameworkListener?) {
        synchronized(startLevelAtomic) {
            startLevelAtomic.set(startlevel)
            val frameworkEvent = FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, bundle, null)
            listeners.forEach { listener ->
                listener?.frameworkEvent(frameworkEvent)
            }
        }
    }

    override fun getInitialBundleStartLevel(): Int {
        return initialStartLevelAtomic.get()
    }

    override fun setInitialBundleStartLevel(startlevel: Int) {
        initialStartLevelAtomic.set(startlevel)
    }
}
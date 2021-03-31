package net.corda.osgi.framework

import org.apache.sling.testing.mock.osgi.MockBundle
import org.apache.sling.testing.mock.osgi.MockOsgi
import org.osgi.framework.*
import org.osgi.framework.launch.Framework
import java.io.File
import java.io.InputStream
import java.lang.IllegalStateException
import java.net.URL
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.Throws


class OSGiFrameworkMock(
    private val configurationMap: MutableMap<String, String>,
    private val transitionDelay: Long = 100L,
    private val version: Version = Version(0, 0, 0, "mock")
) : Framework {

    companion object {

        private const val NO_WAIT = 0

        private const val TIMEOUT_MULTIPLIER = 2L

        private const val WAIT = 1

    }

    private val bundleContext = MockOsgi.newBundleContext()

    private val bundle = MockBundle(bundleContext)

    private val listenersAtomic = AtomicReference(listOf<FrameworkListener>())

    private val stateAtomic = AtomicInteger(Bundle.INSTALLED)

    private val versionAtomic = AtomicReference(version)

    private val transitionLatchAtomic = AtomicReference(CountDownLatch(NO_WAIT))

    private fun resolve() {
        synchronized(transitionLatchAtomic) {
            transitionLatchAtomic.set(CountDownLatch(WAIT))
            CompletableFuture.runAsync {
                setState(Bundle.RESOLVED)
                Thread.sleep(transitionDelay)
                transitionLatchAtomic.get().countDown()
            }
        }
    }

    private fun setState(state: Int) {
        synchronized(stateAtomic) {
            stateAtomic.set(state)
            val frameworkEvent = FrameworkEvent(state, this, null)
            for (listener in listenersAtomic.get()) {
                listener.frameworkEvent(frameworkEvent)
            }
        }
    }

    private fun started() {
        synchronized(transitionLatchAtomic) {
            transitionLatchAtomic.set(CountDownLatch(WAIT))
            CompletableFuture.runAsync {
                setState(Bundle.ACTIVE)
                transitionLatchAtomic.get().countDown()
            }
        }
    }

    private fun starting() {
        synchronized(transitionLatchAtomic) {
            transitionLatchAtomic.set(CountDownLatch(WAIT))
            CompletableFuture.runAsync {
                setState(Bundle.STARTING)
                Thread.sleep(transitionDelay)
                transitionLatchAtomic.get().countDown()
            }
        }
    }

    // Framework

    override fun compareTo(other: Bundle): Int {
        val thisId = this.bundleId
        val thatId = other.bundleId
        return if (thisId < thatId) -1 else if (thatId == thatId) 0 else 1
    }

    override fun getState(): Int {
        return stateAtomic.get()
    }

    @Throws(
        BundleException::class,
        IllegalStateException::class,
        InterruptedException::class,
        InterruptedException::class
    )
    override fun start() {
        // 0,9.
        // If this bundle's state is UNINSTALLED then an IllegalStateException is thrown.
        if (state != Bundle.UNINSTALLED) {
            // 1.
            // If this bundle is in the process of being activated or deactivated then this method must wait for
            // activation or deactivation to complete before continuing.
            // If this does not occur in a reasonable time,
            // a BundleException is thrown to indicate this bundle was unable to be started.
            if (transitionLatchAtomic.get().await(transitionDelay * TIMEOUT_MULTIPLIER, TimeUnit.MILLISECONDS)) {
                when (state) {
                    // 2.
                    // If this bundle's state is ACTIVE then this method returns immediately.
                    Bundle.ACTIVE -> return
                    // 3. IGNORED AS FRAMEWORK!
                    // If the START_TRANSIENT option is not set then set this bundle's autostart setting to
                    // Started with declared activation if the START_ACTIVATION_POLICY option is set or Started
                    // with eager activation if not set. When the Framework is restarted and this bundle's autostart
                    // setting is not Stopped, this bundle must be automatically started.
                    // 4. If this bundle's state is not RESOLVED, an attempt is made to resolve this bundle.
                    // If the Framework cannot resolve this bundle, a BundleException is thrown.
                    Bundle.INSTALLED -> resolve()
                    // 5. IGNORED AS FRAMEWORK!
                    // * If the START_ACTIVATION_POLICY option is set and this bundle's declared activation policy is lazy then:
                    // * If this bundle's state is STARTING then this method returns immediately.
                    // * This bundle's state is set to STARTING.
                    // * A bundle event of type BundleEvent.LAZY_ACTIVATION is fired.
                    // * This method returns immediately and the remaining steps will be followed
                    //   when this bundle's activation is later triggered.
                    // 6,7.
                    // This bundle's state is set to STARTING.
                    // A bundle event of type BundleEvent.STARTING is fired.
                    Bundle.RESOLVED -> starting()
                    // 8. IGNORED AS FRAMEWORK!
                    // The BundleActivator.start(BundleContext) method of this bundle's BundleActivator,
                    // if one is specified, is called. If the BundleActivator is invalid or throws an exception then:
                    // * This bundle's state is set to STOPPING.
                    // * A bundle event of type BundleEvent.STOPPING is fired.
                    // * Any services registered by this bundle must be unregistered.
                    // * Any services used by this bundle must be released.
                    // * Any listeners registered by this bundle must be removed.
                    // * This bundle's state is set to RESOLVED.
                    // * A bundle event of type BundleEvent.STOPPED is fired.
                    // * A BundleException is then thrown.
                    // 10, 11
                    // This bundle's state is set to ACTIVE.
                    // A bundle event of type BundleEvent.STARTED is fired.
                    Bundle.STARTING -> started()
                }
                start()
            }
            throw BundleException("")
        }
        throw IllegalStateException()
    }

    override fun start(ignored: Int) {
        start()
    }





    override fun stop() {
//        if (isStoppable(state)) {
//            CompletableFuture.runAsync {
//                Thread.sleep(transitionDelay)
//                setState(Bundle.STOPPING)
//            }.thenRunAsync {
//                Thread.sleep(transitionDelay)
//                setState(Bundle.UNINSTALLED)
//            }
//        }
    }

    override fun stop(ignored: Int) {
        stop()
    }

    override fun update() {
        bundle.update()
    }

    override fun update(`in`: InputStream?) {
        bundle.update(`in`)
    }

    override fun uninstall() {
        bundle.uninstall()
    }

    override fun getHeaders(): Dictionary<String, String> {
        return bundle.headers
    }

    override fun getHeaders(locale: String?): Dictionary<String, String> {
        return bundle.getHeaders(locale)
    }

    override fun getBundleId(): Long {
        return Constants.SYSTEM_BUNDLE_ID
    }

    override fun getLocation(): String {
        return Constants.SYSTEM_BUNDLE_LOCATION
    }

    override fun getRegisteredServices(): Array<ServiceReference<*>> {
        return bundle.registeredServices
    }

    override fun getServicesInUse(): Array<ServiceReference<*>> {
        return bundle.servicesInUse
    }

    override fun hasPermission(permission: Any?): Boolean {
        return bundle.hasPermission(permission)
    }

    override fun getResource(name: String?): URL {
        return bundle.getResource(name)
    }

    override fun getSymbolicName(): String {
        return Constants.SYSTEM_BUNDLE_SYMBOLICNAME
    }

    override fun loadClass(name: String?): Class<*> {
        return bundle.loadClass(name)
    }

    override fun getResources(name: String?): Enumeration<URL> {
        return bundle.getResources(name)
    }

    override fun getEntryPaths(path: String?): Enumeration<String> {
        return bundle.getEntryPaths(path)
    }

    override fun getEntry(path: String?): URL {
        return bundle.getEntry(path)
    }

    override fun getLastModified(): Long {
        return bundle.lastModified
    }

    override fun findEntries(path: String?, filePattern: String?, recurse: Boolean): Enumeration<URL> {
        return bundle.findEntries(path, filePattern, recurse)
    }

    override fun getBundleContext(): BundleContext {
        return bundle.bundleContext
    }

    override fun getSignerCertificates(signersType: Int): MutableMap<X509Certificate, MutableList<X509Certificate>> {
        return bundle.getSignerCertificates(signersType)
    }

    override fun getVersion(): Version {
        return versionAtomic.get()
    }

    override fun <A : Any?> adapt(type: Class<A>): A {
        return bundle.adapt(type)
    }

    override fun getDataFile(filename: String): File {
        return bundle.getDataFile(filename)
    }



    override fun init() {
    }

    override fun init(vararg listeners: FrameworkListener) {
        listenersAtomic.set(listeners.toList())
    }
    override fun waitForStop(timeout: Long): FrameworkEvent {
        return FrameworkEvent(FrameworkEvent.STOPPED, this, null)
    }
}

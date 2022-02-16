package net.corda.sandboxtests

import java.nio.file.Path
import net.corda.sandbox.SandboxException
import net.corda.testing.sandboxes.SandboxSetup
import net.corda.testing.sandboxes.fetchService
import net.corda.testing.sandboxes.lifecycle.EachTestLifecycle
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.osgi.framework.BundleContext
import org.osgi.test.common.annotation.InjectBundleContext
import org.osgi.test.common.annotation.InjectService
import org.osgi.test.junit5.context.BundleContextExtension
import org.osgi.test.junit5.service.ServiceExtension

/** Tests the inability to resolve against private bundles in a public sandbox. */
@ExtendWith(ServiceExtension::class, BundleContextExtension::class)
class SandboxIrresolvableBundleTest {
    companion object {
        @RegisterExtension
        private val lifecycle = EachTestLifecycle()

        @InjectService(timeout = 1000)
        lateinit var sandboxSetup: SandboxSetup

        lateinit var sandboxFactory: SandboxFactory

        @Suppress("unused")
        @JvmStatic
        @BeforeAll
        fun setup(@InjectBundleContext bundleContext: BundleContext, @TempDir testDirectory: Path) {
            sandboxSetup.configure(bundleContext, testDirectory)
            lifecycle.accept(sandboxSetup) { setup ->
                sandboxFactory = setup.fetchService(timeout = 1000)
            }
        }
    }

    @Test
    fun `bundle cannot resolve against a private bundle in a public sandbox`() {
        val e = assertThrows<SandboxException> {
            sandboxFactory.createSandboxGroupFor("META-INF/sandbox-irresolvable-cpk.cpb")
        }
        assertTrue(e.cause?.message!!.contains("Unable to resolve com.example.sandbox.sandbox-irresolvable-cpk"))
    }
}
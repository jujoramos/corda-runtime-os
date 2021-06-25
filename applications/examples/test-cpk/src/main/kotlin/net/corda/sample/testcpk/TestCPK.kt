package net.corda.sample.testcpk

//import org.slf4j.LoggerFactory

/**
 * Dummy CordApp used in the `:applications:examples:test-sandbox`.
 */
class TestCPK: Runnable {

    companion object {

        //private val logger = LoggerFactory.getLogger(TestCPK::class.java)

    } //~ companion


    init {
        println("net.corda.sample.testcpk.TestCPK INIT.")
        //logger.info("INIT.")
    }


    override fun run() {
        println("net.corda.sample.testcpk.TestCPK RUN.")
        //logger.info("RUN.")
    }
}
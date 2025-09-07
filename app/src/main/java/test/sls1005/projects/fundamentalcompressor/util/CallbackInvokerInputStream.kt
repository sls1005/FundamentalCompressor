package test.sls1005.projects.fundamentalcompressor.util

import java.io.FilterInputStream
import java.io.InputStream

internal class CallbackInvokerInputStream(i: InputStream, initialCount: Int = 0, private val threshold: Int = 5, private val callback: (InputStream) -> Unit): FilterInputStream(i) {
    private var readCounter = initialCount
    override fun read(): Int {
        increaseOrInvoke()
        return super.read()
    }
    override fun read(a: ByteArray, m: Int, n: Int): Int {
        increaseOrInvoke()
        return super.read(a, m, n)
    }
    override fun read(a: ByteArray): Int {
        increaseOrInvoke()
        return super.read(a)
    }
    private fun increaseOrInvoke() {
        if (readCounter >= threshold) {
            readCounter = 0
            callback(`in`)
        } else {
            readCounter += 1
        }
    }
}
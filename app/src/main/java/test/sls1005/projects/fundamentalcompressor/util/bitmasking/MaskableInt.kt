package test.sls1005.projects.fundamentalcompressor.util.bitmasking

@JvmInline
internal final value class MaskableInt(private val n: Int) {
    internal inline fun exactlyMatches(k: Int): Boolean = (n == k)

    internal inline fun bitsSet(k: Int): Boolean = ((n and k) == k)

    internal inline fun flagsSet(vararg flags: Int): Boolean {
        return !flags.any { it != (it and n) }
    }

    internal inline fun bitsNotSet(k: Int): Boolean = ((n and k) == 0) // this is not the opposite of `bitsSet`.

    internal inline fun flagsNotSet(vararg flags: Int): Boolean {
        return !flags.any { (it and n) != 0 }
    }

    internal inline fun withBitsSet(k: Int): MaskableInt = MaskableInt(n or k)

    internal inline fun withBitsUnset(k: Int): MaskableInt = MaskableInt(n and k.inv()) // Use `and inv`. Don't use xor.

    internal inline fun withFlagsSet(vararg flags: Int): MaskableInt {
        var k = n
        for (flag in flags) {
            k = (k or flag)
        }
        return MaskableInt(k)
    }

    internal inline fun withFlagsUnset(vararg flags: Int): MaskableInt {
        var k = n
        for (flag in flags) {
            k = (k and flag.inv())
        }
        return MaskableInt(k)
    }

    internal inline fun matchAllFlags(vararg flags: Int): Boolean {
        var k = 0
        for (flag in flags) {
            if ((n and flag) != flag) {
                return false
            } else {
                k = (k or flag)
            }
        }
        return (n == k)
    }

    internal inline fun matchesAnyOf(vararg flagSets: Int): Boolean = flagSets.any { it == n }

    internal inline fun toInt(): Int = n

    internal inline operator fun contains(flag: Int): Boolean = bitsSet(flag)
}

internal fun maskableIntOf(vararg flags: Int): MaskableInt {
    return MaskableInt(unionOf(*flags))
}

internal fun unionOf(vararg sets: Int): Int {
    var k = 0
    for (s in sets) {
        k = (k or s)
    }
    return k
}

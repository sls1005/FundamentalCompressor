package test.sls1005.projects.fundamentalcompressor.util.functions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

private inline fun getFirstOrLastFewRecords(records: List<String>, first: Boolean = true): Pair<List<String>, Boolean> { // if any omitted, returns true; else, false; if first != true, returns last
    return (records.size < 6).let { notMany ->
        if (notMany) {
            Pair(records, false)
        } else {
            Pair(records.slice(if (first) { 0 .. 4 } else { (records.size - 4) .. (records.size - 1) }), true)
        }
    }
}

private inline fun getFirstFewRecords(records: List<String>): Pair<List<String>, Boolean> {
    return getFirstOrLastFewRecords(records, true)
}

private inline fun getLastFewRecords(records: List<String>): Pair<List<String>, Boolean> {
    return getFirstOrLastFewRecords(records, false)
}

private inline fun omitAfterFirstFewRecordsImpl(records: List<String>, omitted: Boolean): String {
    return records.joinToString("\n").let {
        if (omitted) {
            "$it\n…"
        } else {
            it
        }
    }
}

private inline fun omitBeforeLastFewRecordsImpl(records: List<String>, omitted: Boolean): String {
    return records.joinToString("\n").let {
        if (omitted) {
            "…\n$it"
        } else {
            it
        }
    }
}

internal inline fun omitAfterFirstFewRecords(records: List<String>): String {
    val (firstFewRecords, omitted) = getFirstFewRecords(records)
    return omitAfterFirstFewRecordsImpl(firstFewRecords, omitted)
}

internal inline fun omitBeforeLastFewRecords(records: List<String>): String {
    val (lastFewRecords, omitted) = getLastFewRecords(records)
    return omitBeforeLastFewRecordsImpl(lastFewRecords, omitted)
}

internal suspend fun omitAfterFirstFewRecordsConcurrently(records: List<String>): String {
    val (firstFewRecords, omitted) = getFirstFewRecords(records)
    return withContext(Dispatchers.Default) {
        omitAfterFirstFewRecordsImpl(firstFewRecords, omitted)
    }
}

internal suspend fun omitBeforeLastFewRecordsConcurrently(records: List<String>): String {
    val (lastFewRecords, omitted) = getLastFewRecords(records)
    return withContext(Dispatchers.Default) {
        omitBeforeLastFewRecordsImpl(lastFewRecords, omitted)
    }
}

internal inline fun concatenateSmallNumberOfStrings(vararg strings: String): String { // This is (over-)optimized for concatenating a small number of strings only.
    var n = 0
    for (s in strings) {
        val k = s.length
        if (n > Int.MAX_VALUE - k) {
            break
        } else {
            n += k
        }
    }
    return buildString(n) {
        for (s in strings) {
            append(s)
        }
    }
}

internal suspend fun concatenateSmallNumberOfStringsOnAnotherThread(vararg strings: String): String {
    return withContext(Dispatchers.Default) {
        concatenateSmallNumberOfStrings(*strings)
    }
}

internal inline fun jobIsCompletedOrCancelled(j: Job?): Boolean {
    return j?.let { it.isCompleted || it.isCancelled } ?: false
}

package com.michaeltchuang.walletsdk.core.foundation.utils

inline fun FloatArray.partitionIndexed(predicate: (Int, Float) -> Boolean): Pair<List<Float>, List<Float>> {
    var index = 0
    return partition {
        predicate(index++, it)
    }
}

/**
 * Takes number list and returns Retrofit Query compatible string for array queries
 * @param [1, 12, 123]
 * @return 1,12,123
 */
fun List<Number>.toQueryString(): String = toString().replace(Regex("([^0-9,])"), "")

fun List<*>.toCsvString(): String = joinToString(",")

fun <T> MutableList<T>.popIfOrNull(predicate: (T) -> Boolean): T? {
    val element = firstOrNull { predicate(it) } ?: return null
    remove(element)
    return element
}

fun <T, R> List<T?>.mapToNotNullableListOrNull(transform: (T?) -> R?): List<R>? {
    val safeList = mutableListOf<R>()
    forEach {
        val mappedData = transform(it)
        if (mappedData == null) {
            return null
        } else {
            safeList.add(mappedData)
        }
    }
    return safeList
}

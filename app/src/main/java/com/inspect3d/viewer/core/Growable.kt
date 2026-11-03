package com.inspect3d.viewer.core

/**
 * Minimal primitive-backed growable lists. Parsing a mesh means appending millions of
 * floats, and boxing them into `ArrayList<Float>` would dominate both time and memory.
 */
class FloatList(initialCapacity: Int = 4096) {
    var data = FloatArray(initialCapacity.coerceAtLeast(16))
        private set
    var size = 0
        private set

    fun add(v: Float) {
        ensure(size + 1)
        data[size++] = v
    }

    fun add(a: Float, b: Float, c: Float) {
        ensure(size + 3)
        data[size++] = a
        data[size++] = b
        data[size++] = c
    }

    fun addAll(src: FloatArray, offset: Int, count: Int) {
        ensure(size + count)
        System.arraycopy(src, offset, data, size, count)
        size += count
    }

    operator fun get(index: Int): Float = data[index]

    operator fun set(index: Int, value: Float) {
        data[index] = value
    }

    /** Drops the last [count] elements. */
    fun shrink(count: Int) {
        size = (size - count).coerceAtLeast(0)
    }

    fun ensure(capacity: Int) {
        if (capacity <= data.size) return
        var newCapacity = data.size + (data.size shr 1)
        if (newCapacity < capacity) newCapacity = capacity
        data = data.copyOf(newCapacity)
    }

    /** Copies out exactly [size] elements. */
    fun toArray(): FloatArray = data.copyOf(size)
}

class IntList(initialCapacity: Int = 4096) {
    var data = IntArray(initialCapacity.coerceAtLeast(16))
        private set
    var size = 0
        private set

    fun add(v: Int) {
        ensure(size + 1)
        data[size++] = v
    }

    fun add(a: Int, b: Int, c: Int) {
        ensure(size + 3)
        data[size++] = a
        data[size++] = b
        data[size++] = c
    }

    operator fun get(index: Int): Int = data[index]

    operator fun set(index: Int, value: Int) {
        data[index] = value
    }

    /** Drops the last [count] elements (used to pop work items off a flat stack). */
    fun shrink(count: Int) {
        size = (size - count).coerceAtLeast(0)
    }

    fun ensure(capacity: Int) {
        if (capacity <= data.size) return
        var newCapacity = data.size + (data.size shr 1)
        if (newCapacity < capacity) newCapacity = capacity
        data = data.copyOf(newCapacity)
    }

    fun toArray(): IntArray = data.copyOf(size)
}

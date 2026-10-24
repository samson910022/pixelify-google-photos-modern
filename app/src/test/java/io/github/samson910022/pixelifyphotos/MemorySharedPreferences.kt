package io.github.samson910022.pixelifyphotos

import android.content.SharedPreferences

/** Small deterministic SharedPreferences implementation for store migration tests. */
internal class MemorySharedPreferences(
    initialValues: Map<String, Any?> = emptyMap(),
    var commitResult: Boolean = true,
    /** Models Android's live in-memory map update even when commit() reports disk failure. */
    var commitFalseUpdatesMemory: Boolean = false,
    /** Independently models disk contents when commit() returns false. */
    var commitFalseUpdatesDisk: Boolean = false,
    /** apply() is asynchronous on Android; tests default to its eventual disk state. */
    var applyPersistsImmediately: Boolean = true,
) : SharedPreferences {
    private val values = initialValues.mapValuesTo(mutableMapOf()) { (_, value) -> copyValue(value) }
    private val persistedValues = initialValues.mapValuesTo(mutableMapOf()) { (_, value) -> copyValue(value) }
    var editCount: Int = 0
        private set

    /** Durable snapshot used to model what a newly-created process can observe. */
    val persistedAll: Map<String, Any?>
        get() = persistedValues.mapValues { (_, value) -> copyValue(value) }

    override fun getAll(): MutableMap<String, *> = values.mapValuesTo(mutableMapOf()) { (_, value) -> copyValue(value) }
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues?.toMutableSet()
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor {
        editCount += 1
        return MemoryEditor()
    }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class MemoryEditor : SharedPreferences.Editor {
        private val changed = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = set(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            set(key, values?.toSet())
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = set(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = set(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = set(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = set(key, value)

        private fun set(key: String?, value: Any?): SharedPreferences.Editor {
            requireNotNull(key)
            if (value == null) removed += key else changed[key] = copyValue(value)
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removed += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            if (!commitResult) {
                if (commitFalseUpdatesMemory) applyChanges()
                if (commitFalseUpdatesDisk) persistChanges()
                return false
            }
            applyChanges()
            persistChanges()
            return true
        }

        override fun apply() {
            applyChanges()
            if (applyPersistsImmediately) persistChanges()
        }

        private fun applyChanges() {
            if (clear) values.clear()
            removed.forEach(values::remove)
            changed.forEach { (key, value) -> values[key] = copyValue(value) }
        }

        private fun persistChanges() {
            if (clear) persistedValues.clear()
            removed.forEach(persistedValues::remove)
            changed.forEach { (key, value) -> persistedValues[key] = copyValue(value) }
        }
    }

    private companion object {
        fun copyValue(value: Any?): Any? = if (value is Set<*>) value.toSet() else value
    }
}

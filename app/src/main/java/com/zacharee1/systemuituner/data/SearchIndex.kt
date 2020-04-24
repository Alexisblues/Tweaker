package com.zacharee1.systemuituner.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.preference.*
import com.zacharee1.systemuituner.R
import com.zacharee1.systemuituner.dialogs.RoundedBottomSheetDialog
import com.zacharee1.systemuituner.interfaces.*
import com.zacharee1.systemuituner.util.prefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.ArrayList

/**
 * TODO: Persistent Options were kind of just shoved in here. Clean this up.
 */
@SuppressLint("RestrictedApi")
class SearchIndex private constructor(context: Context) : ContextWrapper(context), CoroutineScope by MainScope() {
    companion object {
        private var instance: SearchIndex? = null

        private val toInflate = arrayOf(
            R.xml.prefs_apps to R.id.appsFragment,
            R.xml.prefs_audio to R.id.audioFragment,
            R.xml.prefs_developer to R.id.developerFragment,
            R.xml.prefs_display to R.id.displayFragment,
            R.xml.prefs_net_cellular to R.id.netCellFragment,
            R.xml.prefs_net_misc to R.id.netMiscellaneousFragment,
            R.xml.prefs_net_wifi to R.id.netWiFiFragment,
            R.xml.prefs_notifications to R.id.notificationsFragment,
            R.xml.prefs_storage to R.id.storageFragment,
            R.xml.prefs_ui to R.id.UIFragment,
            R.xml.prefs_advanced to R.id.advancedFragment
        )

        fun getInstance(context: Context): SearchIndex {
            return instance ?: run {
                SearchIndex(context.applicationContext).apply { instance = this }
            }
        }
    }

    private val preferenceManager = PreferenceManager(this)
    private val preferences = ArrayList<ActionedPreference>()

    private var isLoaded = async {
        toInflate.forEach {
            inflate(it.first, it.second)
        }
        true
    }

    private fun inflate(resource: Int, action: Int): PreferenceScreen {
        return preferenceManager.inflateFromResource(this, resource, null).also { process(it, action) }
    }

    private fun process(group: PreferenceGroup, action: Int) {
        for (i in 0 until group.preferenceCount) {
            val child = group.getPreference(i)

            if (child is PreferenceGroup) process(child, action)
            else preferences.add(ActionedPreference.fromPreference(this, child, action))
        }
    }

    fun filter(query: String?, result: (ArrayList<ActionedPreference>) -> Unit) = launch {
        val lowercase = query?.toLowerCase(Locale.getDefault())

        isLoaded.await()

        val filter = async {
            ArrayList(
                preferences.filter {
                    lowercase == null || lowercase.isBlank() ||
                            it.title.toString().contains(lowercase, true) ||
                            it.summary.toString().contains(lowercase, true)
                }
            )
        }

        result(filter.await())
    }

    fun filterPersistent(query: String?, result: (ArrayList<PersistentPreference>) -> Unit) = launch {
        val lowercase = query?.toLowerCase(Locale.getDefault())

        isLoaded.await()

        val filter = async {
            ArrayList(
                preferences.filter {
                    it.showAsPersistentOption && (lowercase == null || lowercase.isBlank() ||
                            it.title.toString().contains(lowercase, true) ||
                            it.summary.toString().contains(lowercase, true))
                }.map { PersistentPreference.fromPreference(false, it) } +
                        prefManager.customPersistentOptions.filter {
                            lowercase == null || lowercase.isBlank() ||
                                    it.label.contains(lowercase, true) ||
                                    it.key.contains(lowercase, true)
                        }.map {
                            PersistentPreference.fromCustomPersistentOption(this@SearchIndex, it)
                        }
            )
        }

        result(filter.await())
    }

    class PersistentPreference(val isCustom: Boolean, context: Context) : CheckBoxPreference(context), ISecurePreference by SecurePreference(
        context,
        null
    ), IColorPreference by ColorPreference(
        context,
        null
    ) {
        companion object {
            fun fromCustomPersistentOption(context: Context, info: CustomPersistentOption): PersistentPreference {
                return PersistentPreference(true, context).apply {
                    title = info.label
                    key = info.key
                    type = info.type
                    keys.add(key)
                }
            }
            fun fromPreference(isCustom: Boolean, preference: Preference, newContext: Context = preference.context): PersistentPreference {
                return PersistentPreference(isCustom, newContext).apply {
                    title = preference.title
                    icon = preference.icon
                    key = preference.key
                    isVisible = preference.isVisible
                    if (preference is PersistentPreference) {
                        keys.addAll(preference.keys)
                    }
                    if (preference is ISpecificPreference) {
                        keys.addAll(preference.keys)
                    }
                    if (preference is ISecurePreference) {
                        dangerous = preference.dangerous
                        type = preference.type

                        if (keys.isEmpty()) {
                            keys.add(preference.key)
                        }
                    }
                    if (preference is IColorPreference) {
                        iconColor = preference.iconColor
                    }
                }
            }

            fun copy(preference: PersistentPreference, newContext: Context = preference.context): PersistentPreference {
                return fromPreference(preference.isCustom, preference, newContext)
            }
        }

        init {
            isPersistent = false
            layoutResource = R.layout.custom_preference
            widgetLayoutResource = R.layout.checkbox
        }

        val keys: ArrayList<String> = ArrayList()

        override var dangerous: Boolean = false
            set(value) {
                field = value
                markDangerous()
            }

        override fun isPersistent(): Boolean {
            return false
        }

        override fun compareTo(other: Preference): Int {
            val sup = super.compareTo(other)

            return if (other is TwoStatePreference) {
                if (isChecked && !other.isChecked) -1
                else if (isChecked && other.isChecked) sup
                else if (!isChecked && other.isChecked) 1
                else sup
            } else sup
        }

        override fun onAttachedToHierarchy(preferenceManager: PreferenceManager?) {
            super.onAttachedToHierarchy(preferenceManager)

            if (isCustom) {
                summary = context.prefManager.customPersistentOptions.find { it.type == type && it.key == key }?.run {
                    context.resources.getString(R.string.custom_persistent_option_summary_template, type, key, value)
                }
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)

            if (isCustom) {
                holder.itemView.apply {
                    setOnLongClickListener {
                        RoundedBottomSheetDialog(context).apply {
                            setTitle(R.string.remove_item)
                            setMessage(R.string.remove_item_desc)
                            setPositiveButton(android.R.string.ok, DialogInterface.OnClickListener { _, _ ->
                                context.prefManager.apply {
                                    persistentOptions = persistentOptions.apply {
                                        removeAll { it.type == type && it.key == key }
                                    }
                                    customPersistentOptions = customPersistentOptions.apply {
                                        removeAll { it.type == type && it.key == key }
                                    }
                                }
                                dismiss()
                            })
                            setNegativeButton(android.R.string.cancel, null)
                        }.show()
                        true
                    }
                }
            }

            bindVH(holder)
        }

        fun copy(): PersistentPreference {
            return fromPreference(isCustom,this)
        }

        fun markDangerous() {
            title = if (dangerous) {
                SpannableString(title).apply {
                    setSpan(ForegroundColorSpan(Color.RED), 0, length, 0)
                }
            } else {
                title.toString()
            }
        }
    }

    class ActionedPreference(context: Context) : Preference(context), ISecurePreference by SecurePreference(
        context,
        null
    ), ISpecificPreference, IColorPreference by ColorPreference(
        context,
        null
    ) {
        companion object {
            fun fromPreference(context: Context, preference: Preference, action: Int): ActionedPreference {
                return ActionedPreference(context).apply {
                    title = preference.title
                    summary = preference.summary
                    icon = preference.icon
                    key = preference.key
                    isVisible = preference.isVisible
                    if (preference is ISecurePreference) {
                        dangerous = preference.dangerous
                        type = preference.type
                    }
                    if (preference is ISpecificPreference) {
                        _keys.addAll(preference.keys)
                    }
                    if (preference is IColorPreference) {
                        iconColor = preference.iconColor
                    }
                    showAsPersistentOption = preference !is INoPersistPreference
                    this.action = action
                }
            }

            fun copy(context: Context, preference: ActionedPreference): ActionedPreference {
                return fromPreference(context, preference, preference.action)
            }
        }

        val _keys = ArrayList<String>()

        override val keys: Array<String>
            get() = _keys.toTypedArray()

        var action: Int = 0

        override var dangerous: Boolean = false
            set(value) {
                field = value
                markDangerous()
            }

        var showAsPersistentOption = true

        init {
            layoutResource = R.layout.custom_preference
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)

            bindVH(holder)
        }

        fun copy(): ActionedPreference {
            return fromPreference(context, this, action)
        }

        fun markDangerous() {
            title = if (dangerous) {
                SpannableString(title).apply {
                    setSpan(ForegroundColorSpan(Color.RED), 0, length, 0)
                }
            } else {
                title.toString()
            }
        }
    }
}
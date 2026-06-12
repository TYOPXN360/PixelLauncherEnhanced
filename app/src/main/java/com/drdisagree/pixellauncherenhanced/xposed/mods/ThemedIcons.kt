package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_THEMED_ICONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FORCE_THEMED_ICONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadIcons
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.MonochromeIconFactory
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class ThemedIcons(context: Context) : ModPack(context) {

    private var forceThemedIcons: Boolean = false
    private var appDrawerThemedIcons: Boolean = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            forceThemedIcons = getBoolean(FORCE_THEMED_ICONS, false)
            appDrawerThemedIcons = getBoolean(APP_DRAWER_THEMED_ICONS, false)
        }

        when (key.firstOrNull()) {
            in setOf(
                FORCE_THEMED_ICONS,
                APP_DRAWER_THEMED_ICONS
            ) -> reloadIcons()
        }
    }

    @SuppressLint("DiscouragedApi")
    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        try {
            // Only for modified Launcher3
            if (mContext.packageName != LAUNCHER3_PACKAGE) throw Throwable()

            val launcherIconsClass = findClass("com.android.launcher3.icons.LauncherIcons")

            launcherIconsClass
                .hookMethod("getMonochromeDrawable")
                .throwError() // Available only in modified launcher3
                .runAfter { param ->
                    if (param.result == null && forceThemedIcons) {
                        val mIconBitmapSize = param.thisObject.getField("mIconBitmapSize") as Int

                        param.result = MonochromeIconFactory(mIconBitmapSize, true)
                            .wrap(mContext, param.args[0] as Drawable)
                    }
                }
        } catch (_: Throwable) {
            val baseIconFactoryClass = findClass("com.android.launcher3.icons.BaseIconFactory")

            baseIconFactoryClass
                .hookConstructor()
                .runAfter { param ->
                    val mIconBitmapSize = param.thisObject.getAnyField(
                        "mIconBitmapSize",
                        "iconBitmapSize"
                    ) as Int
                    val monochromeIconFactory = MonochromeIconFactory(mIconBitmapSize, false)

                    AdaptiveIconDrawable::class.java
                        .hookMethod("getMonochrome")
                        .runAfter runAfter2@{ param2 ->
                            if (param2.result == null && forceThemedIcons) {
                                // If it's from com.android.launcher3.icons.IconProvider class and
                                // mentioned methods, monochrome is already included
                                Thread.currentThread().stackTrace.firstOrNull {
                                    it.className.contains("IconProvider") && it.methodName in listOf(
                                        "getIconWithOverrides",
                                        "getIcon"
                                    )
                                }?.let { return@runAfter2 }

                                var monochromeIcon = param2.thisObject
                                    .getExtraFieldSilently("mMonochromeIcon") as? Drawable

                                if (monochromeIcon == null) {
                                    monochromeIcon = monochromeIconFactory.wrap(
                                        mContext,
                                        param2.thisObject as Drawable
                                    )
                                    setAdditionalInstanceField(
                                        param2.thisObject,
                                        "mMonochromeIcon",
                                        monochromeIcon
                                    )
                                }

                                param2.result = monochromeIcon
                            }
                        }
                }
        }

        val bubbleTextViewClass = findClass("com.android.launcher3.BubbleTextView")
        val themesClass = findClass("com.android.launcher3.util.Themes")
        val themeManagerClass = findClass(
            "com.android.launcher3.graphics.ThemeManager",
            suppressError = true
        )
        val themePreferenceClass = findClass(
            "com.android.launcher3.graphics.theme.ThemePreference",
            suppressError = true
        )
        val intArrayClass = findClass(
            "com.android.launcher3.util.IntArray",
            suppressError = true
        )

        // Hook shouldUseTheme if it exists (old launcher versions)
        bubbleTextViewClass
            .hookMethod("shouldUseTheme")
            .suppressError()
            .runAfter { param ->
                if (param.result == false && appDrawerThemedIcons) {
                    val context = param.thisObject.callMethod("getContext") as Context
                    val mDisplay = param.thisObject.getField("mDisplay") as Int

                    param.result = mDisplay.shouldUseTheme(
                        context,
                        themesClass,
                        themeManagerClass,
                        themePreferenceClass
                    )
                }
            }

        // Hook ThemeManager.isIconThemeEnabled() for new launcher versions
        // This ensures themed icons work when our setting is enabled
        themeManagerClass?.let { clazz ->
            clazz.hookMethod("isIconThemeEnabled")
                .suppressError()
                .runAfter { param ->
                    if (appDrawerThemedIcons && param.result == false) {
                        log("[ThemedIcons] isIconThemeEnabled: was false, setting to true")
                        param.result = true
                    }
                }
        }

        val monoIconThemeControllerClass = findClass(
            "com.android.launcher3.icons.mono.MonoIconThemeController",
            suppressError = true
        )

        // Hook ThemeManager.verifyIconState() to inject MonoIconThemeController
        // into iconState.themeController when it's null
        if (themeManagerClass != null && monoIconThemeControllerClass != null) {
            var monoControllerCache: Any? = null

            fun getOrCreateController(): Any {
                return monoControllerCache ?: monoIconThemeControllerClass.getConstructor().newInstance().also {
                    monoControllerCache = it
                }
            }

            themeManagerClass.hookMethod("verifyIconState")
                .suppressError()
                .runAfter { param ->
                    if (!appDrawerThemedIcons) return@runAfter

                    val thisObj = param.thisObject
                    val iconState = thisObj.getFieldSilently("iconState") ?: return@runAfter
                    val themeController = iconState.getFieldSilently("themeController")
                    log("[ThemedIcons] verifyIconState: themeController=${themeController != null}")
                    if (themeController == null) {
                        de.robv.android.xposed.XposedHelpers.setObjectField(
                            iconState, "themeController", getOrCreateController()
                        )
                        log("[ThemedIcons] verifyIconState: injected MonoIconThemeController")
                    }
                }
        }

        // Also hook BaseIconFactory constructor as fallback - use XposedHelpers directly
        // for final field injection which is more reliable across Android versions
        val baseIconFactoryClass = findClass(
            "com.android.launcher3.icons.BaseIconFactory",
            suppressError = true
        )

        if (baseIconFactoryClass != null && monoIconThemeControllerClass != null) {
            var monoControllerCache2: Any? = null

            fun getOrCreateController2(): Any {
                return monoControllerCache2 ?: monoIconThemeControllerClass.getConstructor().newInstance().also {
                    monoControllerCache2 = it
                }
            }

            baseIconFactoryClass.hookConstructor()
                .suppressError()
                .runAfter { param ->
                    if (!appDrawerThemedIcons) return@runAfter

                    val themeController = param.thisObject.getFieldSilently("themeController")
                    log("[ThemedIcons] BaseIconFactory: themeController=${themeController != null}")
                    if (themeController == null) {
                        de.robv.android.xposed.XposedHelpers.setObjectField(
                            param.thisObject, "themeController", getOrCreateController2()
                        )
                        log("[ThemedIcons] BaseIconFactory: injected MonoIconThemeController")
                    }
                }
        }
        // When themedBitmap is NOT_SUPPORTED but our setting is enabled,
        // create MonoThemedBitmap using our MonochromeIconFactory
        val bitmapInfoClass = findClass(
            "com.android.launcher3.icons.BitmapInfo",
            suppressError = true
        )

        if (bitmapInfoClass != null) {
            bitmapInfoClass.hookMethod("newIcon")
                .suppressError()
                .runBefore { param ->
                    if (!appDrawerThemedIcons) return@runBefore

                    val flags = param.args[1] as? Int ?: return@runBefore
                    if ((flags and FLAG_THEMED) == 0) return@runBefore

                    val themedBitmap = param.thisObject.getFieldSilently("themedBitmap")
                    log("[ThemedIcons] newIcon: flags=$flags, themedBitmap=${themedBitmap?.javaClass?.simpleName}")
                    if (themedBitmap == null || themedBitmap.javaClass.simpleName == "NOT_SUPPORTED") {
                        val icon = param.thisObject.getFieldSilently("icon") as? android.graphics.Bitmap
                        if (icon != null) {
                            try {
                                // Convert hardware bitmap to software bitmap
                                val softwareIcon = icon.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                // Use our MonochromeIconFactory to create monochrome drawable
                                val monoDrawable = MonochromeIconFactory(softwareIcon.width, false)
                                    .wrap(mContext, android.graphics.drawable.BitmapDrawable(mContext.resources, softwareIcon))

                                // Get the monochrome bitmap from the drawable
                                val monoBitmap = (monoDrawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                                    ?.copy(android.graphics.Bitmap.Config.ALPHA_8, false)

                                if (monoBitmap != null) {
                                    // Get color provider from ThemedIconDelegate.Companion
                                    val themedDelegateCompanion = findClass(
                                        "com.android.launcher3.icons.mono.ThemedIconDelegate\$Companion",
                                        suppressError = true
                                    )
                                    val colorProvider = themedDelegateCompanion?.getStaticFieldSilently("INSTANCE")

                                    // Create MonoThemedBitmap
                                    val monoThemedBitmapClass = findClass(
                                        "com.android.launcher3.icons.mono.MonoThemedBitmap",
                                        suppressError = true
                                    )
                                    val cInterface = findClass("ta.c", suppressError = true)

                                    if (monoThemedBitmapClass != null && cInterface != null && colorProvider != null) {
                                        val newThemedBitmap = monoThemedBitmapClass
                                            .getConstructor(android.graphics.Bitmap::class.java, cInterface, Double::class.javaPrimitiveType)
                                            .newInstance(monoBitmap, colorProvider, null)

                                        de.robv.android.xposed.XposedHelpers.setObjectField(
                                            param.thisObject, "themedBitmap", newThemedBitmap
                                        )
                                        log("[ThemedIcons] Created MonoThemedBitmap from monochrome icon")
                                    }
                                }
                            } catch (e: Throwable) {
                                log("[ThemedIcons] Failed: ${e.message}")
                            }
                        }
                    }
                }
        }

        bubbleTextViewClass
            .hookMethod("applyIconAndLabel")
            .parameters("com.android.launcher3.model.data.ItemInfoWithIcon")
            .runBefore { param ->
                if (!appDrawerThemedIcons) return@runBefore

                val info = param.args[0]
                val context = param.thisObject.callMethod("getContext") as Context
                val mDisplay = param.thisObject.getField("mDisplay") as Int
                val mHideBadge = param.thisObject.getField("mHideBadge") as Boolean
                val mSkipUserBadge = param.thisObject.getField("mSkipUserBadge") as Boolean
                val shouldUseTheme = mDisplay.shouldUseTheme(
                    context,
                    themesClass,
                    themeManagerClass,
                    themePreferenceClass
                )

                val appName = info.getFieldSilently("title") ?: "unknown"
                log("[ThemedIcons] applyIconAndLabel: appName=$appName, shouldUseTheme=$shouldUseTheme, mDisplay=$mDisplay")

                var flags = if (shouldUseTheme) FLAG_THEMED else 0

                // Remove badge on icons smaller than 48dp.
                if (mHideBadge || mDisplay == DISPLAY_SEARCH_RESULT_SMALL) {
                    flags = flags or FLAG_NO_BADGE
                }
                if (mSkipUserBadge) {
                    flags = flags or FLAG_SKIP_USER_BADGE
                }

                val hasNewIconWithContextFirst = info.hasMethod(
                    "newIcon",
                    Context::class.java,
                    Int::class.javaPrimitiveType
                )
                val iconDrawable = if (hasNewIconWithContextFirst) {
                    info.callMethod("newIcon", context, flags)
                } else {
                    info.callMethod("newIcon", flags, context)
                }
                val mDotParams = param.thisObject.getField("mDotParams")

                mDotParams.setFieldSilently(
                    "appColor",
                    iconDrawable.callMethodSilently("getIconColor")
                )
                val notificationDotColorAttr = context.resources.getIdentifier(
                    "notificationDotColor",
                    "attr",
                    context.packageName
                )
                mDotParams.setAnyField(
                    if (notificationDotColorAttr != 0) {
                        LauncherUtils.getAttrColor(context, notificationDotColorAttr)
                    } else {
                        context.resources.getColor(
                            context.resources.getIdentifier(
                                "system_accent3_200",
                                "color",
                                context.packageName
                            ),
                            context.theme
                        )
                    },
                    "dotColor",
                    "mDotColor"
                )

                param.thisObject.callMethod("setIcon", iconDrawable)

                try {
                    param.thisObject.callMethod("applyLabel", info)
                } catch (_: Throwable) { // method is nuked by R8 :)
                    val label = info.getFieldSilently("title") as? CharSequence

                    if (label != null) {
                        param.thisObject.setField("mLastOriginalText", label)
                        param.thisObject.setField("mLastModifiedText", label)

                        val stringMatcher = bubbleTextViewClass.getStaticField("MATCHER")
                        val inputLength = label.length
                        val listOfBreakPoints = intArrayClass!!
                            .getDeclaredConstructor()
                            .newInstance()

                        val mBreakPointsIntArray = if (inputLength > 2 &&
                            TextUtils.indexOf(label, ' ') == -1
                        ) {
                            var prevType =
                                Character.getType(Character.codePointAt(label, 0))
                            var thisType =
                                Character.getType(Character.codePointAt(label, 1))

                            for (i in 1 until inputLength) {
                                val nextType = if (i < inputLength - 1) {
                                    Character.getType(Character.codePointAt(label, i + 1))
                                } else {
                                    0
                                }

                                if (stringMatcher.callMethod(
                                        "isBreak",
                                        thisType,
                                        prevType,
                                        nextType
                                    ) as Boolean
                                ) {
                                    listOfBreakPoints.callMethod("add", i - 1)
                                }

                                prevType = thisType
                                thisType = nextType
                            }

                            listOfBreakPoints
                        } else {
                            val spaceIndices = IntArray(inputLength) { it }
                                .filter { label[it] == ' ' }

                            for (index in spaceIndices) {
                                listOfBreakPoints.callMethod("add", index)
                            }

                            listOfBreakPoints
                        }

                        param.thisObject.setField("mBreakPointsIntArray", mBreakPointsIntArray)
                        param.thisObject.callMethod("setText", label)
                    }

                    if (info.getFieldSilently("contentDescription") != null) {
                        val charSequence = if (info.callMethod("isDisabled") as Boolean) {
                            context.getString(
                                context.resources.getIdentifier(
                                    "disabled_app_label",
                                    "string",
                                    mContext.packageName
                                ),
                                info.getField("contentDescription")
                            )
                        } else {
                            info.getField("contentDescription")
                        }

                        param.thisObject.callMethod("setContentDescription", charSequence)
                    }
                }

                param.result = null
            }
    }

    private fun Int.shouldUseTheme(
        context: Context,
        themesClass: Class<*>?,
        themeManagerClass: Class<*>?,
        themePreferenceClass: Class<*>?
    ) = this in setOf(
        DISPLAY_WORKSPACE,
        DISPLAY_ALL_APPS,
        DISPLAY_FOLDER,
        DISPLAY_TASKBAR,
        DISPLAY_SEARCH_RESULT,
        DISPLAY_SEARCH_RESULT_SMALL,
        DISPLAY_PREDICTION_ROW,
        DISPLAY_SEARCH_RESULT_APP_ROW
    ) && try {
        // New launcher version: ThemeManager.isIconThemeEnabled()
        themeManagerClass
            ?.getStaticFieldSilently("INSTANCE")
            ?.callMethodSilently("get", context)
            ?.callMethodSilently("isIconThemeEnabled") as? Boolean == true
    } catch (_: Throwable) {
        try {
            // Old launcher version: Themes.isThemedIconEnabled(context)
            themesClass.callStaticMethod("isThemedIconEnabled", context)
        } catch (_: Throwable) {
            try {
                // Older launcher version: ThemeManager.isMonoThemeEnabled()
                themeManagerClass
                    .getStaticField("INSTANCE")
                    .callMethod("get", context)
                    .callMethod("isMonoThemeEnabled")
            } catch (_: Throwable) {
                // Oldest launcher version: ThemePreference check
                themePreferenceClass?.getStaticFieldSilently("MONO_THEME_VALUE") == themeManagerClass
                    ?.getStaticFieldSilently("INSTANCE")
                    ?.callMethodSilently("get", context)
                    ?.getFieldSilently("themePreference")
                    ?.callMethodSilently("getValue")
            }
        }
    } as Boolean

    companion object {
        const val FLAG_THEMED: Int = 1 shl 0
        const val FLAG_NO_BADGE: Int = 1 shl 1
        const val FLAG_SKIP_USER_BADGE: Int = 1 shl 2

        const val DISPLAY_WORKSPACE: Int = 0
        const val DISPLAY_ALL_APPS: Int = 1
        const val DISPLAY_FOLDER: Int = 2
        const val DISPLAY_TASKBAR: Int = 5
        const val DISPLAY_SEARCH_RESULT: Int = 6
        const val DISPLAY_SEARCH_RESULT_SMALL: Int = 7
        const val DISPLAY_PREDICTION_ROW: Int = 8
        const val DISPLAY_SEARCH_RESULT_APP_ROW: Int = 9
    }
}
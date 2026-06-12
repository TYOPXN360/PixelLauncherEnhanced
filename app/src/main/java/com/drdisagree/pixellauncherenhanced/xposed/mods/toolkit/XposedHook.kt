package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import com.drdisagree.pixellauncherenhanced.xposed.PLEnhancedModule.Companion.module
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.Helpers.toPx
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.regex.Pattern

private val UNINITIALIZED = Any()

class HookParam(private val chain: XposedInterface.Chain) {
    val thisObject: Any? get() = chain.thisObject
    val args: MutableList<Any?> = chain.args.toMutableList()
    var result: Any? = UNINITIALIZED
    var skipProceed: Boolean = false
}

class HookCallback(
    private val before: ((HookParam) -> Unit)? = null,
    private val after: ((HookParam) -> Unit)? = null,
    private val replace: ((HookParam) -> Unit)? = null
) {
    fun toInterceptor(): XposedInterface.Hooker {
        return XposedInterface.Hooker { chain ->
            if (replace != null) {
                val hookParam = HookParam(chain)
                replace.invoke(hookParam)
                hookParam.result
            } else {
                val hookParam = HookParam(chain)
                if (before != null) {
                    before.invoke(hookParam)
                }
                if (hookParam.skipProceed || hookParam.result !== UNINITIALIZED) {
                    hookParam.result
                } else {
                    val proceedResult = chain.proceed(hookParam.args.toTypedArray())
                    hookParam.result = proceedResult
                    if (after != null) {
                        after.invoke(hookParam)
                    }
                    hookParam.result
                }
            }
        }
    }
}

class XposedHook {
    companion object {
        var classLoader: ClassLoader? = null

        fun findClass(
            vararg classNames: String,
            suppressError: Boolean = false,
            throwException: Boolean = false
        ): Class<*>? {
            val loader = classLoader
                ?: throw IllegalStateException("XposedHook.classLoader must be set before XposedHook.findClass()")

            for (className in classNames) {
                try {
                    val clazz = Class.forName(className, false, loader)
                    if (clazz != null) return clazz
                } catch (_: ClassNotFoundException) {
                }
            }

            if (throwException) {
                if (classNames.size == 1) {
                    throw Throwable("Class not found: ${classNames[0]}")
                } else {
                    throw Throwable("None of the classes were found: ${classNames.joinToString()}")
                }
            } else if (!suppressError) {
                if (classNames.size == 1) {
                    log("Class not found: ${classNames[0]}")
                } else {
                    log("None of the classes were found: ${classNames.joinToString()}")
                }
            }

            return null
        }

        fun Class<*>?.newInstance(): Any? {
            if (this == null) return null
            return try {
                getDeclaredConstructor().newInstance()
            } catch (_: Throwable) {
                null
            }
        }
    }
}

fun Class<*>?.hookMethod(vararg methodNames: String): MethodHookHelper {
    return MethodHookHelper(this, methodNames)
}

fun Class<*>?.hookConstructor(): MethodHookHelper {
    return MethodHookHelper(this)
}

fun Class<*>?.hookMethodMatchPattern(methodNamePattern: String): MethodHookHelper {
    return MethodHookHelper(this, arrayOf(methodNamePattern), true)
}

class MethodHookHelper(
    private val clazz: Class<*>?,
    private val methodNames: Array<out String>? = null,
    private val isPattern: Boolean = false,
    private val method: Method? = null
) {

    constructor(
        clazz: Class<*>?,
        methodNames: Array<out String>? = null,
        isPattern: Boolean = false
    ) : this(clazz, methodNames, isPattern, null)

    constructor(
        method: Method
    ) : this(null, null, false, method)

    private var parameterTypes: Array<Any?>? = null
    private var printError: Boolean = true
    private var throwError: Boolean = false

    @Suppress("UNCHECKED_CAST")
    fun parameters(vararg parameterTypes: Any?): MethodHookHelper {
        this.parameterTypes = parameterTypes as Array<Any?>?
        return this
    }

    fun run(callback: HookCallback): MethodHookHelper {
        val interceptor = callback.toInterceptor()
        if (method != null) {
            hookMethodInternal(method, interceptor)
        } else if (methodNames.isNullOrEmpty()) {
            hookConstructorInternal(interceptor)
        } else {
            var foundAnyMethod = false
            methodNames.forEach { methodName ->
                if (isPattern) {
                    val pattern = Pattern.compile(methodName)
                    clazz?.declaredMethods?.toList()?.union(clazz.methods.toList())
                        ?.forEach { m ->
                            if (pattern.matcher(m.name).matches()) {
                                hookMethodInternal(m, interceptor)
                                foundAnyMethod = true
                            }
                        }
                } else {
                    if (!parameterTypes.isNullOrEmpty()) {
                        val resolvedTypes = resolveParamTypes(parameterTypes!!)
                        val m = try { clazz?.getDeclaredMethod(methodName, *resolvedTypes) } catch (_: Throwable) { null }
                            ?: try { clazz?.getMethod(methodName, *resolvedTypes) } catch (_: Throwable) { null }
                        if (m != null) {
                            hookMethodInternal(m, interceptor)
                            foundAnyMethod = true
                        }
                    } else {
                        clazz?.declaredMethods?.toList()?.union(clazz.methods.toList())
                            ?.find { it.name == methodName }?.let { m ->
                                hookMethodInternal(m, interceptor)
                                foundAnyMethod = true
                            }
                    }
                }
            }
            if (!foundAnyMethod) {
                val errorMessage =
                    "Method${if (methodNames.size == 1) "" else "s"} not found: ${methodNames.joinToString()} in class ${clazz?.simpleName}"
                if (printError && clazz != null) {
                    log(MethodHookHelper::class.simpleName.toString(), errorMessage)
                } else if (throwError) {
                    throw Throwable(errorMessage)
                }
            }
        }
        return this
    }

    fun runBefore(callback: (HookParam) -> Unit): MethodHookHelper {
        return run(HookCallback(before = callback))
    }

    fun runAfter(callback: (HookParam) -> Unit): MethodHookHelper {
        return run(HookCallback(after = callback))
    }

    fun runBeforeAndAfter(
        beforeCallback: (HookParam) -> Unit,
        afterCallback: (HookParam) -> Unit
    ): MethodHookHelper {
        return run(HookCallback(before = beforeCallback, after = afterCallback))
    }

    fun replace(callback: (HookParam) -> Unit): MethodHookHelper {
        return run(HookCallback(replace = callback))
    }

    private fun hookMethodInternal(method: Method, interceptor: XposedInterface.Hooker) {
        try {
            module?.hook(method)?.intercept(interceptor)
        } catch (t: Throwable) {
            if (printError) {
                log(MethodHookHelper::class.simpleName.toString(), "Failed to hook ${method.name}: $t")
            } else if (throwError) {
                throw t
            }
        }
    }

    private fun hookConstructorInternal(interceptor: XposedInterface.Hooker) {
        if (clazz == null) return
        try {
            if (parameterTypes.isNullOrEmpty()) {
                clazz.declaredConstructors.forEach { ctor ->
                    module?.hook(ctor)?.intercept(interceptor)
                }
            } else {
                val resolvedTypes = resolveParamTypes(parameterTypes!!)
                val ctor = clazz.getDeclaredConstructor(*resolvedTypes)
                module?.hook(ctor)?.intercept(interceptor)
            }
        } catch (t: Throwable) {
            if (printError) {
                log(MethodHookHelper::class.simpleName.toString(), "Failed to hook constructor in ${clazz.simpleName}: $t")
            } else if (throwError) {
                throw t
            }
        }
    }

    fun suppressError(): MethodHookHelper {
        printError = false
        return this
    }

    fun throwError(): MethodHookHelper {
        suppressError()
        throwError = true
        return this
    }
}

fun Method.run(callback: HookCallback): MethodHookHelper {
    return MethodHookHelper(this).run(callback)
}

fun Method.runBefore(callback: (HookParam) -> Unit): MethodHookHelper {
    return MethodHookHelper(this).runBefore(callback)
}

fun Method.runAfter(callback: (HookParam) -> Unit): MethodHookHelper {
    return MethodHookHelper(this).runAfter(callback)
}

fun Method.replace(callback: (HookParam) -> Unit): MethodHookHelper {
    return MethodHookHelper(this).replace(callback)
}

private fun resolveParamTypes(parameterTypes: Array<Any?>): Array<Class<*>> {
    return parameterTypes.map { type ->
        when (type) {
            is Class<*> -> type
            is String -> {
                when (type) {
                    "int" -> Int::class.javaPrimitiveType!!
                    "long" -> Long::class.javaPrimitiveType!!
                    "float" -> Float::class.javaPrimitiveType!!
                    "double" -> Double::class.javaPrimitiveType!!
                    "boolean" -> Boolean::class.javaPrimitiveType!!
                    "byte" -> Byte::class.javaPrimitiveType!!
                    "short" -> Short::class.javaPrimitiveType!!
                    "char" -> Char::class.javaPrimitiveType!!
                    "void" -> Void::class.javaPrimitiveType!!
                    else -> Class.forName(type, false, XposedHook.classLoader ?: ClassLoader.getSystemClassLoader())
                }
            }
            else -> throw IllegalArgumentException("Invalid parameter type: $type")
        }
    }.toTypedArray()
}

object ResourceHookManager {

    private val hookedResources = mutableListOf<HookData>()
    private var contextRef: WeakReference<Context>? = null
    private var hooksApplied = false

    fun init(context: Context) {
        contextRef = WeakReference(context)
        applyHooks()
    }

    fun hookDimen(): HookBuilder {
        return HookBuilder(HookType.DIMENSION)
    }

    fun hookBoolean(): HookBuilder {
        return HookBuilder(HookType.BOOLEAN)
    }

    fun hookInteger(): HookBuilder {
        return HookBuilder(HookType.INTEGER)
    }

    fun hookColor(): HookBuilder {
        return HookBuilder(HookType.COLOR)
    }

    private fun applyHooks() {
        if (hooksApplied) return
        hooksApplied = true
        val context = contextRef!!.get() ?: throw IllegalStateException("Context is null")

        HookType.entries.forEach { hookType ->
            hookType.methods.forEach { method ->
                try {
                    val m = Resources::class.java.getDeclaredMethod(method, Int::class.javaPrimitiveType)
                    module?.hook(m)?.intercept { chain ->
                        val resId = chain.args[0] as Int
                        val hookData = hookedResources.find {
                            it.method == method && it.resId == resId && it.condition.invoke()
                        } ?: return@intercept chain.proceed()

                        if (method == "getDimensionPixelSize" || method == "getDimension" || method == "getDimensionPixelOffset") {
                            context.toPx(hookData.value.invoke() as Int)
                        } else {
                            hookData.value.invoke()
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    class HookBuilder(private val hookType: HookType) {

        private var packageName: String? = null
        private var condition: () -> Boolean = { true }
        private val resourcesToHook = mutableListOf<HookData>()

        fun whenCondition(condition: () -> Boolean): HookBuilder {
            this.condition = condition
            return this
        }

        fun forPackageName(packageName: String): HookBuilder {
            this.packageName = packageName
            return this
        }

        @SuppressLint("DiscouragedApi")
        fun addResource(name: String, value: () -> Any): HookBuilder {
            val context = contextRef?.get() ?: return this
            if (packageName == null) throw IllegalArgumentException("packageName must be set")

            val resId = context.resources.getIdentifier(
                name,
                hookType.resourceType,
                packageName
            )

            if (resId != 0) {
                hookType.methods.forEach { method ->
                    resourcesToHook.add(HookData(resId, method, value, condition))
                }
            }

            return this
        }

        fun apply() {
            resourcesToHook.forEach { resource ->
                if (!hookedResources.contains(resource)) {
                    hookedResources.add(resource)
                }
            }
        }
    }

    data class HookData(
        val resId: Int,
        val method: String,
        val value: () -> Any,
        val condition: () -> Boolean
    )

    enum class HookType(val resourceType: String, val methods: List<String>) {
        BOOLEAN("bool", listOf("getBoolean")),
        INTEGER("integer", listOf("getInteger")),
        DIMENSION("dimen", listOf("getDimension", "getDimensionPixelOffset", "getDimensionPixelSize")),
        COLOR("color", listOf("getColor"))
    }
}

fun Any?.callMethod(methodName: String): Any? {
    if (this == null) {
        log("callMethod:noArgs", "Object is null, method=$methodName")
        return null
    }
    return try {
        val m = findMethod(this.javaClass, methodName, 0)
        m.isAccessible = true
        m.invoke(this)
    } catch (t: Throwable) {
        log("callMethod:noArgs", "Error calling $methodName: $t")
        null
    }
}

fun Any?.callMethod(methodName: String, vararg args: Any?): Any? {
    if (this == null) {
        log("callMethod:withArgs", "Object is null, method=$methodName, args=${args.size}")
        return null
    }
    return try {
        val m = findMethod(this.javaClass, methodName, args.size)
        m.isAccessible = true
        m.invoke(this, *args)
    } catch (t: Throwable) {
        log("callMethod:withArgs", "Error calling $methodName: $t")
        null
    }
}

fun Any?.callMethod(methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? {
    if (this == null) {
        log("callMethod:withArgs", "Object is null, method=$methodName, args=${args.size}")
        return null
    }
    return try {
        val m = this.javaClass.getDeclaredMethod(methodName, *parameterTypes)
        m.isAccessible = true
        m.invoke(this, *args)
    } catch (t: Throwable) {
        log("callMethod:withArgs", "Error calling $methodName: $t")
        null
    }
}

fun Any?.callMethodSilently(methodName: String): Any? {
    if (this == null) return null
    return try {
        val m = findMethod(this.javaClass, methodName, 0)
        m.isAccessible = true
        m.invoke(this)
    } catch (_: Throwable) {
        null
    }
}

fun Any?.callMethodSilently(methodName: String, vararg args: Any?): Any? {
    if (this == null) return null
    return try {
        val m = findMethod(this.javaClass, methodName, args.size)
        m.isAccessible = true
        m.invoke(this, *args)
    } catch (_: Throwable) {
        null
    }
}

fun Any?.callMethodSilently(
    methodName: String,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?
): Any? {
    if (this == null) return null
    return try {
        val m = this.javaClass.getDeclaredMethod(methodName, *parameterTypes)
        m.isAccessible = true
        m.invoke(this, *args)
    } catch (_: Throwable) {
        null
    }
}

fun Class<*>?.callStaticMethod(methodName: String): Any? {
    if (this == null) {
        log("callStaticMethod:noArgs", "Class is null, method=$methodName")
        return null
    }
    return try {
        val m = findMethod(this, methodName, 0)
        m.isAccessible = true
        m.invoke(null)
    } catch (t: Throwable) {
        log("callStaticMethod:noArgs", "Error calling $methodName: $t")
        null
    }
}

fun Class<*>?.callStaticMethod(methodName: String, vararg args: Any?): Any? {
    if (this == null) {
        log("callStaticMethod:withArgs", "Class is null, method=$methodName, args=${args.size}")
        return null
    }
    return try {
        val m = findMethod(this, methodName, args.size)
        m.isAccessible = true
        m.invoke(null, *args)
    } catch (t: Throwable) {
        log("callStaticMethod:withArgs", "Error calling $methodName: $t")
        null
    }
}

fun Class<*>?.callStaticMethod(
    methodName: String,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?
): Any? {
    if (this == null) {
        log("callStaticMethod:withArgs", "Class is null, method=$methodName, args=${args.size}")
        return null
    }
    return try {
        val m = this.getDeclaredMethod(methodName, *parameterTypes)
        m.isAccessible = true
        m.invoke(null, *args)
    } catch (t: Throwable) {
        log("callStaticMethod:withArgs", "Error calling $methodName: $t")
        null
    }
}

fun Class<*>?.callStaticMethodSilently(methodName: String): Any? {
    if (this == null) return null
    return try {
        val m = findMethod(this, methodName, 0)
        m.isAccessible = true
        m.invoke(null)
    } catch (_: Throwable) {
        null
    }
}

fun Class<*>?.callStaticMethodSilently(methodName: String, vararg args: Any?): Any? {
    if (this == null) return null
    return try {
        val m = findMethod(this, methodName, args.size)
        m.isAccessible = true
        m.invoke(null, *args)
    } catch (_: Throwable) {
        null
    }
}

fun Class<*>?.callStaticMethodSilently(
    methodName: String,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?
): Any? {
    if (this == null) return null
    return try {
        val m = this.getDeclaredMethod(methodName, *parameterTypes)
        m.isAccessible = true
        m.invoke(null, *args)
    } catch (_: Throwable) {
        null
    }
}

fun Any?.getField(fieldName: String): Any {
    if (this == null) throw NoSuchFieldError("Field not found: $fieldName, object is null")
    val f = findField(this.javaClass, fieldName)
    f.isAccessible = true
    return f.get(this)
}

fun Any?.getFieldSilently(fieldName: String): Any? {
    if (this == null) return null
    return try {
        val f = findField(this.javaClass, fieldName)
        f.isAccessible = true
        f.get(this)
    } catch (_: Throwable) {
        null
    }
}

fun Any?.setField(fieldName: String, value: Any?) {
    if (this == null) return
    val f = findField(this.javaClass, fieldName)
    f.isAccessible = true
    f.set(this, value)
}

fun Any?.setFieldSilently(fieldName: String, value: Any?): Boolean {
    if (this == null) return false
    return try {
        val f = findField(this.javaClass, fieldName)
        f.isAccessible = true
        f.set(this, value)
        true
    } catch (_: Throwable) {
        false
    }
}

fun Class<*>?.getStaticField(fieldName: String): Any {
    if (this == null) throw NoSuchFieldError("Field not found: $fieldName, class is null")
    val f = findField(this, fieldName)
    f.isAccessible = true
    return f.get(null) ?: throw NoSuchFieldError("Static field $fieldName is null")
}

fun Class<*>?.getStaticFieldSilently(fieldName: String): Any? {
    if (this == null) return null
    return try {
        val f = findField(this, fieldName)
        f.isAccessible = true
        f.get(null)
    } catch (_: Throwable) {
        null
    }
}

fun Class<*>?.setStaticField(fieldName: String, value: Any?) {
    if (this == null) return
    val f = findField(this, fieldName)
    f.isAccessible = true
    f.set(null, value)
}

fun Class<*>?.setStaticFieldSilently(fieldName: String, value: Any?) {
    if (this == null) return
    try {
        val f = findField(this, fieldName)
        f.isAccessible = true
        f.set(null, value)
    } catch (_: Throwable) {
    }
}

fun Any?.getAnyField(vararg fieldNames: String): Any? {
    fieldNames.forEach { fieldName ->
        try {
            return getField(fieldName)
        } catch (_: Throwable) {
        }
    }
    throw NoSuchFieldError("Field not found: ${fieldNames.joinToString()}")
}

fun Any?.setAnyField(value: Any?, vararg fieldNames: String) {
    fieldNames.forEach { fieldName ->
        try {
            setField(fieldName, value)
            return
        } catch (_: Throwable) {
        }
    }
    throw NoSuchFieldError("Field not found: ${fieldNames.joinToString()}")
}

fun Class<*>?.getAnyStaticField(vararg fieldNames: String): Any? {
    fieldNames.forEach { fieldName ->
        try {
            return getStaticField(fieldName)
        } catch (_: Throwable) {
        }
    }
    throw NoSuchFieldError("Field not found: ${fieldNames.joinToString()}")
}

private val extraFields = Collections.synchronizedMap(IdentityHashMap<Any, MutableMap<String, Any?>>())

fun Any?.getExtraField(fieldName: String): Any {
    if (this == null) throw NoSuchFieldError("Extra field not found: $fieldName, object is null")
    return extraFields[this]?.get(fieldName)
        ?: throw NoSuchFieldError("Extra field not found: $fieldName")
}

fun Any?.getExtraFieldSilently(fieldName: String): Any? {
    if (this == null) return null
    return extraFields[this]?.get(fieldName)
}

fun Any?.setExtraField(fieldName: String, value: Any?) {
    if (this == null) return
    extraFields.getOrPut(this) { mutableMapOf() }[fieldName] = value
}

private fun findMethod(clazz: Class<*>, name: String, paramCount: Int): Method {
    var c: Class<*>? = clazz
    while (c != null) {
        c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == paramCount }
            ?.let { return it }
        c = c.superclass
    }
    throw NoSuchMethodError("Method not found: $name with $paramCount params in ${clazz.name}")
}

private fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field {
    var c: Class<*>? = clazz
    while (c != null) {
        c.declaredFields.firstOrNull { it.name == name }
            ?.let { return it }
        c = c.superclass
    }
    throw NoSuchFieldError("Field not found: $name in ${clazz.name}")
}

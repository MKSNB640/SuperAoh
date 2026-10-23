package com.superaoh.ui.compose

import android.content.Context
import com.superaoh.container.GameRuntime
import com.superaoh.core.Workspace
import dalvik.system.DexClassLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InvalidClassException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * 通过工作区 APK 的 DEX 读写游戏数据类。
 *
 * 只做三件事：
 *   1. 为工作区建立 DexClassLoader（按 workspace.id 隔离缓存，避免不同版本互相污染）
 *   2. 反序列化 / 序列化游戏对象（Java 原生序列化，与游戏完全一致）
 *   3. 用反射调用游戏类的 getter / setter（参数类型按形参自动对齐）
 */
internal object GameDataAccess {
    private val loaders = ConcurrentHashMap<String, ClassLoader>()

    fun loader(context: Context, workspace: Workspace): ClassLoader {
        val key = workspace.apkSha256.ifBlank { workspace.id }
        loaders[key]?.let { return it }
        val apk = workspace.apk(context)
        if (!apk.isFile) throw IllegalStateException("工作区 APK 不存在: ${apk.absolutePath}")
        val dexCache = File(context.codeCacheDir, "game_data_${workspace.id}")
        if (!dexCache.exists() && !dexCache.mkdirs()) {
            throw IllegalStateException("无法创建工作区 DEX 缓存目录")
        }
        val dexFiles = ZipFile(apk).use { GameRuntime.deployDex(it, dexCache).dexFiles }
        val optimized = File(context.codeCacheDir, "game_data_opt/${workspace.id}")
        if (!optimized.exists() && !optimized.mkdirs()) {
            throw IllegalStateException("无法创建 DEX 优化目录")
        }
        val cp = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
        return DexClassLoader(cp, optimized.absolutePath, null, context.classLoader)
            .also { loaders.putIfAbsent(key, it) }
            .let { loaders[key] ?: it }
    }

    fun readGameObject(input: InputStream, loader: ClassLoader): Any =
        GameObjectInputStream(input, loader).use { it.readObject() }

    fun serialize(value: Any): ByteArray = ByteArrayOutputStream().use { bytes ->
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        bytes.toByteArray()
    }

    fun atomicWrite(target: File, bytes: ByteArray) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("无法创建目录 ${parent.absolutePath}")
        }
        val temp = File(parent, target.name + ".tmp")
        temp.outputStream().use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        if (target.exists() && !target.delete()) throw IllegalStateException("无法替换 ${target.name}")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    // ---------------- 反射读写 ----------------

    fun intGetter(value: Any, method: String): Int =
        (call(value, method) as? Number)?.toInt() ?: 0

    fun floatGetter(value: Any, method: String): Float =
        (call(value, method) as? Number)?.toFloat() ?: 0f

    fun stringGetter(value: Any, method: String): String =
        call(value, method) as? String ?: ""

    fun objectGetter(value: Any, method: String): Any? = call(value, method)

    /**
     * 调用任意无参方法；方法不存在时返回 null，而不是抛异常。
     *
     * 两个必须注意的点：
     *
     * 1. 用 getDeclaredMethods() 而不是 getMethods()
     *    游戏类由工作区的 DexClassLoader 加载，本类由 App 的 PathClassLoader 加载。
     *    后者对前者加载的类**不可见**，而 Class.getMethods() 会做访问检查，
     *    把「声明类不可见」的方法直接过滤掉 —— 结果就是 public 方法也查不到，
     *    报 NoSuchMethodException。getDeclaredMethods() 不做这层过滤。
     *    因此这里沿继承链手工逐级收集。
     *
     * 2. setAccessible(true)
     *    即使拿到了 Method，invoke 时 ART 仍会做访问检查并抛
     *    "Class<...> is not accessible from Class<...>"。
     *    setAccessible 会把调用切换成 kNoAccessCheck，绕开这条检查。
     */
    fun call(value: Any, method: String): Any? = try {
        findMethod(value.javaClass, method, 0)?.invoke(value)
    } catch (_: Throwable) {
        null
    }

    /** 和 [call] 一样，但失败时抛出真实异常，便于定位问题。 */
    fun callOrThrow(value: Any, method: String): Any? {
        val target = findMethod(value.javaClass, method, 0)
            ?: throw NoSuchMethodException("${value.javaClass.name}.$method")
        return target.invoke(value)
    }

    /**
     * 沿继承链查找方法。
     *
     * 用 getDeclaredMethods() 逐级收集，不走 getMethods()（原因见 [call]）。
     * 返回的 Method 已设好 accessible。
     */
    private fun findMethod(type: Class<*>, name: String, paramCount: Int): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            val hit = current.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == paramCount
            }
            if (hit != null) {
                if (!hit.isAccessible) hit.isAccessible = true
                return hit
            }
            current = current.superclass
        }
        return null
    }

    /**
     * 用无参构造器创建游戏类的实例。
     *
     * 同样需要 setAccessible：跨 ClassLoader 时构造器不可见，newInstance 会失败。
     */
    fun newInstance(type: Class<*>): Any {
        val ctor = type.getDeclaredConstructor()
        if (!ctor.isAccessible) ctor.isAccessible = true
        return ctor.newInstance()
    }

    /**
     * 调用单参方法，参数类型按游戏类的形参自动对齐（int/float/boolean/String）。
     *
     * [arg] 允许为 null：游戏里有 `setSr_GameData(null)` 这种「清空可空字段」的用法，
     * 此时只匹配引用类型的形参，不匹配基本类型（基本类型塞 null 会在反射时抛异常）。
     */
    fun set(value: Any, method: String, arg: Any?) {
        val candidates = declaredMethodsNamed(value.javaClass, method).filter {
            it.parameterTypes.size == 1
        }
        val target = if (arg == null) {
            candidates.firstOrNull { !it.parameterTypes[0].isPrimitive }
                ?: throw NoSuchMethodException("${value.javaClass.name}.$method(可空参数)")
        } else {
            candidates.firstOrNull()
                ?: throw NoSuchMethodException("${value.javaClass.name}.$method")
        }
        if (!target.isAccessible) target.isAccessible = true
        target.invoke(value, if (arg == null) null else coerce(arg, target.parameterTypes[0]))
    }

    /** 沿继承链收集指定名字的全部声明方法（不受访问检查过滤）。 */
    private fun declaredMethodsNamed(type: Class<*>, name: String): List<java.lang.reflect.Method> {
        val out = ArrayList<java.lang.reflect.Method>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredMethods.forEach { if (it.name == name) out.add(it) }
            current = current.superclass
        }
        return out
    }

    private fun coerce(arg: Any, type: Class<*>): Any = when (type) {
        java.lang.Integer.TYPE, java.lang.Integer::class.java ->
            (arg as? Number)?.toInt() ?: arg.toString().toIntOrNull() ?: 0
        java.lang.Float.TYPE, java.lang.Float::class.java ->
            (arg as? Number)?.toFloat() ?: arg.toString().toFloatOrNull() ?: 0f
        java.lang.Double.TYPE, java.lang.Double::class.java ->
            (arg as? Number)?.toDouble() ?: arg.toString().toDoubleOrNull() ?: 0.0
        java.lang.Long.TYPE, java.lang.Long::class.java ->
            (arg as? Number)?.toLong() ?: arg.toString().toLongOrNull() ?: 0L
        java.lang.Boolean.TYPE, java.lang.Boolean::class.java ->
            arg as? Boolean ?: arg.toString().equals("true", true)
        else -> arg
    }

    private class GameObjectInputStream(input: InputStream, private val loader: ClassLoader) : ObjectInputStream(input) {
        override fun resolveClass(desc: java.io.ObjectStreamClass): Class<*> {
            val name = desc.name
            if (!(name.startsWith("age.of.civilizations2.jakowski.lukasz.") ||
                    name.startsWith("java.lang.") || name.startsWith("java.util.") || name.startsWith("["))) {
                throw InvalidClassException("拒绝解析非游戏数据类型", name)
            }
            return try {
                if (name.startsWith("[")) Class.forName(name, false, loader) else loader.loadClass(name)
            } catch (_: ClassNotFoundException) {
                super.resolveClass(desc)
            }
        }
    }
}
package com.zero.link.shizuku.server.system

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import java.lang.reflect.Constructor
import java.lang.reflect.Field

/**
 * 环境伪造引擎 (Bypass Pipeline Environment Spoofing)
 * 完全无 Activity 依赖的底层环境构造器，向系统假装为一个正规的预装应用 (com.android.shellUID: 2000)。
 */
@SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi")
internal object ShellEnvironment {

    private const val SHELL_PACKAGE = "com.android.shell"
    private const val SHELL_UID = 2000
    
    private val ACTIVITY_THREAD_CLASS: Class<*> by lazy { Class.forName("android.app.ActivityThread") }
    private val ACTIVITY_THREAD: Any by lazy {
        try {
            val constructor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor()
            constructor.isAccessible = true
            val instance = constructor.newInstance()
            
            val currentActivityThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread")
            currentActivityThreadField.isAccessible = true
            currentActivityThreadField.set(null, instance)
            
            val systemThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread")
            systemThreadField.isAccessible = true
            systemThreadField.setBoolean(instance, true)
            
            instance
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var shellContext: Context? = null

    /**
     * 绕过限制：开启系统级隐藏 API 通过
     */
    fun bypassHiddenApi() {
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmRuntimeClass.getDeclaredMethod("getRuntime").invoke(null)
            vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(runtime, arrayOf("L"))
        } catch (e: Exception) {
            System.err.println("[!] Hidden API bypass failed: ${e.message}")
        }
    }

/**
     * 应用各种工作区和环境修复
     */
    fun applyWorkarounds() {
        System.err.println("[*] applyWorkarounds: starting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fillConfigurationController()
        }
        System.err.println("[*] applyWorkarounds: configuration filled")
        fillBoundApplication()
        System.err.println("[*] applyWorkarounds: bound app filled")
        fillInitialApplication()
        System.err.println("[*] applyWorkarounds: initial app filled")
    }

    /**
     * 获取 DisplayManagerGlobal 实例
     */
    fun getDisplayManagerGlobal(): Any {
        try {
            return Class.forName("android.hardware.display.DisplayManagerGlobal")
                .getMethod("getInstance")
                .invoke(null)!!
        } catch (e: Exception) {
            throw RuntimeException("Failed to acquire DisplayManagerGlobal", e)
        }
    }

    /**
     * 获取基础 SystemContext
     */
    fun getSystemContext(): Context {
        if (systemContext == null) {
            synchronized(this) {
                if (systemContext == null) {
                    try {
                        // 修复 1: 避免与 ServerMain 里的 Looper.prepare() 冲突
                        if (Looper.getMainLooper() == null && Looper.myLooper() == null) {
                            Looper.prepareMainLooper()
                        }
                        val method = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext")
                        systemContext = method.invoke(ACTIVITY_THREAD) as Context
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        // 修复 2: 拦截 MIUI 专属的 FileNotFoundException (theme_compatibility.xml)
                        // 这个异常在 MIUI 上是预期的，且无害的，但如果往上抛会导致初始化失败。
                        val cause = e.targetException
                        if (cause is java.io.FileNotFoundException && cause.message?.contains("theme_compatibility") == true) {
                            System.err.println("[*] Ignored harmless MIUI theme exception.")
                            // 即使抛了异常，ActivityThread 内部通常已经把 mSystemContext 实例化了，我们可以尝试强行拿出来
                            try {
                                val field = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemContext")
                                field.isAccessible = true
                                systemContext = field.get(ACTIVITY_THREAD) as Context?
                            } catch (fallbackEx: Exception) {
                                throw RuntimeException("Failed to extract system context after MIUI crash", fallbackEx)
                            }
                        } else {
                            throw RuntimeException("Failed to get system context", cause)
                        }
                    } catch (e: Exception) {
                        throw RuntimeException("Failed to get system context", e)
                    }
                }
            }
        }
        return systemContext!!
    }

/**
     * 获取伪装成 com.android.shell 的 Context
     * 用于通过权限校验及合法性检查框架调用服务
     */
    fun getShellContext(): Context {
        System.err.println("[*] getShellContext: checking cache...")
        if (shellContext == null) {
            synchronized(this) {
                if (shellContext == null) {
                    System.err.println("[*] getShellContext: creating new shell context...")
                    val baseContext = getSystemContext()
                    System.err.println("[*] getShellContext: got base context")
                    var shellPackageContext = baseContext
                    try {
                        shellPackageContext = baseContext.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                        System.err.println("[*] getShellContext: created shell package context")
                    } catch (e: Exception) {
                        System.err.println("[!] getShellContext: fallback to system context: ${e.message}")
                    }
                    shellContext = ShellContext(shellPackageContext)
                    System.err.println("[*] getShellContext: done")
                }
            }
        }
        return shellContext!!
    }

    private fun fillBoundApplication() {
        try {
            val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val appBindDataConstructor = appBindDataClass.getDeclaredConstructor()
            appBindDataConstructor.isAccessible = true
            val appBindData = appBindDataConstructor.newInstance()

            val applicationInfo = ApplicationInfo()
            applicationInfo.packageName = SHELL_PACKAGE
            applicationInfo.uid = SHELL_UID
            setDeclaredField(appBindDataClass, appBindData, "appInfo", applicationInfo)
            setDeclaredField(ACTIVITY_THREAD_CLASS, ACTIVITY_THREAD, "mBoundApplication", appBindData)
        } catch (e: Throwable) {
            System.err.println("[!] Could not fill app info: ${e.message}")
        }
    }

private fun fillInitialApplication() {
        System.err.println("[*] fillInitialApplication: creating application...")
        try {
            val application = Instrumentation.newApplication(Application::class.java, getShellContext())
            System.err.println("[*] fillInitialApplication: application created, setting field...")
            setDeclaredField(ACTIVITY_THREAD_CLASS, ACTIVITY_THREAD, "mInitialApplication", application)
            System.err.println("[*] fillInitialApplication: done")
        } catch (e: Throwable) {
            System.err.println("[!] fillInitialApplication failed: ${e.message}")
        }
    }

    private fun fillConfigurationController() {
        try {
            val configurationControllerClass = Class.forName("android.app.ConfigurationController")
            val activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal")
            val configurationControllerConstructor = configurationControllerClass.getDeclaredConstructor(activityThreadInternalClass)
            configurationControllerConstructor.isAccessible = true
            val configurationController = configurationControllerConstructor.newInstance(ACTIVITY_THREAD)
            setDeclaredField(ACTIVITY_THREAD_CLASS, ACTIVITY_THREAD, "mConfigurationController", configurationController)
        } catch (e: Throwable) {
            System.err.println("[!] Could not fill configuration controller: ${e.message}")
        }
    }

    private fun setDeclaredField(owner: Class<*>, target: Any, fieldName: String, value: Any) {
        try {
            val field = owner.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(target, value)
        } catch (e: Exception) {
            System.err.println("[!] Failed to set field $fieldName: ${e.message}")
        }
    }

    /**
     * 宿主 Context 代理，向应用框架伪装核心 App 信息
     */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        private val shellApplicationInfo: ApplicationInfo? = runCatching {
            base.applicationInfo?.apply {
                packageName = SHELL_PACKAGE
                uid = SHELL_UID
            }
        }.getOrNull()

        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationInfo(): ApplicationInfo = shellApplicationInfo ?: super.getApplicationInfo()
        override fun getApplicationContext(): Context = this

        @SuppressLint("NewApi")
        override fun getAttributionSource(): AttributionSource {
            return AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
        }

        override fun getSystemService(name: String): Any? {
            val service = super.getSystemService(name) ?: return null
            patchServiceContext(service)
            return service
        }

        private fun patchServiceContext(service: Any) {
            var current: Class<*>? = service.javaClass
            while (current != null) {
                try {
                    val contextField = current.getDeclaredField("mContext")
                    contextField.isAccessible = true
                    contextField.set(service, this)
                    return
                } catch (e: NoSuchFieldException) {
                    current = current.superclass
                } catch (e: Exception) {
                    return
                }
            }
        }
    }
}

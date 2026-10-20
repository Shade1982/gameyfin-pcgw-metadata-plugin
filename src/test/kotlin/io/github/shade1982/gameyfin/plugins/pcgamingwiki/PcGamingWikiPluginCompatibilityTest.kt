package io.github.shade1982.gameyfin.plugins.pcgamingwiki

import org.gameyfin.pluginapi.core.wrapper.GameyfinPlugin
import org.gameyfin.pluginapi.core.wrapper.PluginContext
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PcGamingWikiPluginCompatibilityTest {
    @Test
    fun `packaged plugin loads with Gameyfin 2_5 PluginContext`() {
        val pluginJar = File(requireNotNull(System.getProperty("pluginJar")))
        assertTrue(pluginJar.isFile)

        JarFile(pluginJar).use { jar ->
            assertEquals(
                PLUGIN_CLASS,
                jar.manifest.mainAttributes.getValue("Plugin-Class")
            )
            val extensions = jar.getInputStream(jar.getJarEntry("META-INF/extensions.idx"))
                .bufferedReader()
                .use { it.readText() }
            assertTrue(extensions.contains("$PLUGIN_CLASS\$PcGamingWikiMetadataProvider"))
        }

        PluginJarClassLoader(pluginJar, javaClass.classLoader).use { classLoader ->
            val pluginClass = classLoader.loadClass(PLUGIN_CLASS)
            assertTrue(GameyfinPlugin::class.java.isAssignableFrom(pluginClass))

            val context = pluginContext()
            val plugin = pluginClass.getConstructor(PluginContext::class.java).newInstance(context)
            assertNotNull(plugin)
        }
    }

    private fun pluginContext(): PluginContext =
        Proxy.newProxyInstance(
            PluginContext::class.java.classLoader,
            arrayOf(PluginContext::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getPluginId" -> "io.github.shade1982.gameyfin.plugins.pcgamingwiki"
                "getPluginVersion" -> "0.1.8"
                "getDataDirectory" -> Path.of("build/plugin-smoke-test-data")
                "getDataService" -> null
                "toString" -> "PluginContext smoke-test proxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected PluginContext method: ${method.name}")
            }
        } as PluginContext

    private class PluginJarClassLoader(pluginJar: File, parent: ClassLoader) :
        URLClassLoader(arrayOf(pluginJar.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            synchronized(getClassLoadingLock(name)) {
                var loadedClass = findLoadedClass(name)
                if (loadedClass == null) {
                    loadedClass = if (name.startsWith(PLUGIN_PACKAGE)) {
                        try {
                            findClass(name)
                        } catch (_: ClassNotFoundException) {
                            super.loadClass(name, false)
                        }
                    } else {
                        super.loadClass(name, false)
                    }
                }
                if (resolve) resolveClass(loadedClass)
                loadedClass
            }
    }

    private companion object {
        const val PLUGIN_PACKAGE = "io.github.shade1982.gameyfin.plugins.pcgamingwiki."
        const val PLUGIN_CLASS = "${PLUGIN_PACKAGE}PcGamingWikiPlugin"
    }
}

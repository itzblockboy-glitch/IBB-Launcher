package dev.ibb.launcher.game

import dev.ibb.launcher.data.model.Library
import dev.ibb.launcher.data.model.Rule
import dev.ibb.launcher.data.model.VersionDetail

/**
 * Pure game-version logic: inheritance flattening, rule evaluation and library resolution.
 *
 * Everything here is deliberately free of Android APIs so it can be unit-tested on the JVM
 * (see `app/src/test/java/dev/ibb/launcher/VersionResolverTest.kt`). Getting these rules wrong
 * is the #1 cause of "game crashes instantly" reports, so they get dedicated tests.
 */
object VersionResolver {

    /** Runtime facts the rules care about. Mirrors `os.name`/`os.arch` as seen by the JVM. */
    data class Env(
        val osName: String = "linux",
        val osArch: String = "aarch64",
        val osVersion: String = "",
        val hasCustomResolution: Boolean = false,
        val isDemoUser: Boolean = false,
    ) {
        companion object {
            fun forAndroidArch(is64Bit: Boolean, abi: String, release: String): Env = Env(
                osName = "linux",
                osArch = when {
                    abi.contains("64") && (abi.contains("arm") || abi.contains("aarch")) -> "aarch64"
                    abi.contains("64") -> "x86_64"
                    abi.contains("arm") -> "arm"
                    else -> "x86"
                },
                osVersion = release,
            )
        }
    }

    /**
     * Merges a child profile with its parent (mod-loader profiles only contain deltas).
     *
     * Rules: child wins for scalars (`mainClass`, `assets`, `javaVersion`), lists are appended
     * parent-first so parent libraries stay resolvable, and `downloads` are merged key-by-key.
     */
    fun flatten(child: VersionDetail, parent: VersionDetail?): VersionDetail {
        if (parent == null) return child
        return child.copy(
            mainClass = child.mainClass.ifBlank { parent.mainClass },
            assets = if (child.assets == "legacy" && parent.assets != "legacy") parent.assets else child.assets,
            assetIndex = child.assetIndex ?: parent.assetIndex,
            javaVersion = child.javaVersion ?: parent.javaVersion,
            logging = child.logging ?: parent.logging,
            downloads = parent.downloads + child.downloads,
            libraries = parent.libraries + child.libraries,
        )
    }

    /**
     * Evaluates the Mojang rule DSL.
     *
     * Semantics (from the official launcher):
     *  * no rules → allowed;
     *  * otherwise the **last** matching rule wins;
     *  * a rule that matches on OS but not on features is skipped, letting later rules decide.
     */
    fun isAllowed(rules: List<Rule>, env: Env, features: Map<String, Boolean> = emptyMap()): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            if (!matches(rule, env, features)) continue
            allowed = rule.action == "allow"
        }
        return allowed
    }

    private fun matches(rule: Rule, env: Env, features: Map<String, Boolean>): Boolean {
        rule.os?.let { os ->
            os.name?.let { if (!it.equals(env.osName, ignoreCase = true)) return false }
            os.arch?.let { arch ->
                // "x86" rules must not match x86_64 devices.
                if (!arch.equals(env.osArch, ignoreCase = true)) return false
            }
            os.version?.let { pattern ->
                if (env.osVersion.isNotEmpty() && !Regex(pattern).containsMatchIn(env.osVersion)) return false
            }
        }
        rule.features?.forEach { (name, expected) ->
            val actual = features[name] ?: false
            if (actual != expected) return false
        }
        return true
    }

    /**
     * Android cannot load desktop natives (`natives-windows`, `natives-macos`, x86-only LWJGL
     * builds). We keep the plain artifacts and let our own `lwjgl3` component supply the
     * Android-native equivalents at runtime.
     */
    fun isAndroidCompatible(library: Library, env: Env): Boolean {
        if (!isAllowed(library.rules, env)) return false
        val base = library.name.substringBefore(':')
        // Desktop-OS native classifier jars are never usable on Android.
        if (library.isNativeClassifier) {
            val lower = library.name.lowercase()
            return lower.contains("linux") || lower.contains("android")
        }
        // Skip LWJGL native-only modules that would fight with our bridge.
        if (base == "org.lwjgl" && library.name.contains("natives")) return false
        return true
    }

    /** De-duplicates libraries by `group:artifact`, keeping the last (highest) version. */
    fun dedupeLibraries(libraries: List<Library>): List<Library> {
        val byKey = LinkedHashMap<String, Library>()
        for (library in libraries) {
            val coord = library.coordinate() ?: continue
            val key = "${coord.first}:${coord.second}" + if (library.isNativeClassifier) ":${library.name.substringAfterLast(':')}" else ""
            byKey[key] = library
        }
        return byKey.values.toList()
    }

    /**
     * Whether an LWJGL2-era version needs the LWJGLX compatibility layer
     * (1.13+ uses LWJGL3; 1.12.2 and older use LWJGL2 which has no Android build).
     */
    fun needsLwjglx(libraryNames: List<String>): Boolean =
        libraryNames.any { it.startsWith("org.lwjgl.lwjgl:") }

    /** Parses the LWJGL version out of the library list, e.g. 3.3.3 or 2.9.4. */
    fun lwjglVersion(libraryNames: List<String>): String? {
        for (name in libraryNames) {
            if (name.startsWith("org.lwjgl:lwjgl:")) return name.substringAfter("org.lwjgl:lwjgl:").substringBefore(':')
            if (name.startsWith("org.lwjgl.lwjgl:lwjgl:")) return name.substringAfter("org.lwjgl.lwjgl:lwjgl:").substringBefore(':')
        }
        return null
    }

    /**
     * Expands `${...}` placeholders used by mod-loader argument lists.
     *
     * Each argument stays exactly one argv element: we deliberately do **not** split expanded
     * values on spaces, because `${game_directory}` frequently contains them
     * (`/storage/emulated/0/My Games/...`) and splitting would silently break the launch.
     * Templates that pack two logical arguments into one string (`"--width ${resolution_width}"`)
     * are therefore rejected by [LaunchArgsBuilder.findUnresolvedPlaceholders] style checks during
     * install, where we can show a useful error instead of failing inside the JVM.
     */
    fun expandPlaceholders(args: List<String>, values: Map<String, String>): List<String> {
        val pattern = Regex("\\$\\{([^}]+)}")
        return args.map { raw ->
            pattern.replace(raw) { match -> values[match.groupValues[1]] ?: match.value }
        }
    }

    /** Splits a classpath on both Unix and Windows separators and drops entries that are absent. */
    fun buildClasspath(separator: String, entries: List<String>): String =
        entries.filter { it.isNotBlank() }.distinct().joinToString(separator)

    /**
     * Chooses the Java major version a game version needs.
     * Mojang metadata is authoritative when present; otherwise fall back to the known cut-offs:
     *  * ≤ 1.16.5 → Java 8
     *  * 1.17 → 16, but our ARM builds ship 17 which is backward compatible
     *  * 1.18 – 1.20.4 → Java 17
     *  * 1.20.5+ → Java 21
     */
    fun requiredJavaMajor(versionId: String, declared: Int?): Int {
        if (declared != null && declared > 0) return declared
        val parts = versionId.split('.', '-', '_')
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 8
        val patch = parts.getOrNull(2)?.substringBefore('-')?.toIntOrNull() ?: 0
        return when {
            minor <= 16 -> 8
            minor == 17 -> 17
            minor <= 20 && !(minor == 20 && patch >= 5) -> 17
            minor == 20 && patch >= 5 -> 21
            else -> 21
        }
    }
}

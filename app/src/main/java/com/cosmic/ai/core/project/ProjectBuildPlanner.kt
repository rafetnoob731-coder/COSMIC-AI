package com.cosmic.ai.core.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A candidate plan derived from manifests; it is never evidence that the command can run here. */
data class ProjectBuildPlan(
    val manifest: String,
    val workingDirectory: String,
    val action: Action,
    val argv: List<String>,
    val requiresNetwork: Boolean,
    val requiresApproval: Boolean = true
) {
    enum class Action { BUILD, TEST, RUN, CLEAN, DIAGNOSTICS }
}

object ProjectBuildPlanner {
    fun create(paths: Set<String>, manifestContents: Map<String, String>): List<ProjectBuildPlan> {
        // Keep the provider's original spelling for working directories. Paths are normalized
        // only for matching because SAF and internal workspaces may be case-sensitive.
        val originalByNormalized = paths.associateBy { normalize(it) }
        val normalized = originalByNormalized.keys
        val plans = mutableListOf<ProjectBuildPlan>()
        val manifestsByPath = manifestContents.entries.associateBy { normalize(it.key) }
        val packagePaths = normalized.filter { basename(it) == "package.json" }
        for (packagePath in packagePaths) {
            val actualPackagePath = originalByNormalized.getValue(packagePath)
            val source = manifestsByPath[packagePath]?.value
            val scripts = source?.let(::scripts).orEmpty()
            val projectDirectory = actualPackagePath.substringBeforeLast('/', "")
            val localNames = normalized.asSequence()
                .filter { parent(it) == normalize(projectDirectory) }
                .map(::basename)
                .toSet()
            val manager = when {
                "pnpm-lock.yaml" in localNames -> "pnpm"
                "yarn.lock" in localNames -> "yarn"
                else -> "npm"
            }
            if ("build" in scripts) plans += ProjectBuildPlan(actualPackagePath, projectDirectory, ProjectBuildPlan.Action.BUILD, listOf(manager, "run", "build"), requiresNetwork = true)
            if ("test" in scripts) plans += ProjectBuildPlan(actualPackagePath, projectDirectory, ProjectBuildPlan.Action.TEST, listOf(manager, "test"), requiresNetwork = true)
            if ("lint" in scripts) plans += ProjectBuildPlan(actualPackagePath, projectDirectory, ProjectBuildPlan.Action.DIAGNOSTICS, listOf(manager, "run", "lint"), requiresNetwork = true)
            if ("clean" in scripts) plans += ProjectBuildPlan(actualPackagePath, projectDirectory, ProjectBuildPlan.Action.CLEAN, listOf(manager, "run", "clean"), requiresNetwork = true)
            if ("dev" in scripts || "start" in scripts) plans += ProjectBuildPlan(actualPackagePath, projectDirectory, ProjectBuildPlan.Action.RUN, listOf(manager, "run", if ("dev" in scripts) "dev" else "start"), requiresNetwork = true)
        }
        val rootNames = normalized.filter { parent(it).isEmpty() }.map(::basename).toSet()
        for (path in normalized.filter { basename(it) == "cargo.toml" }) {
            val actual = originalByNormalized.getValue(path)
            val directory = actual.substringBeforeLast('/', "")
            val isWorkspace = manifestsByPath[path]?.value?.contains("[workspace]") == true
            val buildArgv = if (isWorkspace) listOf("cargo", "build", "--workspace") else listOf("cargo", "build")
            val testArgv = if (isWorkspace) listOf("cargo", "test", "--workspace") else listOf("cargo", "test")
            val clippyArgv = if (isWorkspace) listOf("cargo", "clippy", "--workspace") else listOf("cargo", "clippy")
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.BUILD, buildArgv, true)
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.TEST, testArgv, true)
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.RUN, listOf("cargo", "run"), true)
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.CLEAN, listOf("cargo", "clean"), false)
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.DIAGNOSTICS, clippyArgv, true)
        }
        for (path in normalized.filter { basename(it) == "go.work" }) {
            val actual = originalByNormalized.getValue(path)
            val directory = actual.substringBeforeLast('/', "")
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.BUILD, listOf("go", "work", "sync"), true)
        }
        for (path in normalized.filter { basename(it) == "go.mod" }) {
            val actual = originalByNormalized.getValue(path)
            val directory = actual.substringBeforeLast('/', "")
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.BUILD, listOf("go", "build", "./..."), true)
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.TEST, listOf("go", "test", "./..."), true)
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.CLEAN, listOf("go", "clean"), false)
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.DIAGNOSTICS, listOf("go", "vet", "./..."), true)
        }
        if ("gradlew" in rootNames) {
            val wrapper = originalByNormalized.getValue("gradlew")
            plans += ProjectBuildPlan(wrapper, "", ProjectBuildPlan.Action.BUILD, listOf("./$wrapper", "build"), true)
            plans += ProjectBuildPlan(wrapper, "", ProjectBuildPlan.Action.TEST, listOf("./$wrapper", "test"), true)
            plans += ProjectBuildPlan(wrapper, "", ProjectBuildPlan.Action.CLEAN, listOf("./$wrapper", "clean"), true)
            plans += ProjectBuildPlan(wrapper, "", ProjectBuildPlan.Action.DIAGNOSTICS, listOf("./$wrapper", "check"), true)
        } else if ("pom.xml" in rootNames) {
            plans += ProjectBuildPlan("pom.xml", "", ProjectBuildPlan.Action.BUILD, listOf("mvn", "package"), true)
            plans += ProjectBuildPlan("pom.xml", "", ProjectBuildPlan.Action.TEST, listOf("mvn", "test"), true)
            plans += ProjectBuildPlan("pom.xml", "", ProjectBuildPlan.Action.CLEAN, listOf("mvn", "clean"), true)
            plans += ProjectBuildPlan("pom.xml", "", ProjectBuildPlan.Action.DIAGNOSTICS, listOf("mvn", "verify"), true)
        }
        for (path in normalized.filter { basename(it) == "pubspec.yaml" }) {
            if (manifestsByPath[path]?.value?.contains("flutter:") != true) continue
            val actual = originalByNormalized.getValue(path)
            val directory = actual.substringBeforeLast('/', "")
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.BUILD, listOf("flutter", "build"), true)
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.TEST, listOf("flutter", "test"), true)
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.CLEAN, listOf("flutter", "clean"), false)
            plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.DIAGNOSTICS, listOf("flutter", "analyze"), false)
        }
        for (path in normalized.filter { basename(it) in setOf("requirements.txt", "pyproject.toml") }) {
            val actual = originalByNormalized.getValue(path)
            val directory = actual.substringBeforeLast('/', "")
            val localPaths = normalized.filter { isInside(it, normalize(directory)) }
            if (localPaths.any { basename(it).startsWith("test_") || basename(it).endsWith("_test.py") || "/tests/" in "/$it/" }) {
                plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.TEST, listOf("python3", "-m", "pytest"), true)
            }
            val pyproject = manifestsByPath[path]?.value.orEmpty()
            if (basename(path) == "pyproject.toml" && "[build-system]" in pyproject) plans += ProjectBuildPlan(actual, directory, ProjectBuildPlan.Action.BUILD, listOf("python3", "-m", "build"), true)
        }
        if ("cmakelists.txt" in rootNames) {
            plans += ProjectBuildPlan("CMakeLists.txt", "", ProjectBuildPlan.Action.BUILD, listOf("cmake", "-S", ".", "-B", "build"), true)
            plans += ProjectBuildPlan("CMakeLists.txt", "", ProjectBuildPlan.Action.CLEAN, listOf("cmake", "--build", "build", "--target", "clean"), true)
            if (normalized.any { it.endsWith("test.cpp") || it.endsWith("test.c") || it.contains("/tests/") }) {
                plans += ProjectBuildPlan("CMakeLists.txt", "", ProjectBuildPlan.Action.TEST, listOf("ctest", "--test-dir", "build"), true)
            }
        }
        for (composerPath in normalized.filter { basename(it) == "composer.json" }) {
            val actual = originalByNormalized.getValue(composerPath)
            val scripts = manifestsByPath[composerPath]?.value?.let(::scripts).orEmpty()
            if ("test" in scripts) plans += ProjectBuildPlan(actual, actual.substringBeforeLast('/', ""), ProjectBuildPlan.Action.TEST, listOf("composer", "test"), true)
        }
        if ("gemfile" in rootNames && "rakefile" in rootNames) {
            plans += ProjectBuildPlan("Rakefile", "", ProjectBuildPlan.Action.TEST, listOf("bundle", "exec", "rake", "test"), true)
        }
        return plans.distinctBy { Triple(it.workingDirectory + "/" + it.manifest, it.action, it.argv) }
    }

    private fun scripts(source: String): Set<String> = runCatching {
        val root = Json.parseToJsonElement(source).jsonObject
        val scriptObject = root["scripts"]?.jsonObject ?: JsonObject(emptyMap())
        scriptObject.keys
    }.getOrDefault(emptySet())

    private fun normalize(path: String): String = path.replace('\\', '/').trim('/').lowercase()
    private fun basename(path: String): String = path.substringAfterLast('/')
    private fun parent(path: String): String = path.substringBeforeLast('/', "")
    private fun isInside(path: String, directory: String): Boolean = directory.isEmpty() || path.startsWith("$directory/")
}

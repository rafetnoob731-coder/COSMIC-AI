package com.cosmic.ai.core.project

/** Pure Kotlin project fingerprinting. It reports evidence, never guesses that a toolchain is installed. */
data class TechnologyFinding(
    val name: String,
    val kind: Kind,
    val evidence: Set<String>
) {
    enum class Kind { LANGUAGE, FRAMEWORK, PACKAGE_MANAGER, INFRASTRUCTURE, DATABASE }
}

object ProjectTechnologyDetector {
    private val extensionLanguages = mapOf(
        "py" to "Python", "pyi" to "Python", "js" to "JavaScript", "mjs" to "JavaScript", "cjs" to "JavaScript",
        "jsx" to "JavaScript", "ts" to "TypeScript", "tsx" to "TypeScript", "java" to "Java", "kt" to "Kotlin",
        "kts" to "Kotlin", "c" to "C", "h" to "C", "cc" to "C++", "cpp" to "C++", "cxx" to "C++",
        "hpp" to "C++", "cs" to "C#", "go" to "Go", "rs" to "Rust", "php" to "PHP", "rb" to "Ruby",
        "swift" to "Swift", "dart" to "Dart", "html" to "HTML", "css" to "CSS", "scss" to "SCSS",
        "sql" to "SQL", "sh" to "Shell", "bash" to "Shell", "ps1" to "PowerShell", "yaml" to "YAML",
        "yml" to "YAML", "json" to "JSON", "toml" to "TOML", "tf" to "Terraform / HCL"
    )

    private val manifestLanguages = mapOf(
        "requirements.txt" to "Python", "pyproject.toml" to "Python", "pipfile" to "Python", "setup.py" to "Python",
        "package.json" to "JavaScript", "tsconfig.json" to "TypeScript", "cargo.toml" to "Rust", "go.mod" to "Go",
        "pom.xml" to "Java", "build.gradle" to "Java", "build.gradle.kts" to "Kotlin", "settings.gradle.kts" to "Kotlin",
        "pubspec.yaml" to "Dart", "composer.json" to "PHP", "gemfile" to "Ruby", "dockerfile" to "Docker",
        "docker-compose.yml" to "Docker", "docker-compose.yaml" to "Docker", "terraform.tf" to "Terraform / HCL"
    )

    fun detect(paths: Iterable<String>, manifestContents: Map<String, String> = emptyMap()): List<TechnologyFinding> {
        val pathList = paths.map { it.replace('\\', '/').lowercase() }
        val normalized = pathList.map { it.substringAfterLast('/') }.toSet()
        val evidence = linkedMapOf<String, MutableSet<String>>()
        normalized.forEach { file ->
            manifestLanguages[file]?.let { evidence.getOrPut(it) { linkedSetOf() }.add(file) }
            val extension = file.substringAfterLast('.', "")
            extensionLanguages[extension]?.let { evidence.getOrPut(it) { linkedSetOf() }.add("*.$extension") }
        }
        val findings = evidence.map { (name, hits) ->
            TechnologyFinding(name, if (name == "Docker" || name.startsWith("Terraform")) TechnologyFinding.Kind.INFRASTRUCTURE else TechnologyFinding.Kind.LANGUAGE, hits)
        }.toMutableList()
        if ("package.json" in normalized) {
            val manifest = normalized
            findings += TechnologyFinding("Node.js", TechnologyFinding.Kind.FRAMEWORK, setOf("package.json"))
            val packageJson = manifestContents.entries.firstOrNull { it.key.substringAfterLast('/').equals("package.json", true) }?.value.orEmpty()
            if ("next" in packageJson) findings += TechnologyFinding("Next.js", TechnologyFinding.Kind.FRAMEWORK, setOf("package.json dependency"))
            if ("react" in packageJson) findings += TechnologyFinding("React", TechnologyFinding.Kind.FRAMEWORK, setOf("package.json dependency"))
            if ("vue" in packageJson) findings += TechnologyFinding("Vue", TechnologyFinding.Kind.FRAMEWORK, setOf("package.json dependency"))
            if ("@angular/core" in packageJson) findings += TechnologyFinding("Angular", TechnologyFinding.Kind.FRAMEWORK, setOf("package.json dependency"))
            if ("svelte" in packageJson) findings += TechnologyFinding("Svelte", TechnologyFinding.Kind.FRAMEWORK, setOf("package.json dependency"))
            if ("express" in packageJson) findings += TechnologyFinding("Express", TechnologyFinding.Kind.FRAMEWORK, setOf("package.json dependency"))
            if ("nestjs" in packageJson || "@nestjs/" in packageJson) findings += TechnologyFinding("NestJS", TechnologyFinding.Kind.FRAMEWORK, setOf("package.json dependency"))
            if ("next.config.js" in manifest || "next.config.mjs" in manifest || "next.config.ts" in manifest) findings += TechnologyFinding("Next.js", TechnologyFinding.Kind.FRAMEWORK, manifest.filter { it.startsWith("next.config.") }.toSet())
            if ("vite.config.ts" in manifest || "vite.config.js" in manifest) findings += TechnologyFinding("Vite", TechnologyFinding.Kind.FRAMEWORK, manifest.filter { it.startsWith("vite.config.") }.toSet())
            if ("pnpm-lock.yaml" in manifest) findings += TechnologyFinding("pnpm", TechnologyFinding.Kind.PACKAGE_MANAGER, setOf("pnpm-lock.yaml"))
            else if ("yarn.lock" in manifest) findings += TechnologyFinding("Yarn", TechnologyFinding.Kind.PACKAGE_MANAGER, setOf("yarn.lock"))
            else findings += TechnologyFinding("npm", TechnologyFinding.Kind.PACKAGE_MANAGER, setOf("package.json"))
        }
        if ("requirements.txt" in normalized || "pyproject.toml" in normalized) {
            findings += TechnologyFinding("Python dependencies", TechnologyFinding.Kind.PACKAGE_MANAGER, normalized.filter { it in setOf("requirements.txt", "pyproject.toml") }.toSet())
            val pythonManifest = manifestContents.entries.filter { it.key.substringAfterLast('/').lowercase() in setOf("requirements.txt", "pyproject.toml") }.joinToString("\n") { it.value }.lowercase()
            listOf("FastAPI" to "fastapi", "Django" to "django", "Flask" to "flask").forEach { (framework, token) ->
                if (pythonManifest.contains(token)) findings += TechnologyFinding(framework, TechnologyFinding.Kind.FRAMEWORK, setOf("Python dependency manifest"))
            }
        }
        if ("pubspec.yaml" in normalized) {
            val pubspec = manifestContents.entries.firstOrNull { it.key.substringAfterLast('/').equals("pubspec.yaml", true) }?.value.orEmpty()
            if ("flutter:" in pubspec) findings += TechnologyFinding("Flutter", TechnologyFinding.Kind.FRAMEWORK, setOf("pubspec.yaml"))
        }
        if ("pom.xml" in normalized || "build.gradle" in normalized || "build.gradle.kts" in normalized) {
            val buildFiles = manifestContents.entries.filter { it.key.substringAfterLast('/').lowercase() in setOf("pom.xml", "build.gradle", "build.gradle.kts") }.joinToString("\n") { it.value }.lowercase()
            if ("spring-boot" in buildFiles) findings += TechnologyFinding("Spring Boot", TechnologyFinding.Kind.FRAMEWORK, setOf("Java/Kotlin build manifest"))
        }
        if ("composer.json" in normalized) {
            val composer = manifestContents.entries.firstOrNull { it.key.substringAfterLast('/').equals("composer.json", true) }?.value.orEmpty()
            if ("laravel/framework" in composer) findings += TechnologyFinding("Laravel", TechnologyFinding.Kind.FRAMEWORK, setOf("composer.json dependency"))
        }
        if ("docker-compose.yml" in normalized || "docker-compose.yaml" in normalized) findings += TechnologyFinding("Docker Compose", TechnologyFinding.Kind.INFRASTRUCTURE, normalized.filter { it.startsWith("docker-compose") }.toSet())
        if ("schema.sql" in normalized || "migration.sql" in normalized) findings += TechnologyFinding("SQL database", TechnologyFinding.Kind.DATABASE, normalized.filter { it.endsWith(".sql") }.toSet())
        return findings.distinctBy { it.name }.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
    }

}

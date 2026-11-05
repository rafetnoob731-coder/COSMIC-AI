package com.cosmic.ai.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTechnologyDetectorTest {
    @Test fun detectsMixedLanguageRepositoryAndManifests() {
        val result = ProjectTechnologyDetector.detect(
            listOf("backend/requirements.txt", "backend/app/main.py", "web/package.json", "web/src/App.tsx", "db/schema.sql", "infra/Dockerfile", "Cargo.toml", "src/lib.rs"),
            mapOf("web/package.json" to """{"dependencies":{"react":"^18","next":"^14"}}""")
        )
        val names = result.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("Python", "JavaScript", "TypeScript", "React", "Next.js", "Rust", "SQL", "Docker", "Node.js")))
    }

    @Test fun discoversFrameworkFromManifestWithoutSourceExtension() {
        val result = ProjectTechnologyDetector.detect(listOf("package.json", "pnpm-lock.yaml"))
        assertEquals("pnpm", result.first { it.kind == TechnologyFinding.Kind.PACKAGE_MANAGER }.name)
    }

    @Test fun recognizesFrameworksOnlyFromReadableDependencyManifests() {
        val result = ProjectTechnologyDetector.detect(
            listOf("pyproject.toml", "pubspec.yaml", "build.gradle.kts", "composer.json"),
            mapOf(
                "pyproject.toml" to "dependencies = ['fastapi']",
                "pubspec.yaml" to "dependencies:\n  flutter: {sdk: flutter}",
                "build.gradle.kts" to "implementation(\"org.springframework.boot:spring-boot-starter\")",
                "composer.json" to "{\"laravel/framework\": \"^11\"}"
            )
        ).map { it.name }.toSet()
        assertTrue(result.containsAll(setOf("FastAPI", "Flutter", "Spring Boot", "Laravel")))
    }

    @Test fun unknownProjectHasNoInventedTechnologies() {
        assertTrue(ProjectTechnologyDetector.detect(listOf("README.md", "LICENSE")).isEmpty())
    }

    @Test fun runtimeCatalogOnlyDescribesCandidatesAndUnknownLanguageIsUnsupported() {
        assertEquals(null, RuntimeCatalog.forLanguage("Brainfuck"))
        assertTrue(RuntimeCatalog.forLanguage("Rust")!!.executables.contains("cargo"))
    }
}

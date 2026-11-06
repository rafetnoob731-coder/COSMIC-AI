package com.cosmic.ai.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectBuildPlannerTest {
    @Test fun nodePlanUsesOnlyScriptsActuallyDeclaredInManifestAndKeepsSubprojectDirectory() {
        val plans = ProjectBuildPlanner.create(
            setOf("web/package.json", "web/pnpm-lock.yaml"),
            mapOf("web/package.json" to """{"scripts":{"build":"vite build","test":"vitest"}}""")
        )
        assertEquals(setOf(ProjectBuildPlan.Action.BUILD, ProjectBuildPlan.Action.TEST), plans.map { it.action }.toSet())
        assertTrue(plans.all { it.workingDirectory == "web" && it.argv.first() == "pnpm" && it.requiresApproval })
    }

    @Test fun missingScriptsOrNestedManifestDoNotInventRunnablePlans() {
        assertTrue(ProjectBuildPlanner.create(setOf("package.json"), mapOf("package.json" to "{}" )).isEmpty())
        assertTrue(ProjectBuildPlanner.create(setOf("nested/package.json"), mapOf("nested/package.json" to "{}")).isEmpty())
    }

    @Test fun rustAndGoPlansAreExplicitCandidatesAndMarkDependencyResolutionNetworkRisk() {
        val plans = ProjectBuildPlanner.create(setOf("Cargo.toml", "go.mod"), emptyMap())
        assertTrue(plans.any { it.manifest == "Cargo.toml" && it.action == ProjectBuildPlan.Action.TEST && it.argv == listOf("cargo", "test") })
        assertTrue(plans.any { it.manifest == "go.mod" && it.action == ProjectBuildPlan.Action.TEST })
        assertTrue(plans.any { it.manifest == "Cargo.toml" && it.action == ProjectBuildPlan.Action.DIAGNOSTICS })
        assertTrue(plans.all { it.requiresApproval })
    }

    @Test fun cargoWorkspaceAddsWorkspaceFlag() {
        val plans = ProjectBuildPlanner.create(
            setOf("Cargo.toml"),
            mapOf("Cargo.toml" to "[workspace]\nmembers = [\"crate-a\", \"crate-b\"]")
        )
        assertTrue(plans.any { it.action == ProjectBuildPlan.Action.BUILD && it.argv == listOf("cargo", "build", "--workspace") })
        assertTrue(plans.any { it.action == ProjectBuildPlan.Action.TEST && it.argv == listOf("cargo", "test", "--workspace") })
        assertTrue(plans.any { it.action == ProjectBuildPlan.Action.DIAGNOSTICS && it.argv == listOf("cargo", "clippy", "--workspace") })
    }

    @Test fun goWorkspaceGeneratesSyncPlan() {
        val plans = ProjectBuildPlanner.create(setOf("go.work", "service/go.mod"), emptyMap())
        assertTrue(plans.any { it.manifest == "go.work" && it.argv == listOf("go", "work", "sync") })
        assertTrue(plans.any { it.manifest == "service/go.mod" && it.workingDirectory == "service" && it.action == ProjectBuildPlan.Action.BUILD })
    }

    @Test fun packagePlansPreserveWorkspacePathCaseAndUseOnlyTheirOwnLockfile() {
        val plans = ProjectBuildPlanner.create(
            setOf("WebApp/package.json", "WebApp/package-lock.json", "Other/pnpm-lock.yaml"),
            mapOf("WebApp/package.json" to """{"scripts":{"build":"vite build"}}""")
        )
        assertEquals(1, plans.size)
        assertEquals("WebApp", plans.single().workingDirectory)
        assertEquals("WebApp/package.json", plans.single().manifest)
        assertEquals(listOf("npm", "run", "build"), plans.single().argv)
        assertTrue(plans.single().requiresApproval)
    }

    @Test fun nestedManifestsProduceScopedCandidatePathsWithoutClaimingExecution() {
        val plans = ProjectBuildPlanner.create(setOf("Services/API/Cargo.toml"), emptyMap())
        assertTrue(plans.isNotEmpty())
        assertTrue(plans.all { it.workingDirectory == "Services/API" && it.manifest == "Services/API/Cargo.toml" })
        assertTrue(plans.all { it.requiresApproval })
    }
}

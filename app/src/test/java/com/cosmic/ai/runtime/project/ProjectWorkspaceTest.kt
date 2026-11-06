package com.cosmic.ai.runtime.project

import android.app.Application
import com.cosmic.ai.runtime.project.WorkspaceResult.Code
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectWorkspaceTest.TestApplication::class, sdk = [35])
class ProjectWorkspaceTest {
    class TestApplication : Application()

    @Test fun internalWorkspaceWritesAndReadsWithinItsRootAndRejectsTraversal() = runBlocking {
        val workspace = InternalProjectWorkspaceProvider.create(RuntimeEnvironment.getApplication(), "unit-project")
        assertTrue(workspace.createDirectory("src") is WorkspaceResult.Success)
        assertTrue(workspace.write("src/index.html", "<h1>ok</h1>".toByteArray()) is WorkspaceResult.Success)
        val read = workspace.read("src/index.html") as WorkspaceResult.Success
        assertEquals("<h1>ok</h1>", read.value.toString(Charsets.UTF_8))
        val traversal = workspace.write("../outside.txt", byteArrayOf(1)) as WorkspaceResult.Failure
        assertEquals(Code.INVALID_PATH, traversal.code)
        assertTrue(workspace.read("../../outside.txt") is WorkspaceResult.Failure)
    }

    @Test fun archiveImportExtractsProjectButRejectsZipSlip() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val importer = ProjectArchiveImporter(context)
        val valid = importer.import(ByteArrayInputStream(zipOf("site/index.html" to "hello")), "site") as WorkspaceResult.Success
        val data = valid.value.read("site/index.html") as WorkspaceResult.Success
        assertEquals("hello", data.value.toString(Charsets.UTF_8))

        val malicious = importer.import(ByteArrayInputStream(zipOf("../escape.txt" to "bad")), "bad") as WorkspaceResult.Failure
        assertEquals(Code.IO, malicious.code)
        assertFalse(java.io.File(context.filesDir, "codex-workspaces/escape.txt").exists())
    }

    @Test fun archiveImportSanitizesWorkspaceNames() = runBlocking {
        val result = ProjectArchiveImporter(RuntimeEnvironment.getApplication())
            .import(ByteArrayInputStream(zipOf("one.txt" to "ok")), "../../safe") as WorkspaceResult.Success
        assertTrue(result.value.workspaceId.matches(Regex("[A-Za-z0-9._-]{1,80}")))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

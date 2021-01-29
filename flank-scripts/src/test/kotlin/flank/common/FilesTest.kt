package flank.common

import org.junit.Assert
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

internal class FilesTest {

    @get:Rule
    val root = TemporaryFolder()

    @Test
    fun `Should create symbolic file at desired location`() {
        Assume.assumeFalse(isWindows)
        // given
        val testFile = root.newFile("test.file").toPath()
        val expectedDestination = Paths.get(root.newFolder("temp").toString(), "test.link")

        // when
        createSymbolicLinkToFile(expectedDestination, testFile)

        // then
        Assert.assertTrue(Files.isSymbolicLink(expectedDestination))

        // clean
        testFile.toFile().delete()
        expectedDestination.toFile().delete()
    }

    @Test
    fun `Should check if directory contains all needed files`() {
        // given
        val testDirectory = root.newFolder("test").toPath()
        val testFiles = listOf(
            Paths.get(testDirectory.toString(), "testFile1"),
            Paths.get(testDirectory.toString(), "testFile2"),
            Paths.get(testDirectory.toString(), "testFile3"),
            Paths.get(testDirectory.toString(), "testFile4")
        )

        testFiles.forEach { Files.createFile(it) }

        // when
        val resultTrue = testDirectory.toFile().hasAllFiles(testFiles.map { it.fileName.toString() })
        val resultFalse = testDirectory.toFile()
            .hasAllFiles(
                (testFiles + Paths.get(testDirectory.toString(), "testFile5"))
                    .map { it.fileName.toString() }
            )

        // then
        Assert.assertTrue(resultTrue)
        Assert.assertFalse(resultFalse)

        // clean
        testDirectory.toFile().deleteRecursively()
    }
}
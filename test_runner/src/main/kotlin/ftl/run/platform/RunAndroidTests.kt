package ftl.run.platform

import com.google.api.services.testing.Testing
import com.google.api.services.testing.model.TestMatrix
import ftl.args.AndroidArgs
import ftl.gc.GcAndroidDevice
import ftl.gc.GcAndroidTestMatrix
import ftl.gc.GcToolResults
import ftl.http.executeWithRetry
import ftl.run.ANDROID_SHARD_FILE
import ftl.run.model.InstrumentationTestContext
import ftl.run.model.TestResult
import ftl.run.platform.android.createAndroidTestConfig
import ftl.run.platform.android.createAndroidTestContexts
import ftl.run.platform.android.upload
import ftl.run.platform.android.uploadAdditionalApks
import ftl.run.platform.android.uploadOtherFiles
import ftl.run.platform.common.afterRunTests
import ftl.run.platform.common.beforeRunMessage
import ftl.run.platform.common.beforeRunTests
import ftl.run.exception.FlankGeneralError
import ftl.run.model.AndroidMatrixTestShards
import ftl.run.model.AndroidTestContext
import ftl.run.platform.android.asMatrixTestShards
import ftl.run.saveShardChunks
import ftl.shard.Chunk
import ftl.shard.testCases
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal suspend fun runAndroidTests(args: AndroidArgs): TestResult = coroutineScope {
    val (stopwatch, runGcsPath) = beforeRunTests(args)

    // GcAndroidMatrix => GcAndroidTestMatrix
    // GcAndroidTestMatrix.execute() 3x retry => matrix id (string)
    val devices = GcAndroidDevice.build(args.devices)
    val testMatrices = mutableListOf<Deferred<TestMatrix>>()
    val allTestShardChunks = mutableListOf<Chunk>()
    val ignoredTestsShardChunks = mutableListOf<List<String>>()

    val history = GcToolResults.createToolResultsHistory(args)
    val otherGcsFiles = args.uploadOtherFiles(runGcsPath)
    val additionalApks = args.uploadAdditionalApks(runGcsPath)

    args.createAndroidTestContexts().dumpShards().upload(args.resultsBucket, runGcsPath)
        .forEachIndexed { index, context ->
            if (context is InstrumentationTestContext) {
                ignoredTestsShardChunks += context.ignoredTestCases
                allTestShardChunks += context.shards
            }
            val androidTestConfig = args.createAndroidTestConfig(context)
            testMatrices += executeAndroidTestMatrix(runCount = args.repeatTests) {
                GcAndroidTestMatrix.build(
                    androidTestConfig = androidTestConfig,
                    runGcsPath = "$runGcsPath/matrix_$index/",
                    additionalApkGcsPaths = additionalApks,
                    androidDeviceList = devices,
                    args = args,
                    otherFiles = otherGcsFiles,
                    toolResultsHistory = history
                )
            }
        }

    if (testMatrices.isEmpty()) throw FlankGeneralError("There are no tests to run.")

    println(beforeRunMessage(args, allTestShardChunks))

    TestResult(
        matrixMap = afterRunTests(testMatrices.awaitAll(), runGcsPath, stopwatch, args),
        shardChunks = allTestShardChunks.testCases,
        ignoredTests = ignoredTestsShardChunks.flatten()
    )
}

private fun List<AndroidTestContext>.dumpShards() = apply {
    filterIsInstance<InstrumentationTestContext>().asMatrixTestShards().saveShards()
}

private fun AndroidMatrixTestShards.saveShards() = saveShardChunks(
    shardFilePath = ANDROID_SHARD_FILE,
    shards = this,
    size = size,
    obfuscatedOutput = false
)

private suspend fun executeAndroidTestMatrix(
    runCount: Int,
    createTestMatrix: () -> Testing.Projects.TestMatrices.Create
): List<Deferred<TestMatrix>> = coroutineScope {
    (0 until runCount).map {
        async(Dispatchers.IO) {
            createTestMatrix().executeWithRetry()
        }
    }
}

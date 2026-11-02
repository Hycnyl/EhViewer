package com.ehviewer

import com.android.build.api.artifact.ArtifactTransformationRequest
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/** Only APKs signed with the pinned rotation lineage become final release artifacts. */
@DisableCachingByDefault(because = "Release signing uses protected local credentials")
abstract class RotatedReleaseApksTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputApks: DirectoryProperty

    @get:OutputDirectory
    abstract val outputApks: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val signingScript: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val signingPolicy: RegularFileProperty

    @get:Internal
    abstract val sdkDirectory: DirectoryProperty

    @get:OutputFile
    abstract val signingReport: RegularFileProperty

    @get:Internal
    abstract val artifactTransformationRequest: Property<ArtifactTransformationRequest<RotatedReleaseApksTask>>

    @get:Inject
    abstract val execOperations: ExecOperations

    init {
        outputs.upToDateWhen { false }
        notCompatibleWithConfigurationCache("Signing credentials must never enter the configuration cache")
    }

    @TaskAction
    fun sign() {
        execOperations.exec {
            commandLine(
                "python3", signingScript.get().asFile.absolutePath,
                "--input-dir", inputApks.get().asFile.absolutePath,
                "--output-dir", outputApks.get().asFile.absolutePath,
                "--sdk-dir", sdkDirectory.get().asFile.absolutePath,
                "--policy", signingPolicy.get().asFile.absolutePath,
                "--report", signingReport.get().asFile.absolutePath,
            )
        }.assertNormalExitValue()
        artifactTransformationRequest.get().submit(this) { artifact ->
            outputApks.file(File(artifact.outputFile).name.replace("-unsigned.apk", ".apk")).get().asFile
        }
    }
}

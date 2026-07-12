package io.openeden.trainer

import io.openeden.model.LocalModelArtifact
import io.openeden.model.LocalModelArtifactLoader
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

suspend fun main(args: Array<String>) {
    val cli = TrainerCli.parse(args.toList())
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    val corpus = json.decodeFromString(
        CodebookTrainingCorpus.serializer(),
        Files.readString(cli.samplesPath),
    )
    val trainer = LocalCodebookTrainer()
    val artifact = trainer.train(corpus)
    LocalModelArtifactLoader.write(cli.artifactPath, artifact)
    writeCodebookCsv(cli.codebookCsvPath, artifact)
    val report = TrainingReport(
        sampleCount = corpus.samples.size,
        nodeCount = artifact.vqVae.codebook.size,
        top1Accuracy = trainer.evaluate(corpus, artifact),
        artifactPath = cli.artifactPath.toString(),
        codebookCsvPath = cli.codebookCsvPath.toString(),
    )
    cli.reportPath.parent?.let(Files::createDirectories)
    Files.writeString(cli.reportPath, report.render())
    println(report.render())
}

private fun writeCodebookCsv(path: Path, artifact: LocalModelArtifact) {
    path.parent?.let(Files::createDirectories)
    Files.writeString(path, "\uFEFF${artifact.codebookCsv}")
}

private data class TrainerCli(
    val samplesPath: Path,
    val artifactPath: Path,
    val codebookCsvPath: Path,
    val reportPath: Path,
) {
    companion object {
        fun parse(args: List<String>): TrainerCli {
            val values = args.chunked(2).associate {
                require(it.size == 2 && it[0].startsWith("--")) {
                    usage()
                }
                it[0] to it[1]
            }
            return TrainerCli(
                samplesPath = Path(values["--samples"] ?: "data/training/codebook.samples.json"),
                artifactPath = Path(values["--artifact"] ?: "data/models/local-model-artifact.json"),
                codebookCsvPath = Path(values["--codebook-csv"] ?: "data/codebook/codebook.generated.csv"),
                reportPath = Path(values["--report"] ?: "build/reports/openeden-training-report.txt"),
            )
        }

        private fun usage(): String =
            "Usage: trainer --samples <json> --artifact <json> --codebook-csv <csv> --report <txt>"
    }
}

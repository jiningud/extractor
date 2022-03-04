import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.comments.JavadocComment
import com.github.javaparser.javadoc.Javadoc
import com.github.javaparser.javadoc.description.JavadocInlineTag
import com.github.javaparser.javadoc.description.JavadocSnippet
import edu.stanford.nlp.simple.Document
import me.tongfei.progressbar.ProgressBar
import org.jsoup.Jsoup
import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter

import java.util.concurrent.TimeUnit

import edu.stanford.nlp.simple.Sentence

import java.lang.RuntimeException

fun Javadoc.toPlainText(): String = description.elements.joinToString("") { element ->
    when (element) {
        is JavadocSnippet -> element.toText()
        is JavadocInlineTag -> {
            element.content.trim()
        }
        else -> error("Unknown JavadocDescriptionElement type: ${element::class.java}")
    }
}

fun String.removeTags(): String = Jsoup.parse(this)
    .apply {
        select("pre").forEach { it.text("code-example") }
    }
    .text()

class Extractor : CliktCommand(name = "extract", help = "Extract class-level documentation from javadoc") {

    init {
        context { helpFormatter = CliktHelpFormatter(showDefaultValues = true, showRequiredTag = true) }
    }

    private val numThreads by option("--num-threads", help = "number of threads")
        .int()
        .default(Runtime.getRuntime().availableProcessors())

    private val output by option(help = "output file (default: output.txt)")
        .file(mustExist = false)
        .default(File("classdoc.csv"))

    private val sources by argument(help = "<path> of the input files")
        .file(mustExist = true, mustBeReadable = true)
        .multiple()


    private fun gatherFiles(sources: List<File>): List<File> {
        return sources
            .flatMap { it.walk() }
            .filter { it.isFile }
            .filter { it.extension == "java" }
    }


    private fun extractJavadoc(file: File): Optional<Javadoc> {
        return JavaParser()
            .parse(file)
            .result
            .flatMap { it.primaryType }
            .flatMap { it.javadocComment }
            .map(JavadocComment::parse)
    }


    private fun extractSentences(javadoc: Javadoc): List<String> {
        return Document(javadoc.toPlainText().removeTags())
            .sentences()
            .map { it.text() }
            .filter { it.isNotBlank() }
    }


    private fun handleFile(file: File): List<String> {
        return try {
            extractJavadoc(file)
                .map(::extractSentences)
                .orElse(emptyList())
        } catch (e: Throwable) {
            System.err.println(file.path)
            emptyList()
        }
    }


    override fun run() {
        val writer = Files.newBufferedWriter(Paths.get(output.path))
        val executor = Executors.newFixedThreadPool(numThreads)
        val csvPrinter = CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("Class","Sentence"))

            ProgressBar("Extracting", -1).use { pb ->
                pb.extraMessage = "Gathering sources"
                val javaSources = gatherFiles(sources)
                pb.maxHint(javaSources.size.toLong())

                pb.extraMessage = "Processing sources"

                val latch = CountDownLatch(javaSources.size)

                for (source in javaSources) {
                    executor.submit {
                        val sentences = handleFile(source)
                        if (sentences.isNotEmpty()) {
                            for (sentence in sentences) {
                                synchronized(writer) { csvPrinter.printRecord(source.absolutePath, sentence)}
                            }
                        }
                        pb.step()
                        latch.countDown()
                    }
                }

                latch.await()

                pb.extraMessage = "Done"
                csvPrinter.flush()
                csvPrinter.close()
            }



        executor.shutdown()
    }
}

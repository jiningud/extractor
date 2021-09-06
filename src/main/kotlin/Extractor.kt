import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.expr.*
import com.github.javaparser.javadoc.description.*
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.comments.CommentsCollection
import me.tongfei.progressbar.ProgressBar
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import edu.stanford.nlp.simple.Document
import edu.stanford.nlp.simple.Sentence
import java.nio.file.Files
import java.nio.file.Paths
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter

class `Extractor` : CliktCommand(name = "extract", help="Extract class-level documentation from javadoc"){

    init{
        context { helpFormatter = CliktHelpFormatter(showDefaultValues = true, showRequiredTag = true)}
    }

    private val numThreads by option("--num-threads", help = "number of threads")
        .int()
        .default(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
    private val output by option(help = "output file (default: classdoc.csv)")
        .file(exists = false)
        .default(File("classdoc.csv"))
    private val sources by argument(help = "<path> of the input files")
        .file(exists = true, readable = true)
        .multiple()
    private fun extractClassdocs(file: File) : Sequence<String> = sequence{
        val className = file.nameWithoutExtension
        if (className.first().isLowerCase()) return@sequence
        if (file.extension == "java"){
            extractJava(file)
        }
        else if (file.extension == "html") {
            extractHtml(file)
        }
    }
    private fun extractHtml(file: File) : Sequence<String> = sequence{
        val document = Jsoup.parse(file, Charsets.UTF_8.name())
        val description = document.select(".description .block").text()
        val sentences = Document(description).sentences()
        for (sen in sentences){
            val sentence = sen.toString()
            yield(sentence)
        }
    }
    private fun extractJava(file: File) : Sequence<String> = sequence{
        val parser = JavaParser()
        val comments = parser.parse(file).commentsCollection
        val sentences : MutableList<Sentence> = mutableListOf()
        parser.parse(file).commentsCollection.ifPresent { commentsCollection ->
            for (javadocComment in commentsCollection.javadocComments) {
                javadocComment.commentedNode.ifPresent { commentedNode ->
                    if(commentedNode is ClassOrInterfaceDeclaration) {
                        val description = javadocComment
                            .parse()
                            .description
                            .toText()
                        sentences.addAll(Document(description).sentences())


                    }

                }
            }
        }
        for (sen in sentences) {
            val sentence = sen.toString()
            yield(sentence)
        }

    }

    override fun run() {
        val writer = Files.newBufferedWriter(Paths.get(output.path))
        val csvPrinter = CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("Class", "Sentence"))
        ProgressBar("Extracting", -1).use { pb ->
            pb.extraMessage = "Gathering sources"

            val javaSources : List<File> = sources
                .flatMap { it.walk().toList()}
                .filter { it.isFile}
                .filter {it.extension == "html" || it.extension == "java"}

            val executor = Executors.newFixedThreadPool(numThreads)

            pb.extraMessage = "Processing sources"
            pb.maxHint(javaSources.size.toLong())

            val latch = CountDownLatch(javaSources.size)




            for (source in javaSources) {
                executor.submit{
                    print("p")
                    if (source.extension == "java") {
                        extractJava(source)
                            .forEach {
                                synchronized(writer) { csvPrinter.printRecord(source.nameWithoutExtension, it) }
                            }
                    } else if (source.extension == "html") {
                        extractHtml(source)
                            .forEach {
                                synchronized(writer) { csvPrinter.printRecord(source.nameWithoutExtension, it) }
                            }
                    }

                    latch.countDown()
                    pb.step()
                }
            }
            latch.await()
            pb.extraMessage = "Done"
            csvPrinter.flush()
            csvPrinter.close()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.MINUTES)
        }

    }
}
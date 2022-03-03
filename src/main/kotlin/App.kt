    import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

class App : CliktCommand() {
    override fun run() = Unit
}

fun main(args: Array<String>) = App()
    .subcommands(
        Extractor()
    )
    .main(args)


package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Writes the list of the libraries a variant ships, each with its licence and the file that holds
 * the notice its licence asks to go with every copy (`licences/libraries.txt`, an asset the credits
 * screen reads), and fails when a library lacks that notice (#488).
 *
 * A library's licence is the one its POM names, or, when the POM names none, its parent POM's. A
 * library under the Apache License 2.0 alone takes the license's text (`apache-2.0.txt`); any other
 * licence, and an Apache-2.0 library that carries a NOTICE file, needs a line in
 * `config/licence-notices.txt` naming its notice. The build fails on a licence it does not know, on
 * a library with no line it needs, on a line that matches no shipped library or gives another
 * licence than the POM, and on a notice without the licence's text, without a copyright line, or
 * without the copyright lines of the library's own LICENSE file or the text of its NOTICE file.
 *
 * The notices are assets, not the libraries' own `META-INF` files: the Android Gradle plugin's
 * default packaging excludes drop `META-INF/LICENSE.txt` (SLF4J's) from the APK, and the BSD text
 * DataStore carries for its copy of Protocol Buffers has no copyright line.
 */
abstract class ListLibraryLicences : DefaultTask() {
    /** The shipped modules, as `group:name:version` (`CheckDependencies.modulesOf`). */
    @get:Input
    abstract val modules: ListProperty<String>

    /** The modules' POMs and their parents' (`sleevesight.android-app`, *licence POMs*). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val poms: ConfigurableFileCollection

    /** The modules' jars and AARs, read for LICENSE and NOTICE files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifactFiles: ConfigurableFileCollection

    /**
     * Each of [artifactFiles] with its module, as `group:name:version` and the file's path, a tab
     * between; a file may be listed under more than one module. [artifactFiles] is the input.
     */
    @get:Internal
    abstract val artifactModules: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticeTable: RegularFileProperty

    /** The folder of the notices, `app/src/main/assets/licences`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticeDirectory: DirectoryProperty

    /** A generated assets folder: the list goes in its `licences/libraries.txt`. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun list() {
        val pomsByModule = poms.files.map { parsePom(it.readText(), it.name) }.associateBy { it.coordinates }
        val bundled =
            artifactModules.get()
                .map { it.split('\t', limit = 2) }
                .groupBy({ it[0] }, { bundledFiles(File(it[1])) })
                .mapValues { (_, all) -> Bundled(all.flatMap { it.licenses }, all.flatMap { it.notices }) }
        val table = parseNoticeTable(noticeTable.get().asFile.readText())
        val directory = noticeDirectory.get().asFile
        val result =
            libraries(
                modules = modules.get(),
                licences = { licencesOf(it, pomsByModule) },
                bundled = { bundled[it] ?: Bundled(emptyList(), emptyList()) },
                table = table,
                notice = { name -> File(directory, name).takeIf { it.isFile }?.readText() },
            )
        if (result.problems.isNotEmpty()) {
            throw GradleException(
                "Library licences and their notices (config/licence-notices.txt, #488):\n" +
                    result.problems.joinToString("\n") { "  $it" },
            )
        }
        val out = outputDirectory.get().asFile.resolve("licences").apply { mkdirs() }
        out.resolve(LIST).writeText(render(result.libraries))
    }

    /** One `<license>` of a POM. */
    data class PomLicence(
        val name: String,
        val url: String,
    )

    data class Pom(
        val coordinates: String,
        val parent: String?,
        val licences: List<PomLicence>,
    )

    /** The texts of the LICENSE and NOTICE files in a module's artifacts. */
    data class Bundled(
        val licenses: List<String>,
        val notices: List<String>,
    )

    /** A line of `licence-notices.txt`. */
    data class Entry(
        val module: String,
        val licence: String,
        val notice: String,
        val name: String,
    )

    /** A shipped library, its licence, and the asset with its notice; [name] for those with a line. */
    data class Library(
        val module: String,
        val licence: String,
        val notice: String,
        val name: String,
    )

    data class Result(
        val libraries: List<Library>,
        val problems: List<String>,
    )

    companion object {
        const val LIST = "libraries.txt"
        const val APACHE = "Apache-2.0"
        const val APACHE_NOTICE = "apache-2.0.txt"

        /** How many POMs up from a module its licence is looked for: the module's and three parents. */
        const val POM_LEVELS = 4

        /** Words each licence's text has, so a notice holds the licence, not only its name. */
        private val TEXTS =
            mapOf(
                "MIT" to listOf("Permission is hereby granted, free of charge, to any person obtaining a copy"),
                "BSD-3-Clause" to
                    listOf(
                        "Redistributions in binary form must reproduce the above copyright notice",
                        "Neither the name of",
                    ),
            )

        private val COPYRIGHT = Regex("""^\s*Copyright\b.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

        /** The Apache License's own appendix, which shows how to apply it, and other templates. */
        private val TEMPLATE = Regex("""\[yyyy]|<year>|\{yyyy}""", RegexOption.IGNORE_CASE)

        private val LICENSE_FILE = Regex("""^(LICEN[CS]E|COPYING)([._-].*)?$""", RegexOption.IGNORE_CASE)
        private val NOTICE_FILE = Regex("""^NOTICE([._-].*)?$""", RegexOption.IGNORE_CASE)

        /** The SPDX identifier of a POM's licence, for the licences the build knows; null for others. */
        fun spdx(licence: PomLicence): String? {
            val name = licence.name.lowercase().trim()
            val url = licence.url.lowercase()
            return when {
                "apache.org/licenses/license-2.0" in url || Regex("""apache.*2\.0""").containsMatchIn(name) -> APACHE
                name == "mit" || "mit license" in name || Regex("""opensource\.org/licenses?/mit""").containsMatchIn(url) -> "MIT"
                "bsd-3-clause" in name || "bsd 3-clause" in name || "new bsd" in name ||
                    "opensource.org/licenses/bsd-3-clause" in url -> "BSD-3-Clause"
                else -> null
            }
        }

        /**
         * A POM's coordinates (the group and version may come from its parent), parent and licences.
         * The version is the one in [fileName], `name-version.pom`, when given, since a POM may write
         * its own as a property.
         */
        fun parsePom(
            text: String,
            fileName: String? = null,
        ): Pom {
            val factory = DocumentBuilderFactory.newInstance()
            // A POM is data: no DTDs, no external entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.isNamespaceAware = false
            val root = factory.newDocumentBuilder().parse(text.byteInputStream()).documentElement
            val parent = root.child("parent")
            val parentCoordinates =
                parent?.let { listOf(it.text("groupId"), it.text("artifactId"), it.text("version")) }
            val group = root.text("groupId") ?: parentCoordinates?.get(0)
            val artifact = root.text("artifactId")
            val version =
                fileName
                    ?.takeIf { it.startsWith("$artifact-") && it.endsWith(".pom") }
                    ?.removePrefix("$artifact-")
                    ?.removeSuffix(".pom")
                    ?: root.text("version")
                    ?: parentCoordinates?.get(2)
            val licences =
                root.child("licenses")?.children("license").orEmpty().map {
                    PomLicence(it.text("name").orEmpty(), it.text("url").orEmpty())
                }
            return Pom(
                "$group:$artifact:$version",
                parentCoordinates?.takeIf { c -> c.all { it != null } }?.joinToString(":"),
                licences,
            )
        }

        /** The licences of [module]'s POM, or its nearest parent's that names any; null if none does. */
        fun licencesOf(
            module: String,
            poms: Map<String, Pom>,
        ): List<PomLicence>? {
            var next: String? = module
            repeat(POM_LEVELS) {
                val pom = poms[next ?: return null] ?: return null
                if (pom.licences.isNotEmpty()) return pom.licences
                next = pom.parent
            }
            return null
        }

        /** The parent of each of [poms] (POM texts), as `group:name:version`. */
        fun parentsOf(poms: List<String>): List<String> = poms.mapNotNull { parsePom(it).parent }.distinct()

        /** The rows of `licence-notices.txt`: comments and blank lines dropped. */
        fun parseNoticeTable(text: String): List<Entry> =
            text.lines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .map { line ->
                    val parts = line.split(Regex("\\s+"), limit = 4)
                    require(parts.size == 4) { "licence-notices.txt: not `module licence notice name`: $line" }
                    Entry(parts[0], parts[1], parts[2], parts[3])
                }

        /** Each library with its licence and notice, and what is wrong, one line each. */
        @Suppress("CyclomaticComplexMethod", "LongMethod") // One pass over the rules the KDoc lists.
        fun libraries(
            modules: List<String>,
            licences: (String) -> List<PomLicence>?,
            bundled: (String) -> Bundled,
            table: List<Entry>,
            notice: (String) -> String?,
        ): Result {
            val problems = mutableListOf<String>()
            val used = mutableSetOf<Entry>()
            val libraries =
                modules.mapNotNull { module ->
                    val pomLicences = licences(module)
                    if (pomLicences == null) {
                        problems += "$module: no licence in its POM or its parents'"
                        return@mapNotNull null
                    }
                    val ids = pomLicences.map { spdx(it) }
                    if (null in ids) {
                        val unknown = pomLicences.filter { spdx(it) == null }.joinToString { "${it.name} <${it.url}>" }
                        problems += "$module: a licence the build does not know: $unknown; review it and add it to the check"
                        return@mapNotNull null
                    }
                    val others = ids.filterNotNull().distinct().filter { it != APACHE }
                    val files = bundled(module)
                    val entry = table.firstOrNull { CheckDependencies.moduleMatches(it.module, module) }
                    entry?.let { used += it }
                    when {
                        others.size > 1 -> {
                            problems += "$module: more than one licence besides Apache-2.0 (${others.joinToString()}); review it"
                            null
                        }
                        entry == null && (others.isNotEmpty() || files.notices.isNotEmpty()) -> {
                            val why = others.singleOrNull() ?: "Apache-2.0 with a NOTICE file"
                            problems += "$module: $why, with no line in licence-notices.txt naming its notice"
                            null
                        }
                        entry == null -> Library(module, APACHE, APACHE_NOTICE, "")
                        else -> {
                            val licence = others.singleOrNull() ?: APACHE
                            if (entry.licence != licence) {
                                problems += "$module: licence-notices.txt says ${entry.licence}, its POM $licence"
                            } else if (licence == APACHE && files.notices.isEmpty()) {
                                problems += "$module: Apache-2.0 with no NOTICE file needs no line in licence-notices.txt"
                            }
                            problems += noticeProblems(module, entry, licence, files, notice(entry.notice))
                            Library(module, licence, entry.notice, entry.name)
                        }
                    }
                }
            problems += (table - used).map { "licence-notices.txt: ${it.module} matches no shipped library" }
            return Result(libraries, problems)
        }

        private fun noticeProblems(
            module: String,
            entry: Entry,
            licence: String,
            files: Bundled,
            text: String?,
        ): List<String> {
            if (text == null) return listOf("$module: its notice, ${entry.notice}, is not in assets/licences")
            val flat = flatten(text)
            val problems = mutableListOf<String>()
            TEXTS[licence].orEmpty().filterNot { flatten(it) in flat }.forEach {
                problems += "$module: ${entry.notice} lacks the $licence text (\"$it\")"
            }
            if (COPYRIGHT.findAll(text).none()) problems += "$module: ${entry.notice} has no copyright line"
            copyrightLines(files.licenses).filterNot { flatten(it) in flat }.forEach {
                problems += "$module: ${entry.notice} lacks the library's own \"$it\""
            }
            files.notices.filterNot { flatten(it) in flat }.forEach { _ ->
                problems += "$module: ${entry.notice} lacks the text of the library's NOTICE file"
            }
            return problems
        }

        /** The copyright lines of LICENSE texts, templates left out. */
        fun copyrightLines(licenses: List<String>): List<String> =
            licenses.flatMap { COPYRIGHT.findAll(it).map { m -> m.value.trim() }.toList() }
                .filterNot { TEMPLATE.containsMatchIn(it) }
                .distinct()

        /** The LICENSE and NOTICE files in a jar or AAR (and the `classes.jar` in an AAR). */
        fun bundledFiles(artifact: File): Bundled =
            if (artifact.extension in setOf("jar", "aar")) {
                artifact.inputStream().use { bundledFiles(it) }
            } else {
                Bundled(emptyList(), emptyList())
            }

        private fun bundledFiles(stream: InputStream): Bundled {
            val licenses = mutableListOf<String>()
            val notices = mutableListOf<String>()
            val zip = ZipInputStream(stream)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/')
                when {
                    entry.name == "classes.jar" ->
                        bundledFiles(zip.readBytes().inputStream()).let {
                            licenses += it.licenses
                            notices += it.notices
                        }
                    NOTICE_FILE.matches(base) -> notices += zip.readBytes().decodeToString()
                    LICENSE_FILE.matches(base) -> licenses += zip.readBytes().decodeToString()
                }
            }
            return Bundled(licenses, notices)
        }

        /** The asset: a comment, then `module, licence, notice, name` separated by tabs. */
        fun render(libraries: List<Library>): String =
            "# Written by the build (listLibraryLicences) from each library's POM and config/licence-notices.txt.\n" +
                "# module\tlicence\tnotice\tname\n" +
                libraries.sortedBy { it.module }.joinToString("") { "${it.module}\t${it.licence}\t${it.notice}\t${it.name}\n" }

        private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ").trim()

        private fun Element.child(tag: String): Element? = children(tag).firstOrNull()

        private fun Element.children(tag: String): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

        private fun Element.text(tag: String): String? = child(tag)?.textContent?.trim()
    }
}

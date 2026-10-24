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
 * licence needs a line in `config/licence-notices.txt` naming its notice. So does a library whose
 * jar or AAR carries a NOTICE file, or whose AAR brings assets into the APK, which may be under
 * terms of their own (OkHttp's Public Suffix List). A line may add a notice the POM cannot show: an
 * upstream NOTICE file the jar leaves out, or the licence of third-party code inside the library.
 *
 * The build fails on a licence it does not know, on a library with no line it needs, on a line that
 * matches no shipped library, and on a notice without its licence's whole text, held to SPDX's in
 * `resources/sleevesight/licences/` (for an Apache NOTICE, `apache-2.0.txt` holds the license's
 * text), without a copyright line, or without
 * the copyright lines of the library's own LICENSE file or the text of its NOTICE file. What sits
 * outside the artifacts, an upstream NOTICE file or third-party code a library's sources name, it
 * cannot see: those lines are reviewed by hand.
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
                .mapValues { (_, all) ->
                    Bundled(all.flatMap { it.licenses }, all.flatMap { it.notices }, all.flatMap { it.assets })
                }
        val table = parseNoticeTable(noticeTable.get().asFile.readText())
        val directory = noticeDirectory.get().asFile
        val result =
            libraries(
                modules = modules.get(),
                licences = { licencesOf(it, pomsByModule) },
                bundled = { bundled[it] ?: NONE },
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

    /** The texts of the LICENSE and NOTICE files in a module's artifacts, and its AARs' assets. */
    data class Bundled(
        val licenses: List<String>,
        val notices: List<String>,
        val assets: List<String> = emptyList(),
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

        const val MIT = "MIT"
        const val BSD_3 = "BSD-3-Clause"
        const val MPL_2 = "MPL-2.0"

        private val NONE = Bundled(emptyList(), emptyList())

        /** The licences whose whole text the build holds a notice to, in `resources/sleevesight/licences/`. */
        private val TEXTS = setOf(APACHE, MIT, BSD_3, MPL_2)

        /** [licence]'s reference text as a pattern over [normalise]d text (the placeholders in its README). */
        private val patterns = mutableMapOf<String, Regex>()

        private fun pattern(licence: String): Regex =
            patterns.getOrPut(licence) {
                val text =
                    checkNotNull(ListLibraryLicences::class.java.getResource("/sleevesight/licences/$licence.txt")) {
                        "No reference text for $licence"
                    }.readText()
                Regex(placeholdersToPattern(normalise(text)))
            }

        private val PLACEHOLDER = Regex("""\{[^}]*}""")

        private fun placeholdersToPattern(template: String): String {
            val out = StringBuilder()
            var at = 0
            for (match in PLACEHOLDER.findAll(template)) {
                out.append(Regex.escape(template.substring(at, match.range.first)))
                out.append(
                    when (val p = match.value) {
                        "{*}" -> ".{1,200}?"
                        "{-}" -> """(?:(?:\d{1,2}\.|\*|-|\([a-z0-9]{1,2}\)) )?"""
                        else -> p.removeSurrounding("{", "}").split('|').joinToString("|", "(?:", ")") { Regex.escape(it) }
                    },
                )
                at = match.range.last + 1
            }
            return out.append(Regex.escape(template.substring(at))).toString()
        }

        /** The licences the build knows, by their names in POMs, lower case, `the ` and spaces aside. */
        private val NAMES =
            mapOf(
                "apache software license, version 2.0" to APACHE,
                "apache license, version 2.0" to APACHE,
                "apache license 2.0" to APACHE,
                "apache-2.0" to APACHE,
                "apache 2.0" to APACHE,
                "mit" to MIT,
                "mit license" to MIT,
                "bsd-3-clause" to BSD_3,
                "bsd 3-clause license" to BSD_3,
                "new bsd license" to BSD_3,
                "mpl-2.0" to MPL_2,
                "mozilla public license 2.0" to MPL_2,
                "mozilla public license, version 2.0" to MPL_2,
            )

        /** The licences the build knows, by their URLs, without scheme, `www.`, extension or final slash. */
        private val URLS =
            mapOf(
                "apache.org/licenses/license-2.0" to APACHE,
                "opensource.org/licenses/apache-2.0" to APACHE,
                "opensource.org/licenses/mit" to MIT,
                "opensource.org/license/mit" to MIT,
                "opensource.org/licenses/bsd-3-clause" to BSD_3,
                "opensource.org/license/bsd-3-clause" to BSD_3,
                "mozilla.org/mpl/2.0" to MPL_2,
            )

        /** A copyright line: `Copyright`, then `(c)`, `©` or a year; not a licence's "copyright notice". */
        private val COPYRIGHT =
            Regex("""^\s*Copyright\s+(\(c\)|©|\d{4}).*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

        /** The Apache License's own appendix, which shows how to apply it, and other templates. */
        private val TEMPLATE = Regex("""\[yyyy]|<year>|\{yyyy}""", RegexOption.IGNORE_CASE)

        /** `LICENSE`, `LICENSE-MIT.txt`, `NOTICE.md` and the like; not `License.class`. */
        private val LICENSE_FILE = Regex("""^(LICEN[CS]E|COPYING)([-_][\w-]*)?(\.(txt|md|html))?$""", RegexOption.IGNORE_CASE)
        private val NOTICE_FILE = Regex("""^NOTICE([-_][\w-]*)?(\.(txt|md|html))?$""", RegexOption.IGNORE_CASE)

        /**
         * The SPDX identifier of a POM's licence, for the licences the build knows; null for others.
         * A name decides when it is given, and must be one the build knows (`MIT-0` or
         * `BSD-3-Clause-Clear` is not MIT or BSD-3-Clause); the URL, when given, must not name another
         * licence. Only a POM with no name is known by its URL.
         */
        fun spdx(licence: PomLicence): String? {
            val name = licence.name.lowercase().trim().removePrefix("the ").replace(Regex("\\s+"), " ")
            val url =
                licence.url.lowercase().trim()
                    .replace(Regex("^https?://"), "")
                    .removePrefix("www.")
                    .removeSuffix("/")
                    .replace(Regex("\\.(txt|html|php)$"), "")
            val byName = NAMES[name]
            val byUrl = URLS[url]
            return when {
                name.isNotEmpty() -> byName?.takeIf { byUrl == null || byUrl == it }
                else -> byUrl
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

        /**
         * [entry] (a line's module) matches [module] (`group:name:version`) as in the dependency
         * allowlist, or, written `group:prefix*`, every module of the group whose name starts so.
         */
        fun entryMatches(
            entry: String,
            module: String,
        ): Boolean =
            if (':' in entry && entry.endsWith("*")) {
                module.substringBeforeLast(':').startsWith(entry.removeSuffix("*"))
            } else {
                CheckDependencies.moduleMatches(entry, module)
            }

        /** Each library with its licence and notices, one row per notice, and what is wrong, one line each. */
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
            val libraries = mutableListOf<Library>()
            for (module in modules) {
                val lines = table.filter { entryMatches(it.module, module) }
                used += lines
                val pomLicences = licences(module)
                if (pomLicences == null) {
                    problems += "$module: no licence in its POM or its parents'"
                    continue
                }
                val ids = pomLicences.map { spdx(it) }
                if (null in ids) {
                    val unknown = pomLicences.filter { spdx(it) == null }.joinToString { "${it.name} <${it.url}>" }
                    problems += "$module: a licence the build does not know: $unknown; review it and add it to the check"
                    continue
                }
                val others = ids.filterNotNull().distinct().filter { it != APACHE }
                if (others.size > 1) {
                    problems += "$module: more than one licence besides Apache-2.0 (${others.joinToString()}); review it"
                    continue
                }
                val files = bundled(module)
                lines.groupBy { it.notice }.filterValues { it.size > 1 }.keys.forEach {
                    problems += "$module: more than one line names $it"
                }
                val own = others.singleOrNull()
                val ownLines = if (own == null) emptyList() else lines.filter { it.licence == own }
                when {
                    own == null -> libraries += Library(module, APACHE, APACHE_NOTICE, "")
                    ownLines.isEmpty() -> problems += "$module: $own, with no line in licence-notices.txt naming its notice"
                    ownLines.size > 1 -> problems += "$module: more than one line gives its licence, $own"
                    else -> libraries += ownLines.single().let { Library(module, own, it.notice, it.name) }
                }
                if (lines.isEmpty() && files.notices.isNotEmpty()) {
                    problems += "$module: carries a NOTICE file, with no line in licence-notices.txt naming its notice"
                }
                if (lines.isEmpty() && files.assets.isNotEmpty()) {
                    problems += "$module: brings assets into the APK (${files.assets.joinToString()}); " +
                        "review their licence and add a line naming the notice they need"
                }
                val texts = lines.associateWith { notice(it.notice) }
                val held = { n: String -> texts.values.any { it != null && flatten(n) in flatten(it) } }
                if (lines.isNotEmpty() && !files.notices.all(held)) {
                    problems += "$module: no notice of its lines holds the text of its NOTICE file"
                }
                for (line in lines - ownLines.toSet()) libraries += Library(module, line.licence, line.notice, line.name)
                for (line in lines) {
                    problems += noticeProblems(module, line, texts[line], if (line in ownLines) files.licenses else emptyList())
                }
            }
            problems += (table - used).map { "licence-notices.txt: ${it.module} matches no shipped library" }
            notice(APACHE_NOTICE).let { text ->
                if (text == null) {
                    problems += "$APACHE_NOTICE is not in assets/licences"
                } else {
                    missingText(APACHE, text)?.let { problems += "$APACHE_NOTICE lacks the Apache-2.0 text: \"$it\"" }
                }
            }
            return Result(libraries.distinct(), problems.distinct())
        }

        /**
         * Where [text] departs from [licence]'s whole text: null if it holds it, else the first sentence
         * of the reference text it lacks, or a note that it holds each sentence but not in order.
         */
        fun missingText(
            licence: String,
            text: String,
        ): String? {
            val flat = normalise(text)
            if (pattern(licence).containsMatchIn(flat)) return null
            val reference =
                normalise(checkNotNull(ListLibraryLicences::class.java.getResource("/sleevesight/licences/$licence.txt")).readText())
            return reference.split(Regex("""(?<=[.;:])\s+""")).firstOrNull { sentence ->
                !Regex(placeholdersToPattern(sentence)).containsMatchIn(flat)
            } ?: "its sentences, but not as the licence has them"
        }

        /**
         * What is wrong with [line]'s notice, [text]: its licence's text (an Apache NOTICE's is
         * `apache-2.0.txt`, checked once), a copyright line (the MPL asks for none), and the copyright
         * lines of the library's own LICENSE files, [licenses], for the line of the library's licence.
         */
        private fun noticeProblems(
            module: String,
            line: Entry,
            text: String?,
            licenses: List<String>,
        ): List<String> {
            if (line.licence !in TEXTS) {
                return listOf("$module: licence-notices.txt gives ${line.licence}, a licence the build does not know")
            }
            if (text == null) return listOf("$module: its notice, ${line.notice}, is not in assets/licences")
            val problems = mutableListOf<String>()
            if (line.licence != APACHE) {
                missingText(line.licence, text)?.let { problems += "$module: ${line.notice} lacks the ${line.licence} text: \"$it\"" }
            }
            if (line.licence != MPL_2 && COPYRIGHT.findAll(text).none()) problems += "$module: ${line.notice} has no copyright line"
            copyrightLines(licenses).filterNot { flatten(it) in flatten(text) }.forEach {
                problems += "$module: ${line.notice} lacks the library's own \"$it\""
            }
            return problems
        }

        /** The copyright lines of LICENSE texts, templates left out. */
        fun copyrightLines(licenses: List<String>): List<String> =
            licenses.flatMap { COPYRIGHT.findAll(it).map { m -> m.value.trim() }.toList() }
                .filterNot { TEMPLATE.containsMatchIn(it) }
                .distinct()

        /**
         * The LICENSE and NOTICE files in a jar or AAR, and in the jars inside it (an AAR's
         * `classes.jar` and the jars in its `libs`), and an AAR's assets, which the APK takes as they are.
         */
        fun bundledFiles(artifact: File): Bundled =
            if (artifact.extension in setOf("jar", "aar")) {
                artifact.inputStream().use { bundledFiles(it, aar = artifact.extension == "aar") }
            } else {
                NONE
            }

        private fun bundledFiles(
            stream: InputStream,
            aar: Boolean,
        ): Bundled {
            val licenses = mutableListOf<String>()
            val notices = mutableListOf<String>()
            val assets = mutableListOf<String>()
            val zip = ZipInputStream(stream)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/')
                when {
                    aar && entry.name.startsWith("assets/") -> assets += entry.name
                    shipsCode(entry.name, aar) ->
                        bundledFiles(zip.readBytes().inputStream(), aar = false).let {
                            licenses += it.licenses
                            notices += it.notices
                        }
                    NOTICE_FILE.matches(base) -> notices += zip.readBytes().decodeToString()
                    LICENSE_FILE.matches(base) -> licenses += zip.readBytes().decodeToString()
                }
            }
            return Bundled(licenses, notices, assets)
        }

        /** An AAR's code, `classes.jar` and the jars in its `libs` (not its `lint.jar`); any jar in a jar. */
        private fun shipsCode(
            name: String,
            aar: Boolean,
        ): Boolean =
            name.endsWith(".jar") && (!aar || name == "classes.jar" || name.startsWith("libs/"))

        /** The asset: a comment, then `module, licence, notice, name` separated by tabs. */
        fun render(libraries: List<Library>): String =
            "# Written by the build (listLibraryLicences) from each library's POM and config/licence-notices.txt.\n" +
                "# module\tlicence\tnotice\tname\n" +
                libraries.sortedBy { it.module }.joinToString("") { "${it.module}\t${it.licence}\t${it.notice}\t${it.name}\n" }

        private fun flatten(text: String): String = normalise(text)

        /** [text] with each run of whitespace one space, typographic quotes straight, and rules of `=` or `-` gone. */
        private fun normalise(text: String): String =
            text
                .replace('\u201c', '"')
                .replace('\u201d', '"')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace(Regex("""(?m)^\s*[=-]{3,}\s*$"""), "")
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun Element.child(tag: String): Element? = children(tag).firstOrNull()

        private fun Element.children(tag: String): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

        private fun Element.text(tag: String): String? = child(tag)?.textContent?.trim()
    }
}

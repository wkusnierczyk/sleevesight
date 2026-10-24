package sleevesight.checks

import org.junit.jupiter.api.io.TempDir
import sleevesight.checks.ListLibraryLicences.Bundled
import sleevesight.checks.ListLibraryLicences.Entry
import sleevesight.checks.ListLibraryLicences.Library
import sleevesight.checks.ListLibraryLicences.PomLicence
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ListLibraryLicencesTest {
    @TempDir
    lateinit var tmp: File

    private val apache = PomLicence("The Apache Software License, Version 2.0", "http://www.apache.org/licenses/LICENSE-2.0.txt")
    private val mit = PomLicence("MIT", "https://opensource.org/license/mit")
    private val none = Bundled(emptyList(), emptyList())

    // SLF4J's own spacing, which the check reads past.
    private val mitText =
        "Copyright (c) 2004-2023 QOS.ch\n\nPermission is hereby granted, free  of charge, to any person obtaining\n" +
            "a  copy  of this  software\n\nThe  above  copyright  notice  and  this permission  notice  shall  be\n" +
            "included in all copies or substantial portions of the Software.\n\n" +
            "THE  SOFTWARE IS  PROVIDED  \"AS  IS\", WITHOUT WARRANTY OF ANY KIND"

    private val apacheText =
        "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION\n 2. Grant of Copyright License.\n" +
            " 4. Redistribution.\n 7. Disclaimer of Warranty.\n END OF TERMS AND CONDITIONS"

    private val mplText =
        "Mozilla Public License Version 2.0\n3.2. Distribution of Executable Form\n6. Disclaimer of Warranty\n" +
            "Exhibit A - Source Code Form License Notice"

    private fun pom(
        coordinates: String,
        parent: String? = null,
        licences: List<PomLicence> = emptyList(),
    ): String {
        val (group, name, version) = coordinates.split(':')
        val parentXml =
            parent?.split(':')?.let { (g, n, v) -> "<parent><groupId>$g</groupId><artifactId>$n</artifactId><version>$v</version></parent>" }
        val licencesXml =
            licences.takeIf { it.isNotEmpty() }?.joinToString("", "<licenses>", "</licenses>") {
                "<license><name>${it.name}</name><url>${it.url}</url></license>"
            }
        return """<?xml version="1.0"?><project xmlns="http://maven.apache.org/POM/4.0.0">${parentXml.orEmpty()}""" +
            "<groupId>$group</groupId><artifactId>$name</artifactId><version>$version</version>${licencesXml.orEmpty()}</project>"
    }

    private fun run(
        modules: List<String>,
        licences: Map<String, List<PomLicence>>,
        table: List<Entry> = emptyList(),
        bundled: Map<String, Bundled> = emptyMap(),
        notices: Map<String, String> = emptyMap(),
    ) = ListLibraryLicences.libraries(
        modules,
        { licences[it] },
        { bundled[it] ?: none },
        table,
        { (notices + ("apache-2.0.txt" to apacheText))[it] },
    )

    @Test
    fun `the POM's licence, or its parent's, is the library's`() {
        val poms =
            listOf(
                pom("org.slf4j:slf4j-api:2.0.19", parent = "org.slf4j:slf4j-parent:2.0.19"),
                pom("org.slf4j:slf4j-parent:2.0.19", parent = "org.slf4j:slf4j-bom:2.0.19"),
                pom("org.slf4j:slf4j-bom:2.0.19", licences = listOf(mit)),
            ).map { ListLibraryLicences.parsePom(it) }.associateBy { it.coordinates }
        assertEquals(listOf(mit), ListLibraryLicences.licencesOf("org.slf4j:slf4j-api:2.0.19", poms))
        assertEquals(null, ListLibraryLicences.licencesOf("org.other:x:1", poms))
    }

    @Test
    fun `a POM's group and version may come from its parent, and its version from its file name`() {
        val text =
            """<project><parent><groupId>g</groupId><artifactId>p</artifactId><version>3</version></parent>""" +
                "<artifactId>a</artifactId></project>"
        assertEquals("g:a:3", ListLibraryLicences.parsePom(text).coordinates)
        assertEquals("g:p:3", ListLibraryLicences.parsePom(text).parent)
        val property = "<project><groupId>g</groupId><artifactId>a</artifactId><version>\${revision}</version></project>"
        assertEquals("g:a:1.2", ListLibraryLicences.parsePom(property, "a-1.2.pom").coordinates)
    }

    @Test
    fun `the build knows the Apache License, MIT, BSD-3-Clause and MPL-2_0 by name or URL, and no other`() {
        assertEquals("Apache-2.0", ListLibraryLicences.spdx(apache))
        assertEquals("Apache-2.0", ListLibraryLicences.spdx(PomLicence("Apache-2.0", "")))
        assertEquals("MIT", ListLibraryLicences.spdx(PomLicence("The MIT License", "https://github.com/x/y/blob/master/LICENSE")))
        assertEquals("MIT", ListLibraryLicences.spdx(mit))
        assertEquals("BSD-3-Clause", ListLibraryLicences.spdx(PomLicence("BSD-3-Clause", "https://opensource.org/licenses/BSD-3-Clause")))
        assertEquals("MPL-2.0", ListLibraryLicences.spdx(PomLicence("MPL-2.0", "https://www.mozilla.org/MPL/2.0/")))
        assertEquals(null, ListLibraryLicences.spdx(PomLicence("Eclipse Public License 2.0", "https://www.eclipse.org/legal/epl-2.0/")))
        assertEquals(null, ListLibraryLicences.spdx(PomLicence("BSD-2-Clause", "")))
        // Names and URLs that only start like a known licence's.
        assertEquals(null, ListLibraryLicences.spdx(PomLicence("MIT-0", "https://opensource.org/license/mit-0")))
        assertEquals(null, ListLibraryLicences.spdx(PomLicence("BSD-3-Clause-Clear", "https://spdx.org/licenses/BSD-3-Clause-Clear.html")))
        assertEquals(null, ListLibraryLicences.spdx(PomLicence("", "https://opensource.org/licenses/MIT-0")))
        // A name and a URL of different licences.
        assertEquals(null, ListLibraryLicences.spdx(PomLicence("MIT", "https://www.apache.org/licenses/LICENSE-2.0.txt")))
    }

    @Test
    fun `an Apache-2_0 library takes the license's text, and a MIT one its notice`() {
        val result =
            run(
                listOf("io.ktor:ktor-io:3", "org.slf4j:slf4j-api:2"),
                mapOf("io.ktor:ktor-io:3" to listOf(apache), "org.slf4j:slf4j-api:2" to listOf(mit)),
                listOf(Entry("org.slf4j:slf4j-api", "MIT", "mit-slf4j.txt", "SLF4J API")),
                bundled = mapOf("org.slf4j:slf4j-api:2" to Bundled(listOf(mitText), emptyList())),
                notices = mapOf("mit-slf4j.txt" to "SLF4J API\n\n$mitText"),
            )
        assertEquals(emptyList(), result.problems)
        assertEquals(
            listOf(
                Library("io.ktor:ktor-io:3", "Apache-2.0", "apache-2.0.txt", ""),
                Library("org.slf4j:slf4j-api:2", "MIT", "mit-slf4j.txt", "SLF4J API"),
            ),
            result.libraries,
        )
    }

    @Test
    fun `a library with another licence, or none, and no line fails`() {
        val result =
            run(
                listOf("a:mit:1", "a:epl:1", "a:none:1"),
                mapOf("a:mit:1" to listOf(mit), "a:epl:1" to listOf(PomLicence("EPL-2.0", ""))),
            )
        assertEquals(
            listOf(
                "a:mit:1: MIT, with no line in licence-notices.txt naming its notice",
                "a:epl:1: a licence the build does not know: EPL-2.0 <>; review it and add it to the check",
                "a:none:1: no licence in its POM or its parents'",
            ),
            result.problems,
        )
    }

    @Test
    fun `a line that matches nothing, or a library whose licence no line gives, fails`() {
        val result =
            run(
                listOf("a:b:1"),
                mapOf("a:b:1" to listOf(mit)),
                listOf(Entry("a", "BSD-3-Clause", "n.txt", "B"), Entry("gone", "MIT", "g.txt", "G")),
                notices = mapOf("n.txt" to mitText),
            )
        assertEquals(
            listOf(
                "a:b:1: MIT, with no line in licence-notices.txt naming its notice",
                "a:b:1: n.txt lacks the BSD-3-Clause text (\"Redistribution and use in source and binary forms, " +
                    "with or without modification, are permitted\")",
            ),
            result.problems.take(2),
        )
        assertEquals("licence-notices.txt: gone matches no shipped library", result.problems.last())
    }

    @Test
    fun `a notice must hold each part of the licence's text and the library's copyright lines`() {
        val table = listOf(Entry("a:b", "MIT", "n.txt", "B"))
        val licences = mapOf("a:b:1" to listOf(mit))
        val bundled = mapOf("a:b:1" to Bundled(listOf(mitText), emptyList()))
        assertEquals(
            listOf("a:b:1: its notice, n.txt, is not in assets/licences"),
            run(listOf("a:b:1"), licences, table, bundled).problems,
        )
        assertEquals(emptyList(), run(listOf("a:b:1"), licences, table, bundled, mapOf("n.txt" to "B\n\n$mitText")).problems)
        // Without the condition and the disclaimer, or without the library's own copyright line.
        val grantOnly = "Copyright 2020 Someone\nPermission is hereby granted, free of charge, to any person obtaining a copy"
        assertEquals(
            listOf(
                "a:b:1: n.txt lacks the MIT text (\"The above copyright notice and this permission notice shall be included in all copies\")",
                "a:b:1: n.txt lacks the MIT text (\"THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND\")",
                "a:b:1: n.txt lacks the library's own \"Copyright (c) 2004-2023 QOS.ch\"",
            ),
            run(listOf("a:b:1"), licences, table, bundled, mapOf("n.txt" to grantOnly)).problems,
        )
        assertEquals(
            listOf("a:b:1: n.txt has no copyright line"),
            run(listOf("a:b:1"), licences, table, notices = mapOf("n.txt" to mitText.substringAfter("\n\n"))).problems,
        )
    }

    @Test
    fun `an Apache-2_0 library with a NOTICE file or assets needs a line`() {
        val licences = mapOf("a:b:1" to listOf(apache))
        val notice = "Apache Foo\nCopyright 2020 The Foo Authors"
        assertEquals(
            listOf("a:b:1: carries a NOTICE file, with no line in licence-notices.txt naming its notice"),
            run(listOf("a:b:1"), licences, bundled = mapOf("a:b:1" to Bundled(emptyList(), listOf(notice)))).problems,
        )
        assertEquals(
            listOf(
                "a:b:1: brings assets into the APK (assets/list.dat); review their licence and add a line naming the notice they need",
            ),
            run(listOf("a:b:1"), licences, bundled = mapOf("a:b:1" to Bundled(emptyList(), emptyList(), listOf("assets/list.dat")))).problems,
        )
        val table = listOf(Entry("a:b", "Apache-2.0", "n.txt", "Foo"))
        val bundled = mapOf("a:b:1" to Bundled(emptyList(), listOf(notice)))
        val ok = run(listOf("a:b:1"), licences, table, bundled, mapOf("n.txt" to "Foo\n\n$notice"))
        assertEquals(emptyList(), ok.problems)
        assertEquals(
            listOf(
                Library("a:b:1", "Apache-2.0", "apache-2.0.txt", ""),
                Library("a:b:1", "Apache-2.0", "n.txt", "Foo"),
            ),
            ok.libraries,
        )
        assertEquals(
            listOf("a:b:1: no notice of its lines holds the text of its NOTICE file"),
            run(listOf("a:b:1"), licences, table, bundled, mapOf("n.txt" to "Copyright 2020")).problems,
        )
    }

    @Test
    fun `a line may add an upstream NOTICE or third-party code's licence, matched by a name prefix`() {
        val licences = mapOf("k:core:1" to listOf(apache), "k:core-jvm:1" to listOf(apache), "k:other:1" to listOf(apache))
        val table =
            listOf(
                Entry("k:core*", "Apache-2.0", "notice.txt", "Core"),
                Entry("k:other", "MPL-2.0", "mpl.txt", "List, in other"),
            )
        val result =
            run(
                listOf("k:core:1", "k:core-jvm:1", "k:other:1"),
                licences,
                table,
                bundled = mapOf("k:other:1" to Bundled(emptyList(), emptyList(), listOf("assets/list.dat"))),
                notices = mapOf("notice.txt" to "Core\nCopyright 2016 K", "mpl.txt" to mplText),
            )
        assertEquals(emptyList(), result.problems)
        assertEquals(
            setOf("k:core:1 notice.txt", "k:core-jvm:1 notice.txt", "k:other:1 mpl.txt"),
            result.libraries.filter { it.notice != "apache-2.0.txt" }.map { "${it.module} ${it.notice}" }.toSet(),
        )
        assertEquals(
            listOf("k:other:1: mpl.txt lacks the MPL-2.0 text (\"Exhibit A - Source Code Form License Notice\")"),
            run(listOf("k:other:1"), licences, table.drop(1), notices = mapOf("mpl.txt" to mplText.substringBefore("\nExhibit"))).problems,
        )
        assertEquals(
            listOf("k:other:1: licence-notices.txt gives EPL-2.0, a licence the build does not know"),
            run(listOf("k:other:1"), licences, listOf(Entry("k:other", "EPL-2.0", "e.txt", "E"))).problems,
        )
    }

    @Test
    fun `the Apache License's text must be whole`() {
        val result =
            ListLibraryLicences.libraries(
                listOf("a:b:1"),
                { listOf(apache) },
                { none },
                emptyList(),
                { if (it == "apache-2.0.txt") apacheText.replace("4. Redistribution.", "") else null },
            )
        assertEquals(listOf("apache-2.0.txt lacks the Apache-2.0 text (\"4. Redistribution.\")"), result.problems)
    }

    @Test
    fun `the Apache License's appendix is not a copyright line to carry`() {
        assertEquals(
            listOf("Copyright 2008 Google Inc."),
            ListLibraryLicences.copyrightLines(listOf("Copyright [yyyy] [name of copyright owner]", "Copyright 2008 Google Inc.")),
        )
    }

    @Test
    fun `LICENSE and NOTICE files are found in a jar, and in an AAR's jars, with its assets`() {
        val jar = zip("META-INF/LICENSE.txt" to "the licence", "META-INF/foo/NOTICE" to "the notice", "a/Notice.class" to "")
        val lib = zip("META-INF/LICENSE-MIT" to "a bundled licence", "a/License.class" to "")
        val aar =
            tmp.resolve("x.aar").apply {
                writeBytes(zip("classes.jar" to jar, "libs/repackaged.jar" to lib, "assets/list.dat" to "", "res/values/values.xml" to ""))
            }
        assertEquals(
            Bundled(listOf("the licence", "a bundled licence"), listOf("the notice"), listOf("assets/list.dat")),
            ListLibraryLicences.bundledFiles(aar),
        )
        assertEquals(none, ListLibraryLicences.bundledFiles(tmp.resolve("x.pom").apply { writeText("<project/>") }))
    }

    @Test
    fun `the table's name is the rest of the line`() {
        assertEquals(
            listOf(Entry("androidx.datastore:x", "BSD-3-Clause", "b.txt", "Protocol Buffers, in DataStore")),
            ListLibraryLicences.parseNoticeTable(
                "# comment\n\nandroidx.datastore:x   BSD-3-Clause  b.txt   Protocol Buffers, in DataStore  # why\n",
            ),
        )
    }

    private fun zip(vararg entries: Pair<String, Any>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(if (content is ByteArray) content else content.toString().toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}

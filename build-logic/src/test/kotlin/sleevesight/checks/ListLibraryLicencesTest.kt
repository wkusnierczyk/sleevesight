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

    private val mitText =
        "Copyright (c) 2004-2023 QOS.ch\n\nPermission is hereby granted, free  of charge, to any person obtaining\n" +
            "a  copy  of this  software"

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
    ) = ListLibraryLicences.libraries(modules, { licences[it] }, { bundled[it] ?: none }, table, { notices[it] })

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
    fun `the build knows the Apache License, MIT and BSD-3-Clause by name or URL, and no other`() {
        assertEquals("Apache-2.0", ListLibraryLicences.spdx(apache))
        assertEquals("Apache-2.0", ListLibraryLicences.spdx(PomLicence("Apache-2.0", "")))
        assertEquals("MIT", ListLibraryLicences.spdx(PomLicence("The MIT License", "https://github.com/x/y/blob/master/LICENSE")))
        assertEquals("BSD-3-Clause", ListLibraryLicences.spdx(PomLicence("BSD-3-Clause", "https://opensource.org/licenses/BSD-3-Clause")))
        assertEquals(null, ListLibraryLicences.spdx(PomLicence("Eclipse Public License 2.0", "https://www.eclipse.org/legal/epl-2.0/")))
        assertEquals(null, ListLibraryLicences.spdx(PomLicence("BSD-2-Clause", "")))
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
    fun `a line that matches nothing, or names another licence, fails`() {
        val result =
            run(
                listOf("a:b:1"),
                mapOf("a:b:1" to listOf(mit)),
                listOf(Entry("a", "BSD-3-Clause", "n.txt", "B"), Entry("gone", "MIT", "g.txt", "G")),
                notices = mapOf("n.txt" to mitText),
            )
        assertEquals(
            listOf(
                "a:b:1: licence-notices.txt says BSD-3-Clause, its POM MIT",
                "licence-notices.txt: gone matches no shipped library",
            ),
            result.problems,
        )
    }

    @Test
    fun `a notice must hold the licence's text and the library's copyright lines`() {
        val table = listOf(Entry("a:b", "MIT", "n.txt", "B"))
        val licences = mapOf("a:b:1" to listOf(mit))
        val bundled = mapOf("a:b:1" to Bundled(listOf(mitText), emptyList()))
        assertEquals(
            listOf("a:b:1: its notice, n.txt, is not in assets/licences"),
            run(listOf("a:b:1"), licences, table, bundled).problems,
        )
        assertEquals(
            listOf(
                "a:b:1: n.txt lacks the MIT text (\"Permission is hereby granted, free of charge, to any person obtaining a copy\")",
                "a:b:1: n.txt has no copyright line",
                "a:b:1: n.txt lacks the library's own \"Copyright (c) 2004-2023 QOS.ch\"",
            ),
            run(listOf("a:b:1"), licences, table, bundled, mapOf("n.txt" to "MIT License")).problems,
        )
    }

    @Test
    fun `an Apache-2_0 library with a NOTICE file needs it in a notice, and one without needs no line`() {
        val licences = mapOf("a:b:1" to listOf(apache))
        val notice = "Apache Foo\nCopyright 2020 The Foo Authors"
        val bundled = mapOf("a:b:1" to Bundled(emptyList(), listOf(notice)))
        assertEquals(
            listOf("a:b:1: Apache-2.0 with a NOTICE file, with no line in licence-notices.txt naming its notice"),
            run(listOf("a:b:1"), licences, bundled = bundled).problems,
        )
        val table = listOf(Entry("a:b", "Apache-2.0", "n.txt", "Foo"))
        assertEquals(emptyList(), run(listOf("a:b:1"), licences, table, bundled, mapOf("n.txt" to "Foo\n\n$notice")).problems)
        assertEquals(
            listOf("a:b:1: n.txt lacks the text of the library's NOTICE file"),
            run(listOf("a:b:1"), licences, table, bundled, mapOf("n.txt" to "Copyright 2020")).problems,
        )
        assertEquals(
            listOf("a:b:1: Apache-2.0 with no NOTICE file needs no line in licence-notices.txt"),
            run(listOf("a:b:1"), licences, table, notices = mapOf("n.txt" to notice)).problems,
        )
    }

    @Test
    fun `the Apache License's appendix is not a copyright line to carry`() {
        assertEquals(
            listOf("Copyright 2008 Google Inc."),
            ListLibraryLicences.copyrightLines(listOf("Copyright [yyyy] [name of copyright owner]", "Copyright 2008 Google Inc.")),
        )
    }

    @Test
    fun `LICENSE and NOTICE files are found in a jar, and in an AAR's classes jar`() {
        val jar = zip("META-INF/LICENSE.txt" to "the licence", "META-INF/foo/NOTICE" to "the notice", "a/B.class" to "")
        val aar = tmp.resolve("x.aar").apply { writeBytes(zip("classes.jar" to jar, "res/values/values.xml" to "")) }
        assertEquals(Bundled(listOf("the licence"), listOf("the notice")), ListLibraryLicences.bundledFiles(aar))
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

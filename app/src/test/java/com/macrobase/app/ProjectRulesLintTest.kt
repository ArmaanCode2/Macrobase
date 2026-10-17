package com.macrobase.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * BUG-052 / AGENTS.md sections 3.1 and 3.2: every format call passes Locale.US, and Kotlin code
 * holds no raw non-ASCII character outside comments (write "\u2022", not the bullet itself).
 * Every source file must also be valid UTF-8: bytes in another encoding once turned a scanner
 * regex's micro sign into U+FFFD, so it never matched.
 */
class ProjectRulesLintTest {

    private val sourceRoot = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    // The whitespace sits inside the lookahead: outside it, the regex could match no whitespace
    // and then see the line break instead of a locale on the next line
    private val formatWithoutLocale = Regex("""String\.format\((?!\s*(java\.util\.)?Locale\.US\b)""")

    /** Kotlin's `"...".format(...)` extension uses the default locale unless one is passed. */
    private val literalFormatWithoutLocale = Regex("\"\\s*\\.format\\((?!\\s*(java\\.util\\.)?Locale\\.US\\b)")

    @Test
    fun sourcesFollowTheLocaleAndEncodingRules() {
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath.removePrefix("com/macrobase/app/")
                val text = decodeStrictUtf8(file.readBytes())
                if (text == null) sequenceOf("$relative: not valid UTF-8") else violations(text).map { "$relative:$it" }.asSequence()
            }
            .toList()

        assertTrue("AGENTS.md sections 3.1 and 3.2:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    // The checker must find real violations, or the test above passes by finding nothing
    @Test
    fun theCheckerFindsViolations() {
        val bullet = '\u2022'

        assertEquals(listOf(1), formatsWithoutLocale("val a = String.format(\"%.1f\", x)"))
        assertEquals(listOf(2), formatsWithoutLocale("val a = 1\nval b = \"%.1f\".format(x)"))
        assertEquals(listOf(1), nonAsciiLines(withoutComments("val s = \"$bullet\"")))
        // A "//" inside a string is not a comment
        assertEquals(listOf(2), nonAsciiLines(withoutComments("val a = 1\nval u = \"http://x\" + \"$bullet\"")))
        // Nor is one inside a string nested in a template
        assertEquals(listOf(1), nonAsciiLines(withoutComments("val s = \"\${if (a) \"//\" else \"b\"} $bullet\"")))
        assertNull(decodeStrictUtf8(byteArrayOf(0x76, 0xB5.toByte())))
    }

    @Test
    fun theCheckerAcceptsWhatTheRulesAllow() {
        val arrow = '\u2192'

        assertTrue(formatsWithoutLocale("String.format(Locale.US, \"%.1f\", x)").isEmpty())
        assertTrue(formatsWithoutLocale("String.format(\n    java.util.Locale.US,\n    \"%d\", n\n)").isEmpty())
        assertTrue(formatsWithoutLocale("\"%.1f\".format(Locale.US, x)").isEmpty())
        assertTrue(nonAsciiLines(withoutComments("val s = \"\\u2022\"")).isEmpty())
        assertTrue(nonAsciiLines(withoutComments("// $arrow\n/** a $arrow /* nested */ b $arrow */\nval a = 1")).isEmpty())
        assertTrue(nonAsciiLines(withoutComments("val c = '\"' // $arrow")).isEmpty())
        assertTrue(nonAsciiLines(withoutComments("val r = \"\"\"a\"\"\" // $arrow")).isEmpty())
        // A byte-order mark is allowed and dropped
        assertEquals("v", decodeStrictUtf8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x76)))
    }

    /** "line: rule" for every violation in one file's text. */
    private fun violations(text: String): List<String> {
        val code = withoutComments(text)
        return nonAsciiLines(code).map { "$it: raw non-ASCII character outside a comment; use a \\u escape" } +
            formatsWithoutLocale(code).map { "$it: format without Locale.US" }
    }

    private fun nonAsciiLines(code: String): List<Int> =
        code.lines().mapIndexedNotNull { index, line -> if (line.any { it.code > 127 }) index + 1 else null }

    private fun formatsWithoutLocale(code: String): List<Int> =
        (formatWithoutLocale.findAll(code) + literalFormatWithoutLocale.findAll(code))
            .map { match -> code.substring(0, match.range.first).count { it == '\n' } + 1 }
            .distinct()
            .sorted()
            .toList()

    /** The file's text, or null when it is not valid UTF-8. A byte-order mark is dropped. */
    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    } catch (e: CharacterCodingException) {
        null
    }

    /**
     * [source] with every comment replaced by spaces. Strings, characters and line breaks stay,
     * so line numbers still match. String templates are followed, so a "//" inside a string,
     * even one nested in a template, is never taken for a comment.
     */
    private fun withoutComments(source: String): String {
        val out = StringBuilder(source)
        // What encloses the position: a string ('"'), a raw string ('R') or code in a template ('{')
        val stack = ArrayDeque<Char>()
        var i = 0

        fun blank(from: Int, to: Int) {
            for (k in from until to) if (out[k] != '\n') out.setCharAt(k, ' ')
        }

        while (i < source.length) {
            val c = source[i]
            when (stack.lastOrNull()) {
                '"' -> when {
                    c == '\\' -> i += 2
                    source.startsWith("\${", i) -> { stack.addLast('{'); i += 2 }
                    c == '"' -> { stack.removeLast(); i++ }
                    else -> i++
                }
                'R' -> when {
                    source.startsWith("\${", i) -> { stack.addLast('{'); i += 2 }
                    source.startsWith("\"\"\"", i) -> {
                        // A raw string ends at the last quote of a run of three or more
                        while (i < source.length && source[i] == '"') i++
                        stack.removeLast()
                    }
                    else -> i++
                }
                else -> when {
                    source.startsWith("//", i) -> {
                        val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                        blank(i, end)
                        i = end
                    }
                    source.startsWith("/*", i) -> {
                        // Kotlin block comments nest
                        var depth = 0
                        var k = i
                        while (k < source.length) {
                            if (source.startsWith("/*", k)) {
                                depth++
                                k += 2
                            } else if (source.startsWith("*/", k)) {
                                depth--
                                k += 2
                                if (depth == 0) break
                            } else {
                                k++
                            }
                        }
                        blank(i, k)
                        i = k
                    }
                    source.startsWith("\"\"\"", i) -> { stack.addLast('R'); i += 3 }
                    c == '"' -> { stack.addLast('"'); i++ }
                    c == '\'' -> {
                        // A character literal: skip to its closing quote
                        var k = i + 1
                        while (k < source.length && source[k] != '\'') k += if (source[k] == '\\') 2 else 1
                        i = k + 1
                    }
                    // Braces only matter inside a template, where they pair up until it closes
                    c == '{' -> { if (stack.isNotEmpty()) stack.addLast('{'); i++ }
                    c == '}' -> { if (stack.isNotEmpty()) stack.removeLast(); i++ }
                    else -> i++
                }
            }
        }
        return out.toString()
    }
}

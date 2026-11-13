package com.dannycom888.mylrcauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * `values/strings.xml` and `values-zh/strings.xml` must carry the same set of
 * string names, so no language ever falls back to the other one (or crashes
 * on a missing resource). Unit tests run with the module dir as the working
 * dir, so the resource paths are relative to it.
 */
class TranslationParityTest {

    private val nameRegex = Regex("""name="([^"]+)"""")

    private fun stringNames(file: File): Set<String> =
        nameRegex.findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun carFailedStringsAreGone() {
        // v1.0.19: the big screen must show no prompt at all — the
        // "出了点问题" error strings are deleted, and the catch in
        // onGetTemplate returns a neutral loading pane instead.
        for (file in listOf(
            File("src/main/res/values/strings.xml"),
            File("src/main/res/values-zh/strings.xml"),
        )) {
            assertFalse(
                "car_failed still present in $file",
                "car_failed" in file.readText(),
            )
        }
    }

    @Test
    fun stringsXmlAndStringsZhXmlHaveSameNames() {
        val base = stringNames(File("src/main/res/values/strings.xml"))
        val zh = stringNames(File("src/main/res/values-zh/strings.xml"))
        assertEquals(
            "String names differ between values and values-zh " +
                "(only in values: ${base - zh}; only in values-zh: ${zh - base})",
            base,
            zh,
        )
    }
}

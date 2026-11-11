package org.drivine.cypher.oracle

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

/**
 * The spec's host.yaml as JSON (host.json), which every target reads without a YAML reader of its own: each target's
 * HostSpecTest, from the oracle's directory, and the SQLite extension's check (check/extension-native.py), from the
 * directory `./gradlew hostSpecJson` writes it to. Jackson keeps the YAML's whole numbers whole and its floats floats.
 */
object HostSpecJson {

    fun write(spec: File, out: File) {
        out.mkdirs()
        File(out, "host.json").writeText(ObjectMapper().writeValueAsString(ObjectMapper(YAMLFactory()).readTree(File(spec, "host.yaml"))))
    }

    /** The spec's directory, and the directory to write host.json into. */
    @JvmStatic
    fun main(args: Array<String>) = write(File(args[0]), File(args[1]))
}

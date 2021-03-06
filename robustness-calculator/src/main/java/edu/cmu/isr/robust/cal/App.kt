/*
 * MIT License
 *
 * Copyright (c) 2020 Changjian Zhang, David Garlan, Eunsuk Kang
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package edu.cmu.isr.robust.cal

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import edu.cmu.isr.robust.eofm.EOFMS
import edu.cmu.isr.robust.eofm.parseEOFMS
import java.io.File

enum class Mode { COMPUTE, COMPARE, UNSAFE }

/**
 * This private class defines the command line options.
 * TODO: Add feature to do tau elimination and subset construction
 * TODO: Add feature to just output the EOFM translation and EOFM concise translation model
 * TODO: Optimize the error message
 */
private class App : CliktCommand(name = "robustness-calculator", help = """
This program calculates the behavioral robustness of a system against a base environment E
and a safety property P. Also, it takes a deviation model D to generate explanations for the
system robustness. In addition, it can compare the robustness of two systems or a system under
different properties.
""") {
  val verbose by option("--verbose", "-v", help = "Enable verbose mode").flag()
  val mode by option(help = "Operation mode: --compute compute the robustness of a given system w.r.t an environment" +
      "and a safety property; --compare compare the robustness of two; --unsafe compute the unsafe behaviors of a" +
      "given system w.r.t a safety property").switch(
      "--compute" to Mode.COMPUTE,
      "--compare" to Mode.COMPARE,
      "--unsafe" to Mode.UNSAFE
  )
  val outputFile by option("--output", "-o", metavar = "OUTPUT", help = "Save the results in a JSON file")
  val files by argument("FILES", help = "System description files in JSON").multiple()
  val waOnly by option("-w", help = "Generate the weakest assumption only").flag()
  // TODO
  val sink by option("--sink", "-s", help = "Generate the weakest assumption with sink state").flag()
  val io by option("--io", help = "Make the system an I/O automaton which requires input-enableness").flag()

  override fun run() {
    val resultJson = when (mode) {
      Mode.COMPUTE -> {
        if (files.isEmpty())
          throw IllegalArgumentException("Need one config file for computing robustness")
        val configFile = files[0]
        val config = jacksonObjectMapper().readValue<ConfigJson>(File(configFile).readText())
        val cal = createCalculator(config, verbose, io)
        val result = cal.computeRobustness(waOnly = waOnly)
        ResultJson(
            mode = "compute",
            traces = result.map { RepTraceJson(it.first.joinToString(), (it.second?:emptyList()).joinToString()) }
        )
      }
      Mode.COMPARE -> {
        if (files.size != 2)
          throw IllegalArgumentException("Need two config files for comparing robustness")
        val config1 = jacksonObjectMapper().readValue<ConfigJson>(File(files[0]).readText())
        val config2 = jacksonObjectMapper().readValue<ConfigJson>(File(files[1]).readText())
        val cal1 = createCalculator(config1, verbose, io)
        val cal2 = createCalculator(config2, verbose, io)
        cal1.nameOfWA = "WA1"
        cal2.nameOfWA = "WA2"
        println("========== Compute M1 - M2 ==========")
        val result1 = cal1.robustnessComparedTo(cal2.getWA(), "WA2")
        println("========== Compute M2 - M1 ==========")
        val result2 = cal2.robustnessComparedTo(cal1.getWA(), "WA1")
        ResultJson(
            mode = "compare",
            traces = result1.map { RepTraceJson(it.joinToString(), "") }
        )
      }
      Mode.UNSAFE -> {
        assert(files.size == 1)
        if (files.isEmpty())
          throw IllegalArgumentException("Need one config file for computing unsafe behavior")
        val configFile = files[0]
        val config = jacksonObjectMapper().readValue<ConfigJson>(File(configFile).readText())
        val cal = createCalculator(config, verbose, io)
        ResultJson(
            mode = "unsafe",
            traces = cal.computeUnsafeBeh().map { RepTraceJson(it.joinToString(), "") }
        )
      }
      else -> throw PrintMessage("An operation mode must be specified.")
    }
    // Write to JSON file it the output file if specified
    if (outputFile != null) {
      jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(File(outputFile!!), resultJson)
    }
  }
}

/**
 * The ConfigJson and EOFMConfigJson class define the structure of the JSON configuration file.
 */
private data class ConfigJson(
    @JsonProperty
    val mode: String,
    @JsonProperty
    val sys: String,
    @JsonProperty
    val env: String,
    @JsonProperty
    val prop: String,
    @JsonProperty
    val deviation: String?,
    @JsonProperty
    val eofm: EOFMConfigJson?
)

private data class EOFMConfigJson(
    @JsonProperty
    val initialValues: Map<String, String>,
    @JsonProperty
    val world: List<String>,
    @JsonProperty
    val relabels: Map<String, String>
)

/**
 * The ResultJson and RepTraceJson class define the structure of the output JSON file for holding the representative
 * traces and their explanations.
 */
private data class ResultJson(
    @JsonProperty
    val mode: String,
    @JsonProperty
    val traces: List<RepTraceJson>
)

private data class RepTraceJson(
    @JsonProperty
    val trace: String,
    @JsonProperty
    val explanation: String
)

fun main(args: Array<String>) = App().main(args)

/**
 * A helper function to create the corresponding AbstractRobustCal based on the JSON config file.
 */
private fun createCalculator(config: ConfigJson, verbose: Boolean, io: Boolean): RobustCal {
  val sys = File(config.sys).readText()
  val env = File(config.env).readText()
  val p = File(config.prop).readText()
  return when (config.mode) {
    "fsp" -> {
      val deviation = if (config.deviation == null) null else File(config.deviation).readText()
      if (io)
        FSPIORobustCal(sys, env, p, deviation, verbose)
      else
        FSPRobustCal(sys, env, p, deviation, verbose)
    }
    "eofm" -> {
      if (config.eofm == null)
        throw IllegalArgumentException("Need to provide eofm config in eofm mode")
      val eofm: EOFMS = parseEOFMS(env)
      EOFMRobustCal.create(sys, p, eofm, config.eofm.initialValues, config.eofm.world,
          config.eofm.relabels, verbose)
    }
    else -> throw IllegalArgumentException("Unidentified mode: '${config.mode}'")
  }
}


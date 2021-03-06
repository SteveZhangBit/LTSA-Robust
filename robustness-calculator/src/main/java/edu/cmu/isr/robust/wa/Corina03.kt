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

package edu.cmu.isr.robust.wa

import edu.cmu.isr.robust.ltsa.*

/**
 * This class implements the algorithm to generate weakest assumption described in:
 * J. M. Cobleigh, D. Giannakopoulou, and C. S. Pǎsǎreanu, “Learning Assumptions for Compositional Verification,”
 * in International Conference on Tools and Algorithms for the Construction and Analysis of Systems, 2003, pp. 331–346.
 */
class Corina03(val M1: String, val M2: String, p: String) : AbstractWAGenerator(M1, M2, p) {
  private val Σ: Set<String>
  private val S = mutableSetOf("")
  private val E = mutableSetOf("")
  private val T = mutableMapOf("" to true)
  private val nameM1: String
  private val nameM2: String
  private val nameP: String

  init {
    println("========== Parsing the alphabets ==========")
    var sm = LTSACall.doCompile(M1)
    val αM1 = sm.alphabetNoTau()
    nameM1 = sm.getCompositeName()

    sm = LTSACall.doCompile(M2)
    val αM2 = sm.alphabetNoTau()
    nameM2 = sm.getCompositeName()

    sm = LTSACall.doCompile(p)
    val αP = sm.alphabetNoTau()
    nameP = sm.getCompositeName()

    println("========== M1, M2, P model information ==========")
    println("Machines: M1 = $nameM1, M2 = $nameM2, P = $nameP")
    Σ = (αM1 union αP) intersect αM2
    println("Σ of the language: $Σ")
  }

  override fun alphabetOfWA(): Iterable<String> {
    return Σ
  }

  override fun weakestAssumption(name: String): String {
    while (true) {
      println("========== Find assumption for M1 ==========")
      val A = lstar()
      val A_fsp = conjectureToFSP(A, name)
      println("========== Validate conjecture assumption with the environment M2 ==========")
      val counterExample = LTSACall.doCompile(
        "$M2\nproperty $A_fsp\n||Composite = ($nameM2 || $name).", "Composite"
      ).doCompose().propertyCheck()
      if (counterExample == null) {
        println("========== Find the weakest assumption for M1 ==========\n$A_fsp")
        return A_fsp
      } else {
        println("========== Counterexample found with environment M2 ==========\n$counterExample")
        val projected = counterExample.filter { it in Σ }
        val A_c = "AC = (${projected.joinToString(" -> ")} -> STOP) + {${Σ.joinToString(", ")}}."
        val check = LTSACall.doCompile(
          "$A_c\n$M1\n$p\n||Composite = (AC || $nameM1 || $nameP).", "Composite"
        ).doCompose().propertyCheck()
        if (check == null) {
          println("========== Weaken the assumption for M1 ==========")
          witnessOfCounterExample(A, projected)
        } else {
          error("P is violated in M1 || M2.")
        }
      }
    }
  }

  /**
   *
   */
  private fun lstar(): Set<Triple<String, String, String>> {
    while (true) {
      updateTwithQueries()
      while (true) {
        val sa = isClosed()
        if (sa.isEmpty())
          break
        S.addAll(sa)
        updateTwithQueries()
      }
      val C = buildConjecture()
      val counterExample = checkCorrectness(C)
      if (counterExample == null) {
        return C
      } else {
        println("Counterexample found: $counterExample")
        witnessOfCounterExample(C, counterExample)
      }
    }
  }

  private fun witnessOfCounterExample(C: Set<Triple<String, String, String>>, counterExample: List<String>) {
    val projected = counterExample.filter { it in Σ }
    val size = projected.size
    println("Projected counterexample: $projected")
    for (i in projected.indices) {
      val si_1 = getStateAfterTrace(C, projected.subList(0, i))
      val si = getStateAfterTrace(C, projected.subList(0, i + 1))
      val qi_1 = membershipQuery(concat(si_1, *projected.subList(i, size).toTypedArray()))
      val qi = membershipQuery(concat(si, *projected.subList(i + 1, size).toTypedArray()))
      if (qi_1 != qi) {
        S.add(concat(si_1, projected[i]))
        E.add(concat(*projected.subList(i + 1, size).toTypedArray()))
        println("By witness counter example, S = $S, E = $E")
        return
      }
    }
  }

  private fun getStateAfterTrace(C: Set<Triple<String, String, String>>, trace: List<String>): String {
    var s = ""
    for (a in trace) {
      s = C.find { it.first == s && it.second == a }!!.third
    }
    return s
  }

  private fun checkCorrectness(C: Set<Triple<String, String, String>>): List<String>? {
    val fsp = conjectureToFSP(C)
    return LTSACall.doCompile(
      "$fsp\n$M1\n$p\n||Composite = (C || $nameM1 || $nameP).", "Composite"
    ).doCompose().propertyCheck()
  }

  private fun buildConjecture(): Set<Triple<String, String, String>> {
    // Omit the error states
    val F = S.filter { T[it] == true }
    val δ = mutableSetOf<Triple<String, String, String>>()
    // Generate transitions of the conjecture automaton
    // The transition relation δ is defined as δ(s, a) = s' where \forall e \in E: T(sae) = T(s'e).
    for (s in F) {
      for (a in Σ) {
        val s_ = S.find { s_ -> E.forall { e -> T[concat(s, a, e)] == T[concat(s_, e)] } }
        if (s_ in F) {
          δ.add(Triple(s, a, s_!!))
        }
      }
    }
    println("Desire state machine: $δ")
    return δ
  }

  private fun conjectureToFSP(δ: Set<Triple<String, String, String>>, name: String = "C"): String {
    // Final states of the conjecture automaton
    val F = δ.map { it.first }.toSet()
    // Since F \subseteq S where S \subseteq Σ*, thus map F to other names to generate the FSP spec.
    val F_map = F.foldIndexed(mutableMapOf<String, String>()) { i, m, s ->
      m[s] = if (s == "") name else "$name$i"
      m
    }
    // Divide the FSP spec into several sub processes, like A = (a -> B), B = (b -> A).
    val sm = F_map.values.fold(mutableMapOf<String, MutableList<String>>()) { m, s ->
      m[s] = mutableListOf()
      m
    }
    for (t in δ) {
      sm[F_map[t.first]]!!.add("${t.second} -> ${F_map[t.third]}")
    }
    // Build the FSP spec, C is the main process which must exist.
    val C = StringBuilder("$name = (")
    C.append(sm[name]!!.joinToString(" | "))
    C.append(')')
    // Other sub-process
    val C_tmp = sm.filter { it.key != name }
    if (C_tmp.isNotEmpty()) {
      C.append(",\n")
      val subs = C_tmp.map { "${it.key} = (${it.value.joinToString(" | ")})" }
      C.append(subs.joinToString(",\n"))
    }
    // Add the alphabet
    C.append(" + {${Σ.joinToString(", ")}}")
    C.append('.')
    println("Constructed state machine:\n$C")
    return C.toString()
  }

  private fun isClosed(): Set<String> {
    val sa = mutableSetOf<String>()
    // (S, E, T) is closed when \forall s \in S, \forall a \in Σ, \exists s' \in S, \forall e \in E: T(sae) = T(s'e)
    S.forall { s ->
      Σ.forall { a ->
        val re = S.exists { s_ -> E.forall { e -> T[concat(s, a, e)] == T[concat(s_, e)] } }
        // if (S, E, T) is not closed, add sa to S.
        if (!re) {
          sa.add(concat(s, a))
        }
        re
      }
    }
    if (sa.isEmpty()) {
      println("(S, E, T) is closed.")
    } else {
      println("(S, E, T) is not closed, add $sa to S.")
    }
    return sa
  }

  private fun updateTwithQueries() {
    // Update T by making membership queries on (S \cup S . Σ) . E
    val queries = (S union S.flatMap { s -> Σ.map { a -> concat(s, a) } })
      .flatMap { s -> E.map { e -> concat(s, e) } }
    for (query in queries) {
      T[query] = membershipQuery(query)
    }
    println("========== Updated T ==========")
    println(T)
  }

  /**
   * @param σ: the membership query to answer.
   */
  private fun membershipQuery(σ: String): Boolean {
    if (σ in T) {
      return T[σ]!!
    }
    val splited = σ.split(",")
    for (i in splited.indices) {
      val subQuery = splited.subList(0, i + 1).joinToString(",")
      if (subQuery in T && T[subQuery] == false) {
        return false
      }
    }

    val fsp = "A = (${σ.replace(",", " -> ")} -> STOP) + {${Σ.joinToString(", ")}}."
    return LTSACall.doCompile(
      "$fsp\n$M1\n$p\n||Composite = (A || $nameM1 || $nameP).", "Composite"
    ).doCompose().propertyCheck() == null
  }

  private fun concat(vararg words: String): String {
    return words.filter { it != "" }.joinToString(",")
  }

  private fun <T> Iterable<T>.forall(predicate: (T) -> Boolean): Boolean {
    for (x in this) {
      if (!predicate(x)) {
        return false
      }
    }
    return true
  }

  private fun <T> Iterable<T>.exists(predicate: (T) -> Boolean): Boolean {
    for (x in this) {
      if (predicate(x)) {
        return true
      }
    }
    return false
  }
}

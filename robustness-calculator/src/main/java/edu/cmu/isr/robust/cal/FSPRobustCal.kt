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

import edu.cmu.isr.robust.ltsa.LTSACall
import edu.cmu.isr.robust.ltsa.doCompose
import edu.cmu.isr.robust.util.Trace
import edu.cmu.isr.robust.wa.AbstractWAGenerator
import edu.cmu.isr.robust.wa.Corina02WithIO

/**
 * A general purpose AbstractRobustCal implementation for FSP specs. Users have to provide a fixed deviation model.
 * And in the deviation model, users have to define a special menu, ERR_ACTS, to identify the error actions in the
 * deviation model.
 * @param sys model should be named with SYS
 * @param env model should be named with ENV
 * @param p model should be named with P
 * @param deviation model should be named with ENV, also it needs to define a menu named ERR_ACTS to specify the error actions.
 */
open class FSPRobustCal(sys: String, env: String, p: String, private val deviation: String?,
                        verbose: Boolean) : AbstractRobustCal(sys, env, p, verbose) {
  private val errActions: List<String> = if (deviation == null) {
    emptyList()
  } else {
    LTSACall.doCompile(deviation)
    LTSACall.menuActions("ERR_ACTS")
  }

  init {
    // initialize the error actions in the deviation model
  }

  override fun genErrEnvironment(t: Trace): String? {
    return deviation
  }

  override fun isEnvEvent(a: String): Boolean {
    for (err in errActions) {
      if (a.endsWith(err))
        return false
    }
    return true
  }

  override fun isErrEvent(a: String): Boolean {
    for (err in errActions) {
      if (a.endsWith(err))
        return true
    }
    return false
  }

}

class FSPIORobustCal(sys: String, env: String, p: String,
                     deviation: String?, verbose: Boolean) : FSPRobustCal(sys, env, p, deviation, verbose) {
  override val waGenerator: AbstractWAGenerator

  init {
    LTSACall.doCompile(sys, "SYS").doCompose()
    val inputActions = LTSACall.menuActions("INPUT_ACTS")
    val outputActions = LTSACall.menuActions("OUTPUT_ACTS")
    waGenerator = Corina02WithIO(sys, env, p, inputActions, outputActions)
  }
}

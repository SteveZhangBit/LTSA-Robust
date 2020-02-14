package edu.cmu.isr.robust.cal

class ABPRobustCal(sys: String, env: String, p: String) : AbstractRobustCal(sys, env, p) {
  private val errModel = ClassLoader.getSystemResource("specs/abp/abp_env_lossy.lts").readText()

  override fun genErrEnvironment(t: List<String>): String {
    return errModel
  }

  override fun isEnvEvent(a: String): Boolean {
    return !a.endsWith("lose")
  }

  override fun isErrEvent(a: String): Boolean {
    return a.endsWith("lose")
  }
}
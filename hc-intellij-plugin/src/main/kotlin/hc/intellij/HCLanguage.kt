package hc.intellij

import com.intellij.lang.Language

object HCLanguage : Language("HotChocolate") {
    private fun readResolve(): Any = HCLanguage
}

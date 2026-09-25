// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import org.graalvm.polyglot.Context

/** Small embedding example for an Int32 declaration, including an IO Int32 result.
 * Example after interface-core preparation:
 * build/interface-core/typed-foreign-exports/managed.json thc-interface-fixture-0.1 ForeignExportManaged thc_next 3
 */
object ManagedExportsDemo {
    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.size >= 4) { "CORE.json UNIT MODULE C_SYMBOL [INT32 ...]" }
        Context.newBuilder("thc").build().use { context ->
            val units = loadManagedExports(context, listOf(arguments[0]))
            val function = units.getMember(arguments[1]).getMember(arguments[2]).getMember(arguments[3])
            val inputs = arguments.drop(4).map(String::toInt).toTypedArray()
            println(function.execute(*inputs))
        }
    }
}

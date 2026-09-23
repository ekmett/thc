package thc.runtime

/** Select a shared Long instruction, without rewriting any exact Core type proof. */
internal fun scalar64PrimitiveOperation(name: String): String = when (name) {
    "int64ToWord64#", "wordToWord64#" -> "int2Word#"
    "word64ToInt64#", "word64ToWord#" -> "word2Int#"
    "negateInt64#" -> "negateInt#"
    "plusInt64#", "plusWord64#" -> "+#"
    "subInt64#", "subWord64#" -> "-#"
    "timesInt64#", "timesWord64#" -> "*#"
    "quotInt64#" -> "quotInt#"
    "remInt64#" -> "remInt#"
    "quotWord64#" -> "quotWord#"
    "remWord64#" -> "remWord#"
    "eqInt64#", "eqWord64#" -> "==#"
    "neInt64#", "neWord64#" -> "/=#"
    "ltInt64#" -> "<#"
    "leInt64#" -> "<=#"
    "gtInt64#" -> ">#"
    "geInt64#" -> ">=#"
    "ltWord64#" -> "ltWord#"
    "leWord64#" -> "leWord#"
    "gtWord64#" -> "gtWord#"
    "geWord64#" -> "geWord#"
    "and64#" -> "and#"
    "or64#" -> "or#"
    "xor64#" -> "xor#"
    "not64#" -> "not#"
    "uncheckedIShiftL64#", "uncheckedShiftL64#" -> "uncheckedShiftL#"
    "uncheckedIShiftRA64#" -> "uncheckedIShiftRA#"
    "uncheckedIShiftRL64#", "uncheckedShiftRL64#" -> "uncheckedShiftRL#"
    else -> name
}

/** Unsigned decimal literals retain their complete bit pattern in a Long. */
internal fun word64Literal(value: String): Long {
    val number = value.toULongOrNull()
    if (number == null || number.toString() != value) throw RuntimeFault("Invalid word64 literal: $value")
    return number.toLong()
}

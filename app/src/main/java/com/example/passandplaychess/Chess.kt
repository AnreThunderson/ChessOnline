fun Move.toUci(): String {
    return when (promotion) {
        null -> ""
        else -> ""  // make the when exhaustive
    }
}
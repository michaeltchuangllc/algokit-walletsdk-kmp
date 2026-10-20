package com.michaeltchuang.walletsdk.ui.base.utils

fun isValidDecimalInput(input: String): Boolean = input.all { it in '0'..'9' || it == '.' } && input.count { it == '.' } <= 1

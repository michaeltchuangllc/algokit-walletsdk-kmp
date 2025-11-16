package com.michaeltchuang.walletsdk.ui.base.utils

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * iOS implementation: checks if running in debug configuration.
 * This checks if assertions are enabled, which is a reliable way to detect debug builds on iOS.
 */
@OptIn(ExperimentalNativeApi::class)
actual fun isDebugBuild(): Boolean = Platform.isDebugBinary

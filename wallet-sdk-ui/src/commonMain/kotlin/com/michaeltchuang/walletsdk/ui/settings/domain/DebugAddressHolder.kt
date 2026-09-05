package com.michaeltchuang.walletsdk.ui.settings.domain

object DebugAddressHolder {
    var viewerAddresses: ArrayList<String> = arrayListOf("", "", "")
    var creatorAddress: String = ""

    fun getViewerAddress(index: Int): String = viewerAddresses.getOrNull(index).orEmpty()

    fun setViewerAddress(
        index: Int,
        address: String,
    ) {
        while (viewerAddresses.size <= index) {
            viewerAddresses.add("")
        }
        viewerAddresses[index] = address
    }
}

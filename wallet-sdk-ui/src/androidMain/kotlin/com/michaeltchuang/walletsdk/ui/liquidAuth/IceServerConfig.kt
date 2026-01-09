package com.michaeltchuang.walletsdk.ui.liquidAuth

import org.webrtc.PeerConnection

object IceServerConfig {
    private const val NODELY_TURN_USERNAME = "liquid-auth"
    private const val NODELY_TURN_CREDENTIAL = "sqmcP4MiTKMT4TGEDSk9jgHY"
    private const val TURN_USERNAME = "fc7708976bf5d60be20c5a1d"
    private const val TURN_CREDENTIAL = "sVpEREQGGhXOw4gX"

    private fun createIceServer(
        uri: String,
        username: String,
        password: String,
    ): PeerConnection.IceServer =
        PeerConnection.IceServer
            .builder(uri)
            .setUsername(username)
            .setPassword(password)
            .createIceServer()

    val iceServers =
        listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            createIceServer(
                "turn:global.turn.nodely.network:80?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turns:global.turn.nodely.network:443?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turn:eu.turn.nodely.io:80?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turns:eu.turn.nodely.io:443?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turn:us.turn.nodely.io:80?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turns:us.turn.nodely.io:443?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turn:global.relay.metered.ca:80",
                TURN_USERNAME,
                TURN_CREDENTIAL,
            ),
            createIceServer(
                "turn:global.relay.metered.ca:80?transport=tcp",
                TURN_USERNAME,
                TURN_CREDENTIAL,
            ),
            createIceServer(
                "turn:global.relay.metered.ca:443",
                TURN_USERNAME,
                TURN_CREDENTIAL,
            ),
            createIceServer(
                "turns:global.relay.metered.ca:443?transport=tcp",
                TURN_USERNAME,
                TURN_CREDENTIAL,
            ),
        )
}

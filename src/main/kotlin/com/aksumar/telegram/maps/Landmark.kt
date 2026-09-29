package com.aksumar.telegram.maps

internal enum class LandmarkKind(val badge: String) {
    SUBWAY("U"),
    SUBURBAN_RAIL("S"),
    TRAM("T"),
    BUS("B");

    val isRail: Boolean
        get() = this == SUBWAY || this == SUBURBAN_RAIL
}

internal data class Landmark(val name: String, val kind: LandmarkKind, val x: Int, val y: Int)

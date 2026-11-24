package com.example.parkingapp

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

class MapTilerTileSource : OnlineTileSourceBase(
    "MapTilerStreets",
    0,
    19,
    256,
    ".png",
    arrayOf("https://api.maptiler.com/maps/streets-v2/"),
    "© MapTiler © OpenStreetMap contributors"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "https://api.maptiler.com/maps/streets-v2/$zoom/$x/$y.png?key=${BuildConfig.MAPTILER_API_KEY}"
    }
}

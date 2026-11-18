package com.hackdecouverte.loc

import com.hackdecouverte.map.IndoorMap

/** Public bridge so tests in other packages can use the internal synthetic maps. */
object SyntheticMapsAccess {
    fun lCorridor(): IndoorMap = SyntheticMaps.lCorridor()
}

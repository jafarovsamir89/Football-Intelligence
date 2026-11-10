package az.simplesoft.footballintelligence.data

/** Rejects source rows whose event year contradicts the season declared by that source. */
object SeasonDateValidity {
    fun isPlausible(eventDate:String?,season:String?):Boolean {
        if(eventDate==null||season==null) return true
        val eventYear=eventDate.take(4).toIntOrNull() ?: return false
        val seasonYear=Regex("^\\d{4}").find(season)?.value?.toIntOrNull() ?: return true
        // Football seasons may cross New Year, and a few catalogs label the following calendar
        // year. A multi-decade mismatch is always invalid, while the declared year and next two
        // calendar years cover the common season conventions.
        return eventYear in seasonYear..(seasonYear+2)
    }
}

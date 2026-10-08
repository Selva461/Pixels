package androidx.exifinterface.media

class ExifInterface {
    constructor(fd: java.io.FileDescriptor)
    constructor(stream: java.io.InputStream)
    constructor(path: String)
    fun getAttribute(tag: String): String? = TODO()
    fun getAttributeInt(tag: String, defaultValue: Int): Int = TODO()
    fun setAttribute(tag: String, value: String?): Unit = TODO()
    fun saveAttributes(): Unit = TODO()
    fun setLatLong(latitude: Double, longitude: Double): Unit = TODO()
    val latLong: DoubleArray? get() = TODO()
    val rotationDegrees: Int get() = TODO()
    val isFlipped: Boolean get() = TODO()

    companion object {
        const val ORIENTATION_NORMAL = 0
        const val TAG_DATETIME = "TAG_DATETIME"
        const val TAG_DATETIME_DIGITIZED = "TAG_DATETIME_DIGITIZED"
        const val TAG_DATETIME_ORIGINAL = "TAG_DATETIME_ORIGINAL"
        const val TAG_EXPOSURE_BIAS_VALUE = "TAG_EXPOSURE_BIAS_VALUE"
        const val TAG_EXPOSURE_TIME = "TAG_EXPOSURE_TIME"
        const val TAG_FLASH = "TAG_FLASH"
        const val TAG_FOCAL_LENGTH = "TAG_FOCAL_LENGTH"
        const val TAG_FOCAL_LENGTH_IN_35MM_FILM = "TAG_FOCAL_LENGTH_IN_35MM_FILM"
        const val TAG_F_NUMBER = "TAG_F_NUMBER"
        const val TAG_GPS_ALTITUDE = "TAG_GPS_ALTITUDE"
        const val TAG_GPS_ALTITUDE_REF = "TAG_GPS_ALTITUDE_REF"
        const val TAG_GPS_DATESTAMP = "TAG_GPS_DATESTAMP"
        const val TAG_GPS_LATITUDE = "TAG_GPS_LATITUDE"
        const val TAG_GPS_LATITUDE_REF = "TAG_GPS_LATITUDE_REF"
        const val TAG_GPS_LONGITUDE = "TAG_GPS_LONGITUDE"
        const val TAG_GPS_LONGITUDE_REF = "TAG_GPS_LONGITUDE_REF"
        const val TAG_GPS_TIMESTAMP = "TAG_GPS_TIMESTAMP"
        const val TAG_LENS_MAKE = "TAG_LENS_MAKE"
        const val TAG_LENS_MODEL = "TAG_LENS_MODEL"
        const val TAG_MAKE = "TAG_MAKE"
        const val TAG_MODEL = "TAG_MODEL"
        const val TAG_OFFSET_TIME = "TAG_OFFSET_TIME"
        const val TAG_OFFSET_TIME_ORIGINAL = "TAG_OFFSET_TIME_ORIGINAL"
        const val TAG_ORIENTATION = "TAG_ORIENTATION"
        const val TAG_PHOTOGRAPHIC_SENSITIVITY = "TAG_PHOTOGRAPHIC_SENSITIVITY"
        const val TAG_SOFTWARE = "TAG_SOFTWARE"
        const val TAG_WHITE_BALANCE = "TAG_WHITE_BALANCE"
    }
}

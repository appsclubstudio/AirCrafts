package com.example.adsb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/** Android/JVM client for the documented ADSB.lol v2 GET endpoints. */
class AdsbLolV2Client(
    private val baseUrl: String = "https://api.adsb.lol",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000
) {
    init {
        require(baseUrl.startsWith("https://") && baseUrl.removeSuffix("/").isNotBlank())
        require(connectTimeoutMs > 0 && readTimeoutMs > 0)
    }

    sealed interface Result {
        data class Success(val json: JSONObject) : Result
        data class InvalidParameter(val parameter: String, val reason: String) : Result
        data class Timeout(val cause: SocketTimeoutException) : Result
        data class NetworkError(val cause: IOException) : Result
        data class HttpError(val statusCode: Int, val body: String?, val retryAfter: String?) : Result
        data class InvalidJson(val body: String, val cause: JSONException) : Result
    }

    suspend fun pia(): Result = get("/v2/pia")
    suspend fun military(): Result = get("/v2/mil")
    suspend fun ladd(): Result = get("/v2/ladd")

    suspend fun squawk(squawk: String): Result = one("squawk", squawk, "/v2/squawk") {
        it.length == 4 && it.all { digit -> digit in '0'..'7' }
    }
    suspend fun sqk(squawk: String): Result = one("squawk", squawk, "/v2/sqk") {
        it.length == 4 && it.all { digit -> digit in '0'..'7' }
    }
    suspend fun aircraftType(type: String): Result = one("type", type, "/v2/type") {
        it.matches(Regex("[A-Za-z0-9]{2,4}"))
    }
    suspend fun registration(registration: String): Result =
        one("registration", registration, "/v2/registration") { it.isNotBlank() }
    suspend fun reg(registration: String): Result =
        one("registration", registration, "/v2/reg") { it.isNotBlank() }
    suspend fun icao(icaoHex: String): Result = one("icaoHex", icaoHex, "/v2/icao") {
        it.matches(Regex("[A-Fa-f0-9]{6}"))
    }
    suspend fun hex(icaoHex: String): Result = one("icaoHex", icaoHex, "/v2/hex") {
        it.matches(Regex("[A-Fa-f0-9]{6}"))
    }
    suspend fun callsign(callsign: String): Result =
        one("callsign", callsign, "/v2/callsign") { it.isNotBlank() }

    suspend fun point(lat: Double, lon: Double, radiusNm: Int): Result =
        location(lat, lon, radiusNm, "/v2/point/$lat/$lon/$radiusNm")
    suspend fun latLonDist(lat: Double, lon: Double, radiusNm: Int): Result =
        location(lat, lon, radiusNm, "/v2/lat/$lat/lon/$lon/dist/$radiusNm")
    suspend fun closest(lat: Double, lon: Double, radiusNm: Int): Result =
        location(lat, lon, radiusNm, "/v2/closest/$lat/$lon/$radiusNm")

    private suspend fun one(name: String, value: String, prefix: String, valid: (String) -> Boolean): Result {
        val cleaned = value.trim()
        if (!valid(cleaned) || cleaned.any { it.isISOControl() } || cleaned.length > 1000) {
            return Result.InvalidParameter(name, "Invalid or empty $name")
        }
        val encoded = URLEncoder.encode(cleaned, "UTF-8").replace("+", "%20")
        return get("$prefix/$encoded")
    }

    private suspend fun location(lat: Double, lon: Double, radiusNm: Int, path: String): Result {
        if (!lat.isFinite() || lat !in -90.0..90.0) return Result.InvalidParameter("lat", "Must be between -90 and 90")
        if (!lon.isFinite() || lon !in -180.0..180.0) return Result.InvalidParameter("lon", "Must be between -180 and 180")
        if (radiusNm !in 0..250) return Result.InvalidParameter("radiusNm", "Must be between 0 and 250 nautical miles")
        return get(path)
    }

    private suspend fun get(path: String): Result = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                Result.HttpError(status, body.ifEmpty { null }, connection.getHeaderField("Retry-After"))
            } else {
                try { Result.Success(JSONObject(body)) }
                catch (e: JSONException) { Result.InvalidJson(body, e) }
            }
        } catch (e: SocketTimeoutException) {
            Result.Timeout(e)
        } catch (e: UnknownHostException) {
            Result.NetworkError(e)
        } catch (e: IOException) {
            Result.NetworkError(e)
        } catch (e: CancellationException) {
            throw e
        } finally {
            connection?.disconnect()
        }
    }
}

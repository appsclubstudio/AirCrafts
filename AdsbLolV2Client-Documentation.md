# ADSB.lol v2 Kotlin client

Android wrapper for the [ADSB.lol v2 API](https://api.adsb.lol/docs). Each documented v2 GET path has its own `suspend` method. Responses remain raw JSON as `org.json.JSONObject`.

## Setup

1. Copy [`AdsbLolV2Client.kt`](AdsbLolV2Client.kt) into your Android module and change its `package` declaration to match your project.
2. Add Kotlin coroutines to the module if it is not already present:

   ```kotlin
   dependencies {
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<version-used-by-your-project>")
   }
   ```

3. Add Internet permission in `AndroidManifest.xml`:

   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   ```

The implementation uses Android's `org.json` and the JVM's `HttpURLConnection`. Calls are suspendable and run their blocking network work on `Dispatchers.IO`. Use them from a coroutine, such as `viewModelScope.launch`.

## Create a client

```kotlin
val adsb = AdsbLolV2Client()

// Optional configuration; times are milliseconds.
val custom = AdsbLolV2Client(
    baseUrl = "https://api.adsb.lol",
    connectTimeoutMs = 10_000,
    readTimeoutMs = 15_000
)
```

The constructor requires an HTTPS URL and positive timeout values. Invalid constructor options throw `IllegalArgumentException`.

## Endpoint reference

All methods return `AdsbLolV2Client.Result`. Parameters named `radiusNm` are measured in nautical miles. The alias paths below are intentionally separate methods.

| Method | GET path | Required input | Meaning |
| --- | --- | --- | --- |
| `pia()` | `/v2/pia` | None | Aircraft using PIA addresses. |
| `military()` | `/v2/mil` | None | Military registered aircraft. |
| `ladd()` | `/v2/ladd` | None | Aircraft on the LADD list. |
| `squawk(squawk: String)` | `/v2/squawk/{squawk}` | Four octal digits, e.g. `"7700"`. | Aircraft with a specified transponder squawk. |
| `sqk(squawk: String)` | `/v2/sqk/{squawk}` | Same as `squawk`. | Alias path for squawk search. |
| `aircraftType(type: String)` | `/v2/type/{aircraft_type}` | Two to four letters or digits, e.g. `"A320"`. | Aircraft matching an ICAO type designator. |
| `registration(registration: String)` | `/v2/registration/{registration}` | Nonblank registration, e.g. `"G-KELS"`. | Search by aircraft registration. |
| `reg(registration: String)` | `/v2/reg/{registration}` | Same as `registration`. | Alias path for registration search. |
| `icao(icaoHex: String)` | `/v2/icao/{icao_hex}` | Six hexadecimal characters, e.g. `"4CA87C"`. | Search by Mode S / ICAO hex address. |
| `hex(icaoHex: String)` | `/v2/hex/{icao_hex}` | Same as `icao`. | Alias path for hex address search. |
| `callsign(callsign: String)` | `/v2/callsign/{callsign}` | Nonblank callsign, e.g. `"JBU1942"`. | Search by callsign. |
| `point(lat: Double, lon: Double, radiusNm: Int)` | `/v2/point/{lat}/{lon}/{radius}` | Latitude −90…90, longitude −180…180, radius 0…250. | Aircraft within a circle. |
| `latLonDist(lat: Double, lon: Double, radiusNm: Int)` | `/v2/lat/{lat}/lon/{lon}/dist/{radius}` | Same as `point`. | Alias path for circle search. |
| `closest(lat: Double, lon: Double, radiusNm: Int)` | `/v2/closest/{lat}/{lon}/{radius}` | Same as `point`. | Closest aircraft inside the specified circle. |

String arguments are trimmed and URL encoded before sending. The client rejects control characters and strings longer than 1,000 characters. Coordinates must be finite numbers. Client validation is a convenience; the server can apply further constraints and return HTTP errors.

## Example: aircraft near a map center

```kotlin
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject

viewModelScope.launch {
    when (val result = adsb.point(33.6844, 73.0479, 250)) {
        is AdsbLolV2Client.Result.Success -> {
            val aircraft = result.json.getJSONArray("ac")
            for (i in 0 until aircraft.length()) {
                val plane: JSONObject = aircraft.getJSONObject(i)
                val hex = plane.getString("hex")
                val lat = plane.optDouble("lat", Double.NaN)
                val lon = plane.optDouble("lon", Double.NaN)
                // Update map marker when coordinates are finite.
            }
        }
        is AdsbLolV2Client.Result.InvalidParameter ->
            showError("${result.parameter}: ${result.reason}")
        is AdsbLolV2Client.Result.Timeout -> showError("Request timed out")
        is AdsbLolV2Client.Result.NetworkError -> showError("Network unavailable")
        is AdsbLolV2Client.Result.HttpError ->
            showError("Server returned ${result.statusCode}")
        is AdsbLolV2Client.Result.InvalidJson ->
            showError("Server returned invalid JSON")
    }
}
```

Replace `showError` and the marker update with your app's UI code. The example passes 250 nautical miles, the maximum radius documented by the service.

## Example: look up one aircraft

```kotlin
val result = adsb.icao("4CA87C")
if (result is AdsbLolV2Client.Result.Success) {
    val aircraft = result.json.getJSONArray("ac")
    val first = aircraft.optJSONObject(0) // May be null if no aircraft is currently reported.
    val callsign = first?.optString("flight")?.trim()
    val registration = first?.optString("r")
}
```

The API returns live observations. An empty `ac` array means the lookup yielded no current aircraft; it is still a successful response.

## Result and error handling

| Result | When it occurs | Useful fields / response |
| --- | --- | --- |
| `Success` | HTTP 2xx with a JSON object. | `json: JSONObject`; inspect `ac`, `total`, `now`, `msg`. |
| `InvalidParameter` | Local input validation failed; no request sent. | `parameter`, `reason`; correct the input. |
| `Timeout` | Connection or response read timed out. | `cause`; retry based on your app's policy. |
| `NetworkError` | DNS, connection, TLS or other I/O failure. | `cause`; check connectivity. |
| `HttpError` | Non-2xx HTTP response, including 422 or 429. | `statusCode`, optional `body`, optional `retryAfter`. |
| `InvalidJson` | HTTP 2xx response body is not a JSON object. | `body`, `cause`. |

The client does not automatically retry. For a 429 response, use `retryAfter` if present and avoid rapid retry loops. Coroutine cancellation is propagated; it is not converted into a result.

## Response JSON

The wrapper deliberately does not define aircraft data classes, so all current and future API fields remain accessible. Successful v2 responses have this shape (fields within an aircraft vary):

```json
{
  "ac": [{ "hex": "4ca87c", "flight": "EXAMPLE", "lat": 51.9, "lon": 2.8 }],
  "ctime": 0,
  "msg": "No error",
  "now": 0,
  "ptime": 0,
  "total": 1
}
```

This JSON is an illustrative shape, not a live response. Fields such as `flight`, `r`, `lat`, and `lon` may be absent or null; prefer `opt*` accessors for optional data. Refer to the [official OpenAPI schema](https://api.adsb.lol/api/openapi.json) for the current response field definitions.

## Attribution and operation

ADSB.lol describes its API data as ODbL 1.0 licensed. Check the [service documentation](https://api.adsb.lol/docs) for current terms and use an appropriate attribution in your app. Its documentation also asks production users to contact the project so changes to the service can be coordinated.

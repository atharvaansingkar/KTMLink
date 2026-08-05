package com.ktmlink

import android.util.Log
import org.json.JSONObject
import java.net.URL

private const val TAG = "KtmWeather"

data class IdleWeatherData(val tempC: Int, val condition: String, val usAqi: Int)

object KtmIdleWeatherFetcher {

    fun fetch(lat: Double, lon: Double, onResult: (IdleWeatherData?) -> Unit) {
        Thread {
            try {
                val tempC = fetchTemp(lat, lon)
                val aqi = fetchAqi(lat, lon)
                onResult(IdleWeatherData(tempC.first, tempC.second, aqi))
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed: ${e.message}")
                onResult(null)
            }
        }.start()
    }

    private fun fetchTemp(lat: Double, lon: Double): Pair<Int, String> {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weathercode&timezone=auto"
        val json = JSONObject(URL(url).readText())
        val current = json.getJSONObject("current")
        val tempC = current.getDouble("temperature_2m").toInt()
        val code = current.getInt("weathercode")
        return Pair(tempC, wmoCondition(code))
    }

    private fun fetchAqi(lat: Double, lon: Double): Int {
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi"
        val json = JSONObject(URL(url).readText())
        return json.getJSONObject("current").getInt("us_aqi")
    }

    private fun wmoCondition(code: Int): String = when (code) {
        0    -> "Clear sky"
        1    -> "Mainly clear"
        2    -> "Part. cloudy"
        3    -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        80, 81, 82 -> "Rain shower"
        85, 86 -> "Snow shower"
        95   -> "Thunderstorm"
        96, 99 -> "Thund.+hail"
        else -> "Unknown"
    }
}

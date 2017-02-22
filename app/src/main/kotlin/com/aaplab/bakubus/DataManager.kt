package com.aaplab.bakubus

import com.google.maps.GeoApiContext
import com.google.maps.RoadsApi
import com.google.maps.model.LatLng
import com.google.maps.model.SnappedPoint
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import rx.Observable
import rx.lang.kotlin.observable
import java.text.NumberFormat
import java.text.ParseException
import java.util.*

/**
 * Created by user on 22.02.16.
 */
object DataManager {
    val BAKU_BUS_API_PATH = "http://bakubus.az/az/ajax/getPaths/"
    val BAKU_BUS_API_POSITION = "http://bakubus.az/az/ajax/apiNew/"

    val routeIds = mapOf("H1" to "10034", "1" to "11032", "2" to "11035", "3" to "11037", "5" to "11031",
            "6" to "11033", "8" to "11034", "13" to "11039", "14" to "11036", "21" to "11040")

    fun routes(): Observable<List<Bus>> {
        return observable<JSONObject> {
            subscriber ->
            try {
                val request = Request.Builder()
                        .url(BAKU_BUS_API_POSITION)
                        .build()

                val response = OkHttpClient().newCall(request).execute()

                subscriber.onNext(JSONObject(response.body().string()))
                subscriber.onCompleted()
            } catch(e: Throwable) {
                subscriber.onError(e)
            }
        }.flatMap {
            json ->
            observable<List<Bus>> {
                val array = json.getJSONArray("BUS")
                val routes = (0..array.length() - 1).mapTo(ArrayList<Bus>()) { parseBus(array.getJSONObject(it).getJSONObject("@attributes")) }

                it.onNext(routes)
                it.onCompleted()
            }
        }
    }

    fun path(route: String): Observable<List<SnappedPoint>> {
        return observable<JSONObject> {
            subscriber ->

            try {
                val request = Request.Builder()
                        .url(BAKU_BUS_API_PATH + routeIds[route])
                        .build()
                val response = OkHttpClient().newCall(request).execute()

                subscriber.onNext(JSONObject(response.body().string()))
                subscriber.onCompleted()
            } catch(e: Throwable) {
                subscriber.onError(e)
            }
        }.flatMap { json ->
            observable<List<SnappedPoint>> {
                try {
                    val points = ArrayList<LatLng>()
                    val busStops = json.getJSONObject("Forward").getJSONArray("busstops")

                    for (i in 0..busStops.length() - 1) {
                        val lat = busStops.getJSONObject(i).getString("latitude").replace(",", ".")
                        val lng = busStops.getJSONObject(i).getString("longitude").replace(",", ".")

                        points.add(LatLng(lat.toDouble(), lng.toDouble()))
                    }

                    val context = GeoApiContext().setApiKey("AIzaSyDbFBAfcW65Pik1tzfRaApAU91713jv9U0")
                    val road = RoadsApi.snapToRoads(context, true, *points.toTypedArray()).await()

                    it.onNext(road.asList())
                    it.onCompleted()
                } catch(e: JSONException) {
                    it.onError(e)
                }
            }
        }
    }
}

class Bus(val id: Int, val plate: String, val code: String, val route: String, val currentStop: String, val prevStop: String, val lat: Double, val lng: Double)

fun parseBus(json: JSONObject): Bus {
    val id = json.getInt("BUS_ID")
    val plate = json.getString("PLATE")

    val route = json.getString("ROUTE_NAME")
    val code = json.getString("DISPLAY_ROUTE_CODE")

    val currentStop = json.getString("CURRENT_STOP")
    val prevStop = json.getString("PREV_STOP")

    var lat: Double
    var lng: Double

    try {
        val format = NumberFormat.getNumberInstance(Locale.FRANCE)

        lat = format.parse(json.getString("LATITUDE")).toDouble()
        lng = format.parse(json.getString("LONGITUDE")).toDouble()
    } catch (e: ParseException) {

        lat = json.getString("LATITUDE").toDouble()
        lng = json.getString("LONGITUDE").toDouble()
    }

    return Bus(id, plate, code, route, currentStop, prevStop, lat, lng)
}
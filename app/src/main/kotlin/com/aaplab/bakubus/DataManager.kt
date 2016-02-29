package com.aaplab.bakubus

import okhttp3.OkHttpClient
import okhttp3.Request
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
    val BAKU_BUS_API_URL = "http://bakubus.az/az/ajax/apiNew"

    fun routes(): Observable<List<Bus>> {
        return observable<JSONObject> {
            subscriber ->
            try {
                val request = Request.Builder()
                        .url(BAKU_BUS_API_URL)
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
                val routes = ArrayList<Bus>()

                for (i in 0..array.length() - 1) {
                    routes.add(createBus(array.getJSONObject(i).getJSONObject("@attributes")))
                }

                it.onNext(routes)
                it.onCompleted()
            }
        }
    }
}

class Bus {
    val id: Int
    val plate: String
    val code: String
    val route: String
    val currentStop: String
    val prevStop: String
    val lat: Double
    val lng: Double

    constructor(id: Int, plate: String, code: String, route: String, currentStop: String, prevStop: String, lat: Double, lng: Double) {
        this.id = id
        this.plate = plate
        this.code = code
        this.route = route
        this.currentStop = currentStop
        this.prevStop = prevStop
        this.lat = lat
        this.lng = lng
    }
}

fun createBus(json: JSONObject): Bus {
    val id = json.getInt("BUS_ID")
    val plate = json.getString("PLATE")

    val route = json.getString("ROUTE_NAME")
    val code = json.getString("DISPLAY_ROUTE_CODE")

    val currentStop = json.getString("CURRENT_STOP")
    val prevStop = json.getString("PREV_STOP")

    val lat: Double
    val lng: Double

    try {
        val format = NumberFormat.getNumberInstance(Locale.FRANCE);

        lat = format.parse(json.getString("LATITUDE")).toDouble()
        lng = format.parse(json.getString("LONGITUDE")).toDouble()
    } catch (e: ParseException) {

        lat = json.getString("LATITUDE").toDouble()
        lng = json.getString("LONGITUDE").toDouble()
    }

    return Bus(id, plate, code, route, currentStop, prevStop, lat, lng)
}
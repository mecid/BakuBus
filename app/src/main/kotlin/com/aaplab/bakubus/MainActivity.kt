package com.aaplab.bakubus

import android.Manifest
import android.app.Dialog
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.app.AppCompatDialogFragment
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.maps.android.ui.IconGenerator
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription
import timber.log.Timber
import java.util.*

/**
 * Created by user on 22.02.16.
 */
class MainActivity : AppCompatActivity(), GoogleMap.OnMyLocationChangeListener {
    val MY_LOCATION_REQUEST_CODE = 88

    val subscriptions = CompositeSubscription()
    val markers = ArrayList<Marker?>()
    var map: GoogleMap? = null
    val timer = Timer()

    val markerIconGenerator: IconGenerator by lazy {
        IconGenerator(this).apply {
            setStyle(IconGenerator.STYLE_RED)
            setColor(ActivityCompat.getColor(this@MainActivity, R.color.colorPrimary))
        }
    }

    val preferences: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        val mapFragment: SupportMapFragment =
                supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment

        mapFragment.getMapAsync {
            map: GoogleMap ->
            configure(map)
            enableMyLocation(map)

            downloadAndShowPath()
            timer.schedule(DataUpdateTimerTask(), Date(), 10000)
        }
    }

    fun enableMyLocation(map: GoogleMap) {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.setOnMyLocationChangeListener(this)
            map.isMyLocationEnabled = true
        } else {
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), MY_LOCATION_REQUEST_CODE)
        }
    }

    override fun onMyLocationChange(location: Location?) {
        if (markers.isNotEmpty()) {
            map?.setOnMyLocationChangeListener(null)
            zoomToNearestMarker(location)
        }
    }

    fun downloadAndShowPath() {
        subscriptions.add(
                DataManager.path(preferences.getString("route", "14"))
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeOn(Schedulers.io())
                        .subscribe({ road ->
                            val polyline = PolylineOptions()
                            road.forEach { polyline.add(LatLng(it.location.lat, it.location.lng)) }
                            polyline.geodesic(true).width(10f).color(ContextCompat.getColor(this, R.color.colorAccent))
//                            map?.addPolyline(polyline)
                        }, { error ->
                            Timber.w(error, "")
                        })
        )
    }

    fun downloadAndShowRoutes() {
        subscriptions.add(DataManager.routes().retry(3)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe({
                    routes: List<Bus> ->
                    ActivityCompat.invalidateOptionsMenu(this)
                    val route = preferences.getString("route", "14")

                    markers.forEach { it?.remove() }
                    routes.filter { TextUtils.equals(route, it.code) }.forEach {
                        val bitmapWithBusRoute = markerIconGenerator.makeIcon(it.code)
                        val descriptorWithBusRoute = BitmapDescriptorFactory.fromBitmap(bitmapWithBusRoute)

                        markers.add(
                                map?.addMarker(
                                        MarkerOptions()
                                                .icon(descriptorWithBusRoute)
                                                .title(it.plate).snippet(it.route)
                                                .position(LatLng(it.lat, it.lng))
                                )
                        )
                    }
                }, {
                    Snackbar.make(findViewById(R.id.coordinator)!!, R.string.internet_required, Snackbar.LENGTH_LONG).show()
                    Timber.d(it, "")
                })
        )
    }

    fun configure(map: GoogleMap) {
        findViewById(R.id.fab)!!.setOnClickListener { zoomToNearestMarker(map.myLocation) }
        this.map = map

        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
    }

    fun zoomToNearestMarker(location: Location?) {
        map?.let { map ->
            location?.let { location ->
                if (markers.isNotEmpty()) {
                    markers.sortBy { marker ->
                        val routeLocation = Location(LocationManager.NETWORK_PROVIDER)
                        routeLocation.longitude = marker?.position?.latitude ?: 0.0
                        routeLocation.latitude = marker?.position?.longitude ?: 0.0

                        location.distanceTo(routeLocation)
                    }

                    val bounds = LatLngBounds.Builder()
                    bounds.include(LatLng(location.latitude, location.longitude))
                    bounds.include(LatLng(markers[0]?.position?.latitude ?: 0.0, markers[0]?.position?.longitude ?: 0.0))

                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == MY_LOCATION_REQUEST_CODE) {
            map?.let { map ->
                enableMyLocation(map)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        if (item?.itemId == R.id.menu_filter) {
            val filter = FilterDialogFragment()
            filter.preferences = preferences
            filter.routeChanged = { downloadAndShowRoutes() }
            filter.show(supportFragmentManager, "filter")
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        timer.cancel()

        if (!subscriptions.isUnsubscribed) {
            subscriptions.unsubscribe()
        }
    }

    inner class DataUpdateTimerTask : TimerTask() {
        override fun run() {
            downloadAndShowRoutes()
        }
    }
}

class FilterDialogFragment : AppCompatDialogFragment() {
    var preferences: SharedPreferences? = null
    var routeChanged: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val selectedRoute = preferences?.getString("route", "14")
        val titles = ArrayList(DataManager.routeIds.keys)

        return AlertDialog.Builder(activity, R.style.AlertDialog)
                .setSingleChoiceItems(titles.toTypedArray(), titles.indexOf(selectedRoute)) {
                    dialogInterface: DialogInterface, i: Int ->
                    preferences?.edit()?.putString("route", titles[i])?.apply()
                }
                .setPositiveButton(android.R.string.ok) {
                    dialogInterface: DialogInterface, i: Int ->
                    routeChanged?.invoke()
                }.setNegativeButton(android.R.string.cancel, null).create()
    }
}
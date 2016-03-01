package com.aaplab.bakubus

import android.Manifest
import android.app.Dialog
import android.content.Context
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
import android.view.Menu
import android.view.MenuItem
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import com.google.maps.android.ui.IconGenerator
import org.json.JSONArray
import org.json.JSONException
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.Subscriptions
import java.util.*

/**
 * Created by user on 22.02.16.
 */
class MainActivity : AppCompatActivity(), GoogleMap.OnMyLocationChangeListener {
    val MY_LOCATION_REQUEST_CODE = 88

    val preferences: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    var clusterManager: ClusterManager<BusItem>? = null
    var subscription: Subscription = Subscriptions.empty()
    var routeMap: Map<String, List<Bus>>? = null
    var selectedRoutes = ArrayList<String>()
    val markers = ArrayList<BusItem>()
    var map: GoogleMap? = null
    val timer = Timer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        loadSelectedRoutes()

        val mapFragment: SupportMapFragment =
                supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment

        mapFragment.getMapAsync {
            map: GoogleMap ->
            configure(map)
            enableMyLocation(map)

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

    fun loadSelectedRoutes() {
        try {
            val json = JSONArray(preferences.getString("filter", ""))

            selectedRoutes.clear()

            for (i in 0..json.length() - 1) {
                selectedRoutes.add(json.getString(i))
            }
        } catch(ignored: JSONException) {
        }
    }

    fun downloadAndShowRoutes() {
        clusterManager?.let {
            clusterManager ->
            subscription = DataManager.routes().retry(3)
                    .map {
                        routes ->
                        val routeMap = HashMap<String, List<Bus>>()

                        for (bus in routes) {
                            if (routeMap.containsKey(bus.code)) {
                                val items: ArrayList<Bus> = routeMap[bus.code] as ArrayList<Bus>
                                items.add(bus)
                            } else {
                                val items = ArrayList<Bus>()
                                items.add(bus)
                                routeMap[bus.code] = items
                            }
                        }

                        routeMap
                    }
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribeOn(Schedulers.io())
                    .subscribe ({
                        routeMap: Map<String, List<Bus>> ->
                        ActivityCompat.invalidateOptionsMenu(this)
                        this.routeMap = routeMap

                        markers.clear()
                        clusterManager.clearItems()

                        for ((k, v) in routeMap) {
                            if (selectedRoutes.isEmpty()) {
                                clusterManager.addItems(v.map { BusItem(it) })
                                markers.addAll(v.map { BusItem(it) })
                            } else {
                                if (selectedRoutes.contains(k)) {
                                    clusterManager.addItems(v.map { BusItem(it) })
                                    markers.addAll(v.map { BusItem(it) })
                                }
                            }
                        }

                        clusterManager.cluster()
                    }, {
                        Snackbar.make(findViewById(R.id.coordinator),
                                R.string.internet_required, Snackbar.LENGTH_LONG).show()
                    })
        }
    }

    fun configure(map: GoogleMap) {
        findViewById(R.id.fab).setOnClickListener { zoomToNearestMarker(map.myLocation) }
        this.map = map

        clusterManager = ClusterManager(this, map)
        clusterManager?.setRenderer(BusRenderer(this, map, clusterManager))

        map.setOnCameraChangeListener(clusterManager)
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
    }

    fun zoomToNearestMarker(location: Location?) {
        map?.let { map ->
            location?.let { location ->
                markers.sortBy { marker ->
                    val routeLocation = Location(LocationManager.NETWORK_PROVIDER)
                    routeLocation.longitude = marker.position!!.longitude
                    routeLocation.latitude = marker.position!!.latitude

                    location.distanceTo(routeLocation)
                }

                val bounds = LatLngBounds.Builder()
                bounds.include(LatLng(location.latitude, location.longitude))
                bounds.include(markers[0].position)

                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
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
        routeMap?.let {
            menuInflater.inflate(R.menu.main, menu)
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        if (item?.itemId == R.id.menu_filter) {
            FilterDialogFragment().show(supportFragmentManager, "filter")
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        timer.cancel()

        if (!subscription.isUnsubscribed) {
            subscription.unsubscribe()
        }
    }

    class BusItem(val bus: Bus) : ClusterItem {

        override fun getPosition(): LatLng? {
            return LatLng(bus.lat, bus.lng)
        }

        fun getTitle(): String {
            return bus.code
        }

        fun getNumber(): String {
            return bus.plate;
        }

        fun getRoute(): String {
            return bus.route
        }
    }

    inner class BusRenderer(context: Context?, map: GoogleMap?, clusterManager: ClusterManager<BusItem>?) :
            DefaultClusterRenderer<BusItem>(context, map, clusterManager) {

        val markerIconGenerator = IconGenerator(context).apply {
            setStyle(IconGenerator.STYLE_RED)
            setColor(ActivityCompat.getColor(context, R.color.colorPrimary))
        }

        override fun onBeforeClusterItemRendered(item: BusItem?, markerOptions: MarkerOptions?) {
            super.onBeforeClusterItemRendered(item, markerOptions)

            val bitmapWithBusRoute = markerIconGenerator.makeIcon(item?.getTitle())
            val descriptorWithBusRoute = BitmapDescriptorFactory.fromBitmap(bitmapWithBusRoute)

            markerOptions?.icon(descriptorWithBusRoute)?.title(item?.getNumber())?.snippet(item?.getRoute())
        }
    }

    inner class DataUpdateTimerTask : TimerTask() {
        override fun run() {
            downloadAndShowRoutes()
        }
    }

    inner class FilterDialogFragment : AppCompatDialogFragment() {
        val titles = ArrayList<CharSequence>()
        val selected = ArrayList<Boolean>()

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            for (title in routeMap?.keys!!) {
                titles.add(title)
                selected.add(true)
            }

            for (i in 0..titles.size - 1) {
                selected[i] = selectedRoutes.contains(titles[i])
            }

            val builder = AlertDialog.Builder(activity, R.style.AlertDialog)

            builder.setMultiChoiceItems(titles.toTypedArray(), selected.toBooleanArray()) {
                dialogInterface, position, value ->
                selected[position] = value

                val action = titles.size == selectedFilterCount()
                (dialog as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setText(if (action) R.string.deselect_all else android.R.string.selectAll)
            }.setPositiveButton(android.R.string.ok) {
                dialogInterface: DialogInterface, i: Int ->

                save()
                loadSelectedRoutes()
                downloadAndShowRoutes()

            }.setNegativeButton(android.R.string.cancel) {
                dialogInterface: DialogInterface, i: Int ->

            }

            return builder.create()
        }

        fun selectedFilterCount(): Int {
            var count = 0

            for (i in 0..selected.size - 1)
                if (selected[i]) count++

            return count
        }

        fun save() {
            val array = JSONArray()

            for (i in 0..selected.size - 1) {
                if (selected[i])
                    array.put(titles[i])
            }

            preferences.edit()?.putString("filter", array.toString())?.commit()
        }
    }
}
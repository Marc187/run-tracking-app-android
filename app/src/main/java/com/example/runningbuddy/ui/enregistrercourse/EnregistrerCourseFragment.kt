package com.example.runningbuddy.ui.enregistrercourse

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.example.runningbuddy.Constants.ACTION_PAUSE_SERVICE
import com.example.runningbuddy.Constants.ACTION_START_OR_RESUME_SERVICE
import com.example.runningbuddy.Constants.ACTION_STOP_SERVICE
import com.example.runningbuddy.Constants.MAP_ZOOM
import com.example.runningbuddy.Constants.POLYLINE_COLOR
import com.example.runningbuddy.Constants.POLYLINE_WIDTH
import com.example.runningbuddy.Constants.REQUEST_CODE_LOCATION_PERMISSION
import com.example.runningbuddy.MainActivity.Companion.userId
import com.example.runningbuddy.R
import com.example.runningbuddy.TrackingUtility
import com.example.runningbuddy.models.RunPost
import com.example.runningbuddy.services.Polyline
import com.example.runningbuddy.services.TrackingService
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.android.synthetic.main.fragment_enregistrer_course.*
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import java.util.*
import kotlin.math.round

class EnregistrerCourseFragment : Fragment(), EasyPermissions.PermissionCallbacks {
    private lateinit var enregistrerCourseViewModel: EnregistrerCourseViewModel
    private lateinit var mapView: MapView

    private var isTracking = false
    private var pathPoints = mutableListOf<Polyline>()

    //Create an object google and then we see this object with mapview
    private var map: GoogleMap? = null

    private var curTimeMillis = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_enregistrer_course, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requestPermissions()
        enregistrerCourseViewModel =
            ViewModelProvider(this).get(EnregistrerCourseViewModel::class.java)

        mapView = requireView().findViewById(R.id.mapView)
        btnStartRun.setOnClickListener {
            toggleRun()
        }

        btnFinishRun.setOnClickListener {
            zoomToSeeWholeTrack()
            endRunAndSaveToDb()
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync {
            map = it
            addAllPolylines()
            moveCameraToUser()
        }

        subscribeToObservers()
    }

    // function to check if tracking then update le button pour start ou stop
    private fun updateTracking(isTracking: Boolean) {
        this.isTracking = isTracking
        if(!isTracking) {
            btnStartRun.text = "Start"
            btnFinishRun.visibility = View.VISIBLE
        } else {
            btnStartRun.text = "Stop"
            btnFinishRun.visibility = View.GONE
        }
    }


    private fun subscribeToObservers() {
        // function permettant de d'observer
        // le state de isTracking et d'update le tracking en fonction
        TrackingService.isTracking.observe(viewLifecycleOwner, Observer {
            updateTracking(it)
        })

        // observe aussi les pathpoints pour les rajouter et
        // bouger la camera en fonction de la postion
        TrackingService.pathPoints.observe(viewLifecycleOwner, Observer  {
            pathPoints = it
            addAllPolylines()
            moveCameraToUser()
        })

        // observe le temps pour pouvoir la formet en fonction de si l'utilisateur cours
        TrackingService.timeRunInMillis.observe(viewLifecycleOwner, Observer {
            curTimeMillis = it
            val formattedTime = TrackingUtility.getFormattedStopWatchTime(curTimeMillis, true)
            tvtimer.text = formattedTime
        })
    }



    // function pour vérifier si isTracking est égal à true si oui pause le service
    // sinon commence le
    private fun toggleRun() {
        if(isTracking) {
            sendCommandToService(ACTION_PAUSE_SERVICE)
        } else {
            sendCommandToService(ACTION_START_OR_RESUME_SERVICE)
        }
    }



    private fun stopRun() {
        sendCommandToService(ACTION_STOP_SERVICE)
    }



    // si 'utilisateur est situer sur la map bouger la camera sur lui (on sait qu'il est sur la map
    // si les pathpoints sont pas vide)
    private fun moveCameraToUser() {
        if(pathPoints.isNotEmpty() && pathPoints.last().isNotEmpty()) {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    pathPoints.last().last(),
                    MAP_ZOOM
                )
            )
        }
    }



    private fun zoomToSeeWholeTrack() {
        val bounds = LatLngBounds.Builder()
        for(polyline in pathPoints) {
            for(pos in polyline) {
                bounds.include(pos)
            }
        }

        map?.moveCamera(
            CameraUpdateFactory.newLatLngBounds(
                bounds.build(),
                mapView.width,
                mapView.height,
                (mapView.height * 0.05f).toInt()
            )
        )
    }


    private fun endRunAndSaveToDb() {
        map?.snapshot { bmp ->
            var distanceInMeters = 0
            for (polyline in pathPoints) {
                distanceInMeters += TrackingUtility.calculatePolylineLength(polyline).toInt()
            }
            val avgSpeed = round((distanceInMeters / 1000f) / (curTimeMillis / 1000f / 60 / 60) *10) / 10f
            val dateTimeStamp = Calendar.getInstance().timeInMillis
            val caloriesBurned = ((distanceInMeters / 1000f) * 80f).toInt()
            val runPost = RunPost(userId, bmp, dateTimeStamp, avgSpeed, distanceInMeters, curTimeMillis, caloriesBurned)
            enregistrerCourseViewModel.insertRun(runPost)
            stopRun()
        }
    }

    // Add all polyline when the device is rotated
    private fun addAllPolylines() {
        for(polyline in pathPoints) {
            val polylineOptions = PolylineOptions()
                .color(POLYLINE_COLOR)
                .width(POLYLINE_WIDTH)
                .addAll(polyline)
            map?.addPolyline(polylineOptions)
        }
    }

    // rajoute le dernier polyline
    private fun addLatestPolyline() {
        if(pathPoints.isEmpty() && pathPoints.last().size > 1) {
            val preLastLatLng = pathPoints.last()[pathPoints.last().size - 2]
            val lastLatLng = pathPoints.last().last()
            val polylineOptions = PolylineOptions()
                .color(POLYLINE_COLOR)
                .width(POLYLINE_WIDTH)
                .add(preLastLatLng)
                .add(lastLatLng)
            map?.addPolyline(polylineOptions)
        }
    }

    // petite fonction permettant d'envoyer une action au service
    private fun sendCommandToService(action: String) =
        //it == intent
        Intent(requireContext(), TrackingService::class.java).also {
            it.action = action
            requireContext().startService(it)
        }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    // demande les permisson en fonction de la réponse de la fonction hasLocationPermissions
    // de TrackingUtility
    private fun requestPermissions() {
        if(TrackingUtility.hasLocationPermissions(requireContext())) {
            return
        }
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            EasyPermissions.requestPermissions(
                this,
                "You need to accept the location permission to use the app",
                REQUEST_CODE_LOCATION_PERMISSION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            EasyPermissions.requestPermissions(
                this,
                "You need to accept the location permission to use the app",
                REQUEST_CODE_LOCATION_PERMISSION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        }
    }


    // override la fonction permissionGranted puisqu'on utilise EasyPErmission et pas les permission
    // de base
    override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        // if il a denied pour tjr le forcer a accepter haha
        if(EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            AppSettingsDialog.Builder(this).build().show()
        } else {
            requestPermissions()
        }
    }

    override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {}


    // TODO : modifer les trucs deprecated
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }
}
package com.example.parkingapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.parkingapp.databinding.ActivityMainBinding
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var carMarker: Marker? = null
    private var lastUserLocation: GeoPoint? = null
    private var savedCarParking: SavedParking? = null

    private val noticeHandler = Handler(Looper.getMainLooper())
    private var noticeRunnable: Runnable? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            Log.d("ParkingApp", "Location permission granted via launcher")
            enableLocationFeatures()
        } else {
            handlePermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep light theme only
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // osmdroid setup before inflating layout
        Configuration.getInstance().osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        Configuration.getInstance().load(applicationContext, androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext))
        Configuration.getInstance().userAgentValue = "ParkingApp-StudentLab/1.0 (cresteljeandonal@gmail.com)"

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply status bar window insets to cardInfo
        ViewCompat.setOnApplyWindowInsetsListener(binding.cardInfo) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val layoutParams = v.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = systemBars.top + 12
            v.layoutParams = layoutParams
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupMap()
        checkLocationPermission()

        binding.btnParkedHere.setOnClickListener {
            onParkedHereClicked()
        }

        binding.btnRecenter.setOnClickListener {
            fetchCurrentLocation(false) { geoPoint ->
                binding.mapView.controller.setZoom(17.0)
                binding.mapView.controller.animateTo(geoPoint)
            }
        }

        binding.btnFindCar.setOnClickListener {
            findMyCar()
        }

        updateInfoCard()
    }

    private fun setupMap() {
        binding.mapView.setTileSource(MapTilerTileSource())
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.isTilesScaledToDpi = true
        binding.mapView.controller.setZoom(15.0)
        binding.mapView.controller.setCenter(GeoPoint(10.3157, 123.8854)) // Cebu City
        binding.mapView.setBuiltInZoomControls(false)
        binding.mapView.tileProvider.clearTileCache()

        // Restore saved car location ONCE at startup
        savedCarParking = restoreParking()
        if (savedCarParking != null) {
            Log.d("ParkingApp", "Restored car location at startup: lat=${savedCarParking!!.geoPoint.latitude}, lon=${savedCarParking!!.geoPoint.longitude}")
            addOrUpdateCarMarker(savedCarParking!!.geoPoint)
            binding.mapView.controller.setZoom(17.0)
            binding.mapView.controller.setCenter(savedCarParking!!.geoPoint)
        } else {
            Log.d("ParkingApp", "No restored car location found at startup")
        }
    }

    private fun checkLocationPermission() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            Log.d("ParkingApp", "Location permission already granted")
            enableLocationFeatures()
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AlertDialog.Builder(this)
                .setTitle("Location Permission Needed")
                .setMessage("The app uses your location to show where you are and to save where you parked.")
                .setPositiveButton("Allow") { _, _ ->
                    requestPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
                .setNegativeButton("Cancel") { _, _ ->
                    handlePermissionDenied()
                }
                .show()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun enableLocationFeatures() {
        binding.btnParkedHere.isEnabled = true
        if (myLocationOverlay == null) {
            val blueDot = createBlueDotBitmap()
            myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView).apply {
                enableMyLocation()
                enableFollowLocation()
                setPersonIcon(blueDot)
                setDirectionIcon(blueDot)
                runOnFirstFix {
                    runOnUiThread {
                        if (myLocationOverlay?.myLocation != null) {
                            lastUserLocation = GeoPoint(myLocationOverlay!!.myLocation.latitude, myLocationOverlay!!.myLocation.longitude)
                            updateInfoCard()
                        }
                    }
                }
            }
            binding.mapView.overlays.add(myLocationOverlay)
        } else {
            myLocationOverlay?.enableMyLocation()
        }

        fetchCurrentLocation(false)
    }

    private fun createBlueDotBitmap(): Bitmap {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = true }
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.parseColor("#1976D2")
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 4f, paint)
        return bitmap
    }

    @SuppressLint("MissingPermission")
    private fun onParkedHereClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showFloatingNotice("Location permission required", false)
            return
        }

        binding.btnParkedHere.isEnabled = false
        showFloatingNotice("Getting your location...", false)

        val cancellationTokenSource = CancellationTokenSource()
        val currentLocationRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(15000L)
            .build()

        fusedLocationClient.getCurrentLocation(currentLocationRequest, cancellationTokenSource.token)
            .addOnSuccessListener { location ->
                binding.btnParkedHere.isEnabled = true
                if (location != null) {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    lastUserLocation = geoPoint
                    Log.d("ParkingApp", "Fresh location received for parking: lat=${location.latitude}, lon=${location.longitude}")
                    saveAndPlaceCar(geoPoint)
                } else {
                    val lastFix = myLocationOverlay?.lastFix
                    if (lastFix != null && (System.currentTimeMillis() - lastFix.time < 60000)) {
                        val geoPoint = GeoPoint(lastFix.latitude, lastFix.longitude)
                        lastUserLocation = geoPoint
                        Log.d("ParkingApp", "Fallback to overlay location: lat=${geoPoint.latitude}, lon=${geoPoint.longitude}")
                        saveAndPlaceCar(geoPoint)
                    } else {
                        Log.d("ParkingApp", "Failed to get fresh location")
                        showFloatingNotice("Unable to get current location", false)
                    }
                }
            }
            .addOnFailureListener { e ->
                binding.btnParkedHere.isEnabled = true
                Log.d("ParkingApp", "getCurrentLocation failed: ${e.message}")
                val lastFix = myLocationOverlay?.lastFix
                if (lastFix != null && (System.currentTimeMillis() - lastFix.time < 60000)) {
                    val geoPoint = GeoPoint(lastFix.latitude, lastFix.longitude)
                    lastUserLocation = geoPoint
                    saveAndPlaceCar(geoPoint)
                } else {
                    showFloatingNotice("Unable to get current location", false)
                }
            }
    }

    private fun saveAndPlaceCar(geoPoint: GeoPoint) {
        savedCarParking = SavedParking(geoPoint, System.currentTimeMillis())
        saveLocation(geoPoint)
        addOrUpdateCarMarker(geoPoint)
        binding.mapView.controller.animateTo(geoPoint)
        updateInfoCard()
        showFloatingNotice("Parking spot saved", true)
    }

    private fun fetchCurrentLocation(isBackground: Boolean, onLocationReceived: ((GeoPoint) -> Unit)? = null) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val locationManager = getSystemService(LocationManager::class.java)
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                           locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!isGpsEnabled) {
            if (!isBackground) {
                showFloatingNotice("Turn on location", false)
            }
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                lastUserLocation = geoPoint
                updateInfoCard()
                Log.d("ParkingApp", "Location received via lastLocation: lat=${location.latitude}, lon=${location.longitude}")
                onLocationReceived?.invoke(geoPoint)
            } else {
                val cancellationTokenSource = CancellationTokenSource()
                val currentLocationRequest = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setDurationMillis(15000L)
                    .build()
                fusedLocationClient.getCurrentLocation(currentLocationRequest, cancellationTokenSource.token)
                    .addOnSuccessListener { currLocation ->
                        if (currLocation != null) {
                            val geoPoint = GeoPoint(currLocation.latitude, currLocation.longitude)
                            lastUserLocation = geoPoint
                            updateInfoCard()
                            Log.d("ParkingApp", "Location received via getCurrentLocation: lat=${currLocation.latitude}, lon=${currLocation.longitude}")
                            onLocationReceived?.invoke(geoPoint)
                        }
                    }
            }
        }
    }

    private fun findMyCar() {
        val parking = savedCarParking
        if (parking == null) {
            showFloatingNotice("No parking spot saved yet", false)
            return
        }
        val carPoint = parking.geoPoint
        val userPoint = lastUserLocation ?: myLocationOverlay?.myLocation?.let { GeoPoint(it.latitude, it.longitude) }

        if (userPoint != null) {
            val bbox = BoundingBox.fromGeoPoints(listOf(carPoint, userPoint))
            binding.mapView.zoomToBoundingBox(bbox, true, 100)
        } else {
            binding.mapView.controller.setZoom(17.0)
            binding.mapView.controller.animateTo(carPoint)
        }
    }

    private fun showFloatingNotice(message: String, isSuccess: Boolean) {
        noticeRunnable?.let { noticeHandler.removeCallbacks(it) }

        binding.tvNoticeText.text = message
        if (isSuccess) {
            binding.ivNoticeIcon.setImageResource(R.drawable.ic_check_circle)
        } else {
            binding.ivNoticeIcon.setImageResource(R.drawable.ic_info_blue)
        }

        binding.cardNotice.visibility = View.VISIBLE
        binding.cardNotice.alpha = 0f
        binding.cardNotice.translationY = -40f
        binding.cardNotice.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .start()

        noticeRunnable = Runnable {
            binding.cardNotice.animate()
                .alpha(0f)
                .translationY(-40f)
                .setDuration(250)
                .withEndAction {
                    binding.cardNotice.visibility = View.GONE
                }
                .start()
        }
        noticeHandler.postDelayed(noticeRunnable!!, 2500)
    }

    private fun handlePermissionDenied() {
        binding.btnParkedHere.isEnabled = false
        showFloatingNotice("Location permission required in settings", false)
    }

    private fun addOrUpdateCarMarker(geoPoint: GeoPoint) {
        if (carMarker == null) {
            carMarker = Marker(binding.mapView).apply {
                title = "Your car"
                setAnchor(0.5f, 1.0f)
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_car_pin)
                isDraggable = true
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker) {}
                    override fun onMarkerDragStart(marker: Marker) {}
                    override fun onMarkerDragEnd(marker: Marker) {
                        val newGeoPoint = marker.position
                        savedCarParking = SavedParking(newGeoPoint, System.currentTimeMillis())
                        saveLocation(newGeoPoint)
                        updateInfoCard()
                        Log.d("ParkingApp", "Car dragged and saved: lat=${newGeoPoint.latitude}, lon=${newGeoPoint.longitude}")
                        showFloatingNotice("Parking spot updated", true)
                    }
                })
            }
            binding.mapView.overlays.add(carMarker)
        }
        carMarker?.position = geoPoint
        binding.mapView.invalidate()
        updateInfoCard()
    }

    private data class SavedParking(val geoPoint: GeoPoint, val timestamp: Long?)

    private fun saveLocation(latLng: GeoPoint) {
        val currentTime = System.currentTimeMillis()
        getPreferences(MODE_PRIVATE)?.edit()?.apply {
            putString("latitude", latLng.latitude.toString())
            putString("longitude", latLng.longitude.toString())
            putLong("timestamp", currentTime)
            apply()
        }
        Log.d("ParkingApp", "Coordinates saved: lat=${latLng.latitude}, lon=${latLng.longitude}, time=$currentTime")
    }

    private fun restoreParking(): SavedParking? {
        val prefs = getPreferences(MODE_PRIVATE) ?: return null
        val latStr = prefs.getString("latitude", null) ?: return null
        val lonStr = prefs.getString("longitude", null) ?: return null
        val lat = latStr.toDoubleOrNull() ?: return null
        val lon = lonStr.toDoubleOrNull() ?: return null
        val timestamp = if (prefs.contains("timestamp")) prefs.getLong("timestamp", 0L) else null
        Log.d("ParkingApp", "Coordinates restored: lat=$lat, lon=$lon, time=$timestamp")
        return SavedParking(GeoPoint(lat, lon), timestamp)
    }

    private fun updateInfoCard() {
        val savedParking = savedCarParking
        if (savedParking == null) {
            binding.tvInfo.text = "No parking spot saved yet"
        } else {
            val carPoint = savedParking.geoPoint
            val timeStr = if (savedParking.timestamp != null && savedParking.timestamp > 0L) {
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(savedParking.timestamp))
            } else {
                "Unknown time"
            }

            if (lastUserLocation != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    lastUserLocation!!.latitude, lastUserLocation!!.longitude,
                    carPoint.latitude, carPoint.longitude,
                    results
                )
                val distMeters = results[0]
                val distStr = if (distMeters >= 1000f) {
                    String.format(Locale.getDefault(), "%.1f km", distMeters / 1000f)
                } else {
                    "${distMeters.toInt()} m"
                }
                binding.tvInfo.text = "Car parked $distStr away · Saved $timeStr"
            } else {
                binding.tvInfo.text = "Car parked · Saved $timeStr"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        myLocationOverlay?.enableMyLocation()
        updateInfoCard()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        myLocationOverlay?.disableMyLocation()
    }
}

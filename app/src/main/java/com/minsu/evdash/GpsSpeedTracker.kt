package com.minsu.evdash

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

/**
 * 폰 자체 GPS로 속도(km/h)를 받아오는 트래커.
 * LocationManager를 직접 써서 별도 라이브러리(FusedLocation) 의존성 없이 동작합니다.
 */
class GpsSpeedTracker(
    private val context: Context,
    private val onSpeedUpdate: (speedKmh: Float) -> Unit
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // location.speed 단위: m/s -> km/h 변환
            val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            onSpeedUpdate(speedKmh)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,   // 0.5초마다 갱신
                0.5f,   // 0.5m 이동마다 갱신
                listener
            )
        } catch (e: SecurityException) {
            // 권한 없음 - MainActivity에서 권한 요청 처리
        }
    }

    fun stop() {
        locationManager.removeUpdates(listener)
    }
}

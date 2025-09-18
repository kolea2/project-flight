/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.sspsdk

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import android.view.MotionEvent
import androidx.privacysandbox.ads.adservices.adselection.AdSelectionConfig
import androidx.privacysandbox.ads.adservices.adselection.AdSelectionManager
import androidx.privacysandbox.ads.adservices.adselection.AdSelectionOutcome
import androidx.privacysandbox.ads.adservices.adselection.ReportEventRequest
import androidx.privacysandbox.ads.adservices.adselection.ReportEventRequest.Companion.FLAG_REPORTING_DESTINATION_BUYER
import androidx.privacysandbox.ads.adservices.adselection.ReportEventRequest.Companion.FLAG_REPORTING_DESTINATION_SELLER
import androidx.privacysandbox.ads.adservices.adselection.ReportImpressionRequest
import androidx.privacysandbox.ads.adservices.common.AdSelectionSignals
import androidx.privacysandbox.ads.adservices.common.AdTechIdentifier
import androidx.privacysandbox.ads.adservices.measurement.MeasurementManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.lang.IllegalStateException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.stream.Collectors

@Suppress("NewApi")
class SspSdkImpl constructor(
    context: Context
) {
    /*
     * SHARED
     */
    private val logTag = "Flight SSP"
    private val topLevelDomain = "example.com"

    /*
     * PROTECTED AUDIENCES
     */
    private val adSelectionManager: AdSelectionManager
    private val adSelectionConfig: AdSelectionConfig

    // Use your own scoring logic and scoring trusted URLs
    private val scoringLogicUri =
        Uri.parse("https://$topLevelDomain/protected-audience/Logic/ScoringLogic.js")
    private val scoringTrustedUri =
        Uri.parse("https://$topLevelDomain/protected-audience/Functions/ScoringTrusted.js")

    // TODO: Replace with URI of buyer (can also pull this list from server)
    private val buyers = listOf(AdTechIdentifier(topLevelDomain))

    /*
     * ATTRIBUTION REPORTING API
     */
    private val measurementManager: MeasurementManager

    // Use your own register source URL
    private val registerSourceUrl = "https://$topLevelDomain/attribution/source"
    private val registerSourceIdentifier = "?attribution_id="

    init {
        // Check for Privacy Sandbox version
        if (!canUsePrivacySandbox(context)) {
            // If the version is too low, can not initialize the customAudienceManager
            // and measurementManager
            throw IllegalStateException("Can not use Privacy Sandbox, version too low")
        }

        adSelectionManager = context.getSystemService(
            AdSelectionManager::class.java
        )

        measurementManager = context.getSystemService(
            MeasurementManager::class.java
        )

        adSelectionConfig = AdSelectionConfig(
            AdTechIdentifier(topLevelDomain),
            scoringLogicUri,
            buyers,
            AdSelectionSignals(""),
            AdSelectionSignals(""),
            buyers.stream().collect(
                Collectors.toMap(
                    { buyer: AdTechIdentifier -> buyer },
                    { AdSelectionSignals("") }
                )).toMap(),
            scoringTrustedUri)
    }

    /*
    * Returns whether the device and AdServices Extension and Google Play Services versions
    * are at the minimum level or higher to use the Privacy Sandbox.
    */
    fun canUsePrivacySandbox(context: Context): Boolean {
        // Only needed for Beta releases
        val isCorrectBuildVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val isCorrectSdkExtensionVersion =
            SdkExtensions.getExtensionVersion(SdkExtensions.AD_SERVICES) >= 4

        // Needed for both Developer Preview and Beta releases
        val isGooglePlayServicesAvailable = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        return isCorrectBuildVersion && isCorrectSdkExtensionVersion && isGooglePlayServicesAvailable
    }

    suspend fun runAdSelection(): AdSelectionOutcome {
        try {
            return adSelectionManager.selectAds(adSelectionConfig)
        } catch (e: IllegalStateException) {
            Log.e(logTag, "Error when running ad selection", e)
        }
    }

    suspend fun reportImpression(adSelectionId: Long) {
        val reportImpressionRequest = ReportImpressionRequest(adSelectionId, adSelectionConfig)
        adSelectionManager.reportImpression(reportImpressionRequest)
    }

    suspend fun reportEvent(adSelectionId: Long, key: String, data: String) {
        val executor: Executor = Executors.newCachedThreadPool()
        val reportingDestinations =
            FLAG_REPORTING_DESTINATION_BUYER or FLAG_REPORTING_DESTINATION_SELLER
        val reportEventRequest = ReportEventRequest(
            adSelectionId,
            key,
            data,
            reportingDestinations
        )

        adSelectionManager.reportEvent(reportEventRequest)
    }

    /*
     * Registers a source.
     */
    suspend fun registerSource(identifier: String, inputEvent: MotionEvent) {
        var registerSourceUri = Uri.parse("$registerSourceUrl$registerSourceIdentifier$identifier")
        if (inputEvent.action == MotionEvent.ACTION_DOWN) {
            registerSourceUri =
                registerSourceUri.buildUpon().appendQueryParameter("type", "click").build()
        }

        Log.d(logTag, "registerSource called")
        Log.d(logTag, "registerSource URL is = $registerSourceUri")

        try {
            measurementManager.registerSource(registerSourceUri, inputEvent)
        } catch (e: Exception) {
            Log.e(logTag, "registerSource failed", e)
        }
    }
}

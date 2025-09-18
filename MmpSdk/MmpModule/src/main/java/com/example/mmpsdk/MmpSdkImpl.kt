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

package com.example.mmpsdk

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.privacysandbox.ads.adservices.common.AdData
import androidx.privacysandbox.ads.adservices.common.AdSelectionSignals
import androidx.privacysandbox.ads.adservices.common.AdTechIdentifier
import androidx.privacysandbox.ads.adservices.customaudience.CustomAudience
import androidx.privacysandbox.ads.adservices.customaudience.CustomAudienceManager
import androidx.privacysandbox.ads.adservices.customaudience.JoinCustomAudienceRequest
import androidx.privacysandbox.ads.adservices.customaudience.TrustedBiddingData
import androidx.privacysandbox.ads.adservices.measurement.MeasurementManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import androidx.core.net.toUri

/*
 * Implementation of an MMP SDK. This SDK will join custom audiences on behalf of a DSP.
 */
@SuppressLint("NewApi")
class MmpSdkImpl constructor(
    context: Context
) {
    /*
     * SHARED
     */
    private val logTag = "Flight MMP"
    private val topLevelDomain = "example.com"

    /*
     * PROTECTED AUDIENCES
     */
    private val customAudienceManager: CustomAudienceManager
    private val locationsWithAds = listOf("athens", "berlin", "cairo")

    // Use your own bidding logic, bidding daily, and bidding trusted URLs
    private val biddingLogicUri =
        Uri.parse("https://$topLevelDomain/protected-audience/Logic/BiddingLogic.js")
    private val biddingDailyUri =
        Uri.parse("https://$topLevelDomain/protected-audience/Functions/BiddingDaily.html")
    private val biddingTrustedUri =
        Uri.parse("https://$topLevelDomain/protected-audience/Functions/BiddingTrusted.js")

    /*
     * ATTRIBUTION REPORTING
     */
    private val measurementManager: MeasurementManager

    // Use your own register trigger URL
    private val registerTriggerUrl = "https://$topLevelDomain/attribution/trigger"
    private val registerTriggerIdentifier = "?attribution_id="

    init {
        // Check for Privacy Sandbox version
        if (!canUsePrivacySandbox(context)) {
            // If the version is too low, can not initialize the customAudienceManager
            // and measurementManager
            throw IllegalStateException("Can not use Privacy Sandbox, version too low")
        }

        // initialize the customAudienceManager from the context
        customAudienceManager = CustomAudienceManager.obtain(context)!!

        measurementManager = context.getSystemService(
            MeasurementManager::class.java
        )
    }

    /*
    * Returns whether the device, AdServices Extension versions, and Google Play Services
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


    suspend fun joinCustomAudience(customAudienceName: String) {
        val customAudience = CustomAudience(
            buyer = AdTechIdentifier(topLevelDomain),
            name = customAudienceName,
            dailyUpdateUri = biddingDailyUri,
            biddingLogicUri = biddingLogicUri,
            activationTime = Instant.now(),
            ads = listOf(
                AdData(
                    getRenderUriForAudience(customAudienceName),
                    metadata = JSONObject().toString()
                )
            ),
            expirationTime = Instant.now().plus(Duration.ofDays(7)),
            userBiddingSignals = AdSelectionSignals("{}"),
            trustedBiddingSignals = TrustedBiddingData(
                biddingTrustedUri,
                listOf("\"valid_signals\": true")
            )
        )
        try {
            customAudienceManager.joinCustomAudience(JoinCustomAudienceRequest(customAudience))
            Log.i(logTag, "Successfully joined custom audience: $customAudienceName")
        } catch (e: Exception) {
            Log.e(logTag, "joinCustomAudience exception: ", e)
        }
    }

    // Lazily compute the render URI for an audience. We have a limited
    // number of specific "ads", so will fall back to generic ad if the destination
    // does not have a specific ad.
    private fun getRenderUriForAudience(name: String): Uri {
        var renderParam = name
        if (!locationsWithAds.contains(name)) {
            renderParam = "generic"
        }
        return Uri.parse("https://$topLevelDomain/render/${renderParam}.jpg")
    }

    /*
     * Registers a trigger.
     */
    suspend fun registerTrigger(identifier: String) {
        val registerTriggerUri =
            "$registerTriggerUrl$registerTriggerIdentifier$identifier".toUri()

        Log.d(logTag, "registerTrigger called")
        Log.d(logTag, "registerTrigger URL is = $registerTriggerUri")

        try {
            measurementManager.run {
                registerTrigger(
                    trigger = registerTriggerUri
                )
            }
        } catch (e: Exception) {
            Log.e(logTag, "error running register trigger")
        }
    }
}
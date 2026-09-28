package com.angussoftware.letta.env

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * HCRequestActivity — launches the Health Connect permission REQUEST contract
 * (the only thing that makes the app appear in HC's "Your health apps" list
 * and get per-type grants). A plain android.app.Activity cannot host the
 * activity-result API; this ComponentActivity exists solely to run the
 * contract and finish.
 *
 * Started from the Telemetry card's Health Grant button (and safe to re-run:
 * already-granted types are pre-approved, the rest show toggles).
 * Finishes immediately after the result lands so the UI returns to MainActivity.
 */
class HCRequestActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { /* grants applied — nothing to do; finish below */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launch the request DIRECTLY — no pre-flight getGrantedPermissions:
        // that is a binder round-trip and runBlocking'ing it on main ANRed
        // the activity (Sep 28, live). The contract handles already-granted
        // types gracefully (they simply don't re-prompt).
        requestPermissions.launch(setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
        ))
    }

}

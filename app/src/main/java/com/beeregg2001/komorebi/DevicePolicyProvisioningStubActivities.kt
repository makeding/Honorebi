package com.beeregg2001.komorebi

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Honorebi uses the Device Policy Management role only for its HOME policy.
 * It deliberately refuses Android Enterprise provisioning delegation.
 */
abstract class DevicePolicyProvisioningStubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w(TAG, "Rejecting unsupported provisioning action: ${intent?.action}")
        setResult(RESULT_CANCELED)
        finish()
    }

    private companion object {
        const val TAG = "HonorebiDevicePolicy"
    }
}

class TrustedSourceProvisioningStubActivity : DevicePolicyProvisioningStubActivity()

class ManagedProfileProvisioningStubActivity : DevicePolicyProvisioningStubActivity()

class ProvisioningFinalizationStubActivity : DevicePolicyProvisioningStubActivity()

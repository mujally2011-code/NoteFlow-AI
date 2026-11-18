package org.noteflow.app.domain

enum class ProcessingMode { OFFLINE_ONLY, CLOUD_ON_DEMAND, AUTO_WITH_CONFIRMATION }
fun mayUpload(mode: ProcessingMode, explicitConsent: Boolean): Boolean =
    explicitConsent && mode != ProcessingMode.OFFLINE_ONLY

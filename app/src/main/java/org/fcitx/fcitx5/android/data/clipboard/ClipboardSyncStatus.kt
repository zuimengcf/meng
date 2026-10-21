package org.fcitx.fcitx5.android.data.clipboard

/**
 * Sync state exposed to advanced UI. The current schema has no peer-receipt column,
 * therefore this API deliberately never presents lastModifiedBy as a receiving peer.
 */
enum class ClipboardSyncState {
    /** Captured locally and no exposure has been recorded. */
    LOCAL_ONLY,
    /** Bytes may have been exposed, but no authenticated durable receipt is known. */
    EXPOSURE_PENDING,
    /** A durable authenticated receipt exists for this exact current version. */
    RECEIPT_CONFIRMED,
    /** Shared/legacy-protected row; delivery is not attributable to a peer. */
    SHARED_OR_LEGACY_UNKNOWN,
}

data class ClipboardSyncStatus(
    val state: ClipboardSyncState,
    /** True only with authenticated evidence that a peer holds this exact version. */
    val peerReceiptConfirmed: Boolean = false,
    /** Comma-separated confirmed peer IDs, never inferred from lastModifiedBy. */
    val receiptDeviceId: String? = null,
    /** Source/last modifier, explicitly not a receiving-device confirmation. */
    val sourceOrLastModifiedDeviceId: String?,
) {
    val isReceivingPeerKnown: Boolean get() = peerReceiptConfirmed && receiptDeviceId != null
}

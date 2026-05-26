package app.syncler.core.network

/**
 * V3 #16 — host-side sink for incoming live card patch
 * envelopes. The LiveBridge (in :feature:plugin-host) routes
 * card.patch frames here so the InboxRepository (in
 * :feature:inbox) can decrypt, sequence-check, and apply the
 * replace ops on the InboxItem's payloadJson.
 *
 * The sink is decoupled across feature modules — wire the
 * concrete `InboxRepository::applyLivePatch` impl into
 * LiveBridge at the composition root.
 *
 * Spec: docs/live-card-patch.md "Device-side state".
 */
fun interface LivePatchSink {
    suspend fun acceptCardPatch(envelopeJson: String)

    companion object {
        /** Default for tests / wiring not yet hooked up. */
        val NoOp: LivePatchSink = LivePatchSink { /* discard */ }
    }
}

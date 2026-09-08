// Naming is intentional
@file:Suppress("FunctionName")

package at.hannibal2.skyhanni.mixins.hooks

interface ParticlePacketStore {
    fun `skyhanni$isEventPosted`(): Boolean = throw UnsupportedOperationException("Implemented via mixin")

    fun `skyhanni$setEventPosted`() {
        throw UnsupportedOperationException("Implemented via mixin")
    }

    fun `skyhanni$isCancelled`(): Boolean = throw UnsupportedOperationException("Implemented via mixin")

    fun `skyhanni$setCancelled`() {
        throw UnsupportedOperationException("Implemented via mixin")
    }
}

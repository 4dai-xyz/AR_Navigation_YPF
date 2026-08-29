package com.aiglasses.poc.glasses

interface GlassesSdkAdapter {
    fun name(): String
    fun status(): String
}

class NoOpGlassesSdkAdapter : GlassesSdkAdapter {
    override fun name(): String = "noop-heycyan-adapter"

    override fun status(): String = "mock-only"
}

package dev.southstar21.volum.platform

/** Implemented in Swift (iosApp's TabBarHost): the UITabBarController drawn over the Compose app. */
interface IosTabBarHost {
    fun render(state: SystemNavbarState)
    fun remove()
}

/** The iOS system tab bar as the shared shell sees it. State that arrives before the host attaches is replayed. */
object IosTabBar : SystemNavbar {
    private var host: IosTabBarHost? = null
    private var last: SystemNavbarState? = null

    override var onSelect: ((Int) -> Unit)? = null
    override var onAction: (() -> Unit)? = null

    fun attach(host: IosTabBarHost) {
        this.host = host
        last?.let(host::render)
    }

    override fun update(state: SystemNavbarState) {
        if (state == last) return
        last = state
        host?.render(state)
    }

    override fun detach() {
        last = null
        host?.remove()
    }

    /** A tab was tapped in the system bar. */
    fun select(index: Int) { onSelect?.invoke(index) }

    /** The round + was tapped. */
    fun runAction() { onAction?.invoke() }
}

actual val platformNavbar: SystemNavbar? = IosTabBar

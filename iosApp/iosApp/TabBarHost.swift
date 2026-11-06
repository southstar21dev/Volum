import Shared
import UIKit

/// The app's root: the shared Compose app full screen, with the system tab bar over it. The bar is a
/// real UITabBarController (Liquid Glass on iOS 26, with the separate round + beside it); its pages
/// are empty and transparent, so everything outside the bar is the Compose app.
final class RootViewController: UIViewController {
    private let compose = IosAppKt.MainViewController()
    private let tabs = TabBarHost()
    private let overlay = PassthroughView()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(named: "LaunchBackground")

        addChild(compose)
        compose.view.frame = view.bounds
        compose.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(compose.view)
        compose.didMove(toParent: self)

        overlay.frame = view.bounds
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        overlay.tabs = tabs
        view.addSubview(overlay)
        addChild(tabs)
        tabs.view.frame = overlay.bounds
        tabs.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        overlay.addSubview(tabs.view)
        tabs.didMove(toParent: self)

        IosTabBar.shared.attach(host: tabs)
    }

    // Compose owns the status bar and the system gestures.
    override var childForStatusBarStyle: UIViewController? { compose }
    override var childForStatusBarHidden: UIViewController? { compose }
    override var childForHomeIndicatorAutoHidden: UIViewController? { compose }
    override var childForScreenEdgesDeferringSystemGestures: UIViewController? { compose }
}

/// Lets touches through to the Compose app everywhere except on the tab bar itself.
private final class PassthroughView: UIView {
    weak var tabs: TabBarHost?

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let tabs, tabs.isInteractive else { return nil }
        let bar = tabs.tabBar
        guard bar.point(inside: convert(point, to: bar), with: event) else { return nil }
        return super.hitTest(point, with: event)
    }
}

/// An empty, see-through page: the content is the Compose app underneath.
private final class ClearPage: UIViewController {
    override func loadView() {
        view = UIView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
    }
}

final class TabBarHost: UITabBarController, UITabBarControllerDelegate, IosTabBarHost {
    // Same order as the shared Tab enum.
    private static let items: [(title: String, symbol: String)] = [
        ("Today", "house"),
        ("History", "calendar"),
        ("Lifts", "dumbbell"),
        ("Profile", "person.crop.circle"),
    ]

    private lazy var mainTabs: [UITab] = Self.items.enumerated().map { index, item in
        UITab(title: item.title, image: UIImage(systemName: item.symbol), identifier: "tab.\(index)") { _ in ClearPage() }
    }

    /// The + sits apart from the bar, in the slot iOS gives a search tab; it starts a new routine.
    private lazy var actionTab: UISearchTab = {
        let tab = UISearchTab { _ in ClearPage() }
        tab.title = "New routine"
        tab.image = UIImage(systemName: "plus")
        if #available(iOS 26.0, *) { tab.automaticallyActivatesSearch = false }
        return tab
    }()

    private var state: SystemNavbarState?
    private var hidden = true

    var isInteractive: Bool { !hidden && state != nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        delegate = self
        mode = .tabBar
        view.backgroundColor = .clear
        if #available(iOS 26.0, *) { tabBarMinimizeBehavior = .never }
        setTabs(mainTabs, animated: false)
        view.alpha = 0
    }

    // MARK: IosTabBarHost

    func render(state: SystemNavbarState) {
        loadViewIfNeeded()
        self.state = state
        let wanted = state.showAction ? mainTabs + [actionTab] : mainTabs
        if tabs.map(\.identifier) != wanted.map(\.identifier) {
            setTabs(wanted, animated: view.window != nil && view.alpha > 0)
        }
        let index = Int(state.selectedIndex)
        if mainTabs.indices.contains(index), selectedTab !== mainTabs[index] {
            selectedTab = mainTabs[index]
        }
        view.tintColor = Self.color(argb: state.accentArgb)
        view.overrideUserInterfaceStyle = .light
        setHidden(state.hidden)
    }

    func remove() {
        state = nil
        setHidden(true)
    }

    // MARK: UITabBarControllerDelegate

    func tabBarController(_ tabBarController: UITabBarController, shouldSelectTab tab: UITab) -> Bool {
        if tab === actionTab {
            IosTabBar.shared.runAction()
            return false
        }
        if let index = mainTabs.firstIndex(where: { $0 === tab }) {
            IosTabBar.shared.select(index: Int32(index))
        }
        return true
    }

    // MARK: Private

    private func setHidden(_ value: Bool) {
        guard value != hidden else { return }
        hidden = value
        UIView.animate(withDuration: value ? 0.16 : 0.22, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
            self.view.alpha = value ? 0 : 1
        }
    }

    private static func color(argb: Int32) -> UIColor {
        let v = UInt32(bitPattern: argb)
        return UIColor(
            red: CGFloat((v >> 16) & 0xFF) / 255,
            green: CGFloat((v >> 8) & 0xFF) / 255,
            blue: CGFloat(v & 0xFF) / 255,
            alpha: CGFloat((v >> 24) & 0xFF) / 255
        )
    }
}

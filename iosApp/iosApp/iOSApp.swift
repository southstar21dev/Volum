import Shared
import SwiftUI
import UIKit

/// The iOS shell. Everything the user sees is the shared Compose app (shared/), the same code
/// androidApp runs, except the tab bar, which is the system's Liquid Glass one (TabBarHost.swift).
@main
struct VolumIOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                .background(Color("LaunchBackground"))
        }
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        RootViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

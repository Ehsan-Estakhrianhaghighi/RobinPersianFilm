import SwiftUI
import AVKit
import WebKit

struct PlaybackView: View {
    let page: URL
    let provider: String
    @State private var streams: [Stream] = []
    @State private var selected: Stream?
    @State private var player = AVPlayer()
    @State private var isResolving = true
    @State private var message = "Finding the selected video…"
    @State private var fullscreen = false
    @State private var resumedPosition: CMTime = .zero
    @Environment(\.dismiss) private var dismiss

    private var isFarsiLand: Bool { provider == "FarsiLand" }
    private var isMovie: Bool { page.path.hasPrefix("/movies/") }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                if !streams.isEmpty {
                    Text("Quality").font(.headline)
                    HStack(spacing: 10) {
                        ForEach(streams.sorted(by: { (Int($0.quality) ?? 0) < (Int($1.quality) ?? 0) })) { stream in
                            Button {
                                choose(stream)
                            } label: {
                                Text("\(selected?.id == stream.id ? "✓ " : "")\(stream.quality)p")
                                    .frame(minWidth: 68)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(selected?.id == stream.id ? .orange : .red)
                        }
                    }
                    .padding(.horizontal, 4)
                }
                if isResolving { ProgressView(message).padding() }
                else if selected == nil {
                    Text(message).font(.caption).foregroundStyle(.secondary)
                }
                if selected != nil {
                    VideoPlayer(player: player)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    Button {
                        fullscreen = true
                    } label: { Label("Full screen", systemImage: "arrow.up.left.and.arrow.down.right") }
                    .buttonStyle(.bordered)
                } else {
                    // Fall back to the provider's legitimate browser controls.
                    ProviderWebPlayer(url: page) { media in
                        Task { await acceptObserved(media) }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .padding(12)
            .navigationTitle("RobinPersianFilm Player")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Back") { player.pause(); dismiss() }
                }
            }
            .task(id: page) { await resolve() }
            .fullScreenCover(isPresented: $fullscreen) {
                ZStack(alignment: .topTrailing) {
                    Color.black.ignoresSafeArea()
                    VideoPlayer(player: player).ignoresSafeArea()
                    Button("Done") { fullscreen = false }
                        .buttonStyle(.borderedProminent)
                        .padding()
                }
            }
            .onDisappear { player.pause() }
        }
    }

    private func choose(_ stream: Stream) {
        guard selected?.id != stream.id else { return }
        let position = player.currentTime()
        selected = stream
        player.replaceCurrentItem(with: AVPlayerItem(url: stream.url))
        if position.isValid, position.seconds > 0 {
            player.seek(to: position, toleranceBefore: .zero, toleranceAfter: .zero)
        }
        player.play()
    }

    private func resolve() async {
        isResolving = true
        if isFarsiLand {
            let found = await SiteService.shared.farsiLandStreams(for: page)
            streams = found
            if let preferred = found.first(where: { $0.quality == "720" }) ?? found.first {
                choose(preferred)
                message = "Playing FarsiLand video"
            } else {
                message = "Direct stream not found; use the provider's player"
            }
        } else {
            message = "Loading FarsiPlex web player and looking for video source…"
        }
        isResolving = false
    }

    private func acceptObserved(_ url: URL) async {
        guard selected == nil else { return }
        let host = url.host?.lowercased() ?? ""
        guard host == "pl.farsiplex.buzz" || host == "pl.farsiland.buzz" else { return }
        guard await SiteService.shared.verified(url) else { return }
        let expression = #"[.-](480|720|1080)(?=(?:\.[^./]+)*\.mp4$)"#
        let quality: String
        if let regex = try? NSRegularExpression(pattern: expression),
           let match = regex.firstMatch(in: url.path, range: NSRange(url.path.startIndex..., in: url.path)),
           let range = Range(match.range(at: 1), in: url.path) {
            quality = String(url.path[range])
        } else { quality = "Auto" }
        let initial = Stream(url: url, quality: quality, label: provider)
        selected = initial
        streams = [initial]
        chooseInitial(initial)
        let choices = await SiteService.shared.variants(for: initial)
        streams = choices
    }

    private func chooseInitial(_ stream: Stream) {
        player.replaceCurrentItem(with: AVPlayerItem(url: stream.url))
        player.play()
    }
}

struct ProviderWebPlayer: UIViewRepresentable {
    let url: URL
    let onMedia: (URL) -> Void

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        let onMedia: (URL) -> Void
        var currentHost: String?
        init(onMedia: @escaping (URL) -> Void) { self.onMedia = onMedia }

        func userContentController(_ userContentController: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            guard let raw = message.body as? String,
                  let url = URL(string: raw), url.scheme == "https" else { return }
            DispatchQueue.main.async { self.onMedia(url) }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
            if navigationAction.targetFrame?.isMainFrame != true {
                decisionHandler(.allow); return
            }
            let host = (url.host ?? "").lowercased()
            let permitted = ["farsiplex.com", "www.farsiplex.com",
                             "farsiland.com", "www.farsiland.com",
                             "farsiland.buzz", "www.farsiland.buzz",
                             "filmserial.com", "www.filmserial.com",
                             "filmserial.tv", "www.filmserial.tv"].contains(host)
            decisionHandler(permitted ? .allow : .cancel)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // Only scan links and resources that the authorized page exposes.
            // Do not synthesize access tokens, skip gates or inspect cross-origin frames.
            let scan = """
            (function(){
              var items=[];
              document.querySelectorAll('video,source,a[href],iframe[src]').forEach(function(n){
                var u=n.currentSrc||n.src||n.href||'';
                if(u&&/\\.mp4(?:\\?|$)/i.test(u))items.push(u);
              });
              var text=document.documentElement.outerHTML;
              var found=text.match(/https:\\/\\/pl\\.(?:farsiplex|farsiland)\\.buzz\\/[^'"\\s<>]+?\\.mp4\\?md5=[A-Za-z0-9_-]+/g)||[];
              found.forEach(function(u){items.push(u.replace(/&amp;/g,'&'));});
              items.forEach(function(u){window.webkit.messageHandlers.media.postMessage(u)});
            })();
            """
            webView.evaluateJavaScript(scan)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onMedia: onMedia) }

    func makeUIView(context: Context) -> WKWebView {
        let controller = WKUserContentController()
        controller.add(context.coordinator, name: "media")
        let config = WKWebViewConfiguration()
        config.userContentController = controller
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = .black
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}
}

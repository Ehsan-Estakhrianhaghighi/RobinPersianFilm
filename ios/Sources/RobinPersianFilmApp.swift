import SwiftUI
import AVKit

@main
struct RobinPersianFilmApp: App {
    var body: some Scene {
        WindowGroup {
            CatalogView()
                .preferredColorScheme(.dark)
        }
    }
}

struct Film: Identifiable, Hashable {
    let id: String
    let title: String
    let poster: URL?
    let isSeries: Bool
    let provider: String
    let rank: Int
    let publishedAt: Date?
}
struct Episode: Identifiable, Hashable {
    let id: String
    let season: Int
    let number: Int
}
struct Stream: Identifiable, Hashable {
    var id: String { url.absoluteString }
    let url: URL
    let quality: String
    let label: String
}
enum FilmCategory: String, CaseIterable {
    case home = "Home", movies = "Movies", series = "Series", favorites = "Favorites", history = "History"
}
enum SortMode: String, CaseIterable {
    case newest = "Newest", alphabetical = "A–Z"
}

@MainActor
final class CatalogModel: ObservableObject {
    @Published var movies: [Film] = []
    @Published var series: [Film] = []
    @Published var loading = false
    @Published var error: String?
    private let favoritesKey = "robin.ios.favorites"
    private let historyKey = "robin.ios.history"
    @Published var favorites: Set<String> = []
    @Published var history: Set<String> = []
    init() {
        favorites = Set(UserDefaults.standard.stringArray(forKey: favoritesKey) ?? [])
        history = Set(UserDefaults.standard.stringArray(forKey: historyKey) ?? [])
    }
    func refresh() async {
        guard !loading else { return }
        loading = true; error = nil
        defer { loading = false }
        async let newMovies = SiteService.shared.catalog(series: false)
        async let newSeries = SiteService.shared.catalog(series: true)
        let (m, s) = await (newMovies, newSeries)
        movies = m
        series = s
        if m.isEmpty && s.isEmpty { error = "Unable to load either provider. Check your connection." }
    }
    func toggleFavorite(_ id: String) {
        if !favorites.insert(id).inserted { favorites.remove(id) }
        UserDefaults.standard.set(Array(favorites), forKey: favoritesKey)
    }
    func markWatched(_ id: String) {
        history.insert(id)
        UserDefaults.standard.set(Array(history), forKey: historyKey)
    }
}

struct CatalogView: View {
    @StateObject private var model = CatalogModel()
    @State private var tab = FilmCategory.home
    @State private var sorting = SortMode.newest
    @State private var search = ""
    private let accent = Color(red: 0.91, green: 0.22, blue: 0.29)

    private var results: [Film] {
        let movies = model.movies
        let series = model.series
        let list: [Film]
        switch tab {
        case .home:
            list = Array(movies.prefix(12)) + Array(series.prefix(12))
        case .movies: list = movies
        case .series: list = series
        case .favorites: list = (movies + series).filter { model.favorites.contains($0.id) }
        case .history: list = (movies + series).filter { model.history.contains($0.id) }
        }
        let filtered = list.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) }
        switch sorting {
        case .alphabetical: return filtered.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        case .newest:
            return filtered.sorted {
                if let a = $0.publishedAt, let b = $1.publishedAt, a != b { return a > b }
                if $0.publishedAt != nil && $1.publishedAt == nil { return true }
                if $1.publishedAt != nil && $0.publishedAt == nil { return false }
                return $0.rank < $1.rank
            }
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Category", selection: $tab) {
                    ForEach(FilmCategory.allCases, id: \.self) { category in
                        Text(category.rawValue).tag(category)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 8)
                .padding(.bottom, 8)

                HStack {
                    Text("\(tab.rawValue) (\(results.count))").font(.headline)
                    Spacer()
                    Picker("Sort", selection: $sorting) {
                        ForEach(SortMode.allCases, id: \.self) { order in
                            Text(order.rawValue).tag(order)
                        }
                    }
                    .tint(accent)
                    Button { Task { await model.refresh() } } label: {
                        Image(systemName: "arrow.clockwise")
                    }.accessibilityLabel("Refresh catalog")
                }
                .padding(.horizontal)

                if model.loading { ProgressView().padding() }
                if let error = model.error {
                    Text(error).font(.footnote).foregroundStyle(.yellow).padding()
                }
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 132), spacing: 12)], spacing: 16) {
                        ForEach(results) { film in
                            NavigationLink {
                                FilmDetailView(film: film, model: model)
                            } label: {
                                VStack(alignment: .leading, spacing: 5) {
                                    AsyncImage(url: film.poster) { image in
                                        image.resizable().scaledToFill()
                                    } placeholder: {
                                        Rectangle().fill(.gray.opacity(0.3))
                                            .overlay(Image(systemName: "film").font(.largeTitle))
                                    }
                                    .frame(height: 193).frame(maxWidth: .infinity)
                                    .clipped().clipShape(RoundedRectangle(cornerRadius: 8))
                                    Text(film.title).font(.subheadline).lineLimit(2)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    Text(film.provider).font(.caption2).foregroundStyle(.secondary)
                                }
                                .foregroundStyle(.white)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(12)
                }
            }
            .navigationTitle("RobinPersianFilm")
            .searchable(text: $search, prompt: "Search movies and series")
            .task { if model.movies.isEmpty && model.series.isEmpty { await model.refresh() } }
        }
        .tint(accent)
    }
}

struct FilmDetailView: View {
    let film: Film
    @ObservedObject var model: CatalogModel
    @State private var episodes: [Episode] = []
    @State private var loadingEpisodes = false
    @State private var episodeError: String?
    @State private var selectedEpisode: Episode?
    @State private var playbackURL: URL?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top, spacing: 15) {
                    AsyncImage(url: film.poster) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Rectangle().fill(.gray.opacity(0.3))
                    }
                    .frame(width: 120, height: 174).clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 10) {
                        Text(film.title).font(.title2).bold()
                        Text(film.provider).foregroundStyle(.secondary)
                        Button {
                            model.toggleFavorite(film.id)
                        } label: {
                            Label(model.favorites.contains(film.id) ? "Remove favorite" : "Add favorite",
                                  systemImage: model.favorites.contains(film.id) ? "heart.fill" : "heart")
                        }
                        .buttonStyle(.bordered)
                    }
                }
                if film.isSeries {
                    if loadingEpisodes { ProgressView("Loading episodes…") }
                    if let episodeError { Text(episodeError).foregroundStyle(.yellow) }
                    ForEach(episodes) { episode in
                        Button {
                            selectedEpisode = episode
                            model.markWatched(episode.id)
                            playbackURL = URL(string: episode.id)
                        } label: {
                            HStack {
                                Text("S\(episode.season) · Episode \(episode.number)")
                                Spacer()
                                Image(systemName: "play.circle")
                            }
                            .padding(10)
                        }
                        .buttonStyle(.bordered)
                    }
                } else {
                    Button {
                        model.markWatched(film.id)
                        playbackURL = URL(string: film.id)
                    } label: {
                        Label("Play movie", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding()
        }
        .navigationTitle(film.title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard film.isSeries else { return }
            loadingEpisodes = true
            episodes = await SiteService.shared.episodes(for: film)
            loadingEpisodes = false
            if episodes.isEmpty { episodeError = "No episodes found on provider page." }
        }
        .navigationDestination(item: $playbackURL) { page in
            PlaybackView(page: page, provider: film.provider)
        }
    }
}

extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}

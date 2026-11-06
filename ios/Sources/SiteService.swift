import Foundation
import SwiftSoup

actor SiteService {
    static let shared = SiteService()
    private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieAcceptPolicy = .always
        config.httpShouldSetCookies = true
        config.timeoutIntervalForRequest = 20
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
        ]
        return URLSession(configuration: config)
    }()

    private func hostAllowed(_ url: URL, stream: Bool = false) -> Bool {
        guard url.scheme == "https", let host = url.host?.lowercased() else { return false }
        if stream {
            return host == "pl.farsiland.buzz" || host == "pl.farsiplex.buzz"
        }
        return ["farsiplex.com", "www.farsiplex.com", "farsiland.com",
                "www.farsiland.com", "farsiland.buzz", "www.farsiland.buzz"].contains(host)
    }

    private func request(_ url: URL, fields: [String: String]? = nil, referer: URL? = nil) async throws -> String {
        guard hostAllowed(url) else { throw URLError(.unsupportedURL) }
        var req = URLRequest(url: url)
        if let referer { req.setValue(referer.absoluteString, forHTTPHeaderField: "Referer") }
        if let fields {
            req.httpMethod = "POST"
            req.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
            var parts = URLComponents()
            parts.queryItems = fields.map { URLQueryItem(name: $0.key, value: $0.value) }
            req.httpBody = (parts.percentEncodedQuery ?? "").data(using: .utf8)
        }
        let (data, response) = try await session.data(for: req)
        guard let response = response as? HTTPURLResponse, (200...299).contains(response.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return String(decoding: data, as: UTF8.self)
    }

    private func document(_ url: URL, fields: [String: String]? = nil,
                          referer: URL? = nil) async throws -> Document {
        try SwiftSoup.parse(await request(url, fields: fields, referer: referer), url.absoluteString)
    }

    // Normalizes Persian spelling and punctuation without merging different categories.
    private func identity(_ film: Film) -> String {
        let base = film.title.precomposedStringWithCompatibilityMapping.lowercased()
            .replacingOccurrences(of: "ي", with: "ی")
            .replacingOccurrences(of: "ك", with: "ک")
        let filtered = String(base.unicodeScalars.filter {
            CharacterSet.letters.contains($0) || CharacterSet.decimalDigits.contains($0)
        })
        return (film.isSeries ? "series:" : "movie:") + filtered
    }

    private func pageURL(_ element: Element, base: URL) throws -> URL? {
        let href = try element.attr("href")
        guard let url = URL(string: href, relativeTo: base)?.absoluteURL, hostAllowed(url) else { return nil }
        return url
    }

    private func posterURL(_ link: Element, base: URL) -> URL? {
        let image = try? link.select("img").first()
            ?? link.parent()?.select("img").first()
        guard let image else { return nil }
        for key in ["data-src", "data-lazy-src", "data-original", "src"] {
            if let value = try? image.attr(key), !value.isEmpty,
               let url = URL(string: value, relativeTo: base)?.absoluteURL,
               url.scheme == "https" { return url }
        }
        return nil
    }

    private func publication(_ link: Element) -> Date? {
        let context = link.parent()
        let time = try? context?.select("time[datetime], [itemprop=datePublished][content]").first()
        guard let time else { return nil }
        let raw = ((try? time.attr("datetime")) ?? "").isEmpty
            ? (try? time.attr("content")) ?? "" : (try? time.attr("datetime")) ?? ""
        if let date = ISO8601DateFormatter().date(from: raw) { return date }
        let format = DateFormatter()
        format.dateFormat = "yyyy-MM-dd"
        format.locale = Locale(identifier: "en_US_POSIX")
        return format.date(from: String(raw.prefix(10)))
    }

    private func extract(_ doc: Document, page: URL, series: Bool, provider: String) -> [Film] {
        let expected = provider == "FarsiPlex" ? (series ? "/tvshow/" : "/movie/") :
            (series ? "/tvshows/" : "/movies/")
        let invalid = Set(["movies", "movie", "series", "home", "play", "watch", "view", "more"])
        guard let links = try? doc.select("a[href]").array() else { return [] }
        var result = [Film]()
        var seen = Set<String>()
        for link in links {
            guard let url = try? pageURL(link, base: page),
                  url.path.contains(expected), url.path != expected,
                  !seen.contains(url.absoluteString) else { continue }
            let heading = (try? link.select("h2,h3,h4").first()?.text()) ?? ""
            let imageTitle = (try? link.select("img").first()?.attr("alt")) ?? ""
            let titleAttribute = (try? link.attr("title")) ?? ""
            let text = (try? link.text()) ?? ""
            let title = [heading, titleAttribute, text, imageTitle]
                .first(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !title.isEmpty, title.count <= 110,
                  !invalid.contains(title.lowercased()),
                  !invalid.contains(url.lastPathComponent.lowercased()),
                  (!heading.isEmpty || posterURL(link, base: page) != nil)
            else { continue }
            seen.insert(url.absoluteString)
            result.append(Film(id: url.absoluteString, title: title,
                               poster: posterURL(link, base: page), isSeries: series,
                               provider: provider, rank: result.count, publishedAt: publication(link)))
        }
        return result
    }

    private func collect(provider: String, series: Bool) async -> [Film] {
        let plex = provider == "FarsiPlex"
        let base = plex ? "https://farsiplex.com" : "https://farsiland.com"
        let category = plex ? (series ? "/tvshow/" : "/movie/") :
            (series ? "/series-26/" : "/iran-movie-2025/")
        var queue = plex ? [base + category] : [base + "/home", base + category]
        let limit = plex ? 12 : 5
        var visited = Set<String>()
        var found = [Film]()
        var seen = Set<String>()
        while !queue.isEmpty && visited.count < limit {
            let address = queue.removeFirst()
            guard !visited.contains(address), let url = URL(string: address) else { continue }
            visited.insert(address)
            guard let doc = try? await document(url) else { continue }
            for film in extract(doc, page: url, series: series, provider: provider) {
                if seen.insert(film.id).inserted { found.append(film) }
            }
            guard address.hasPrefix(base + category) else { continue }
            if let links = try? doc.select(".pagination a[href],.page-numbers[href],nav.pagination a[href]").array() {
                for link in links {
                    if let next = try? pageURL(link, base: url),
                       next.absoluteString.hasPrefix(base + category),
                       !visited.contains(next.absoluteString), !queue.contains(next.absoluteString) {
                        queue.append(next.absoluteString)
                    }
                }
            }
        }
        return found
    }

    func catalog(series: Bool) async -> [Film] {
        async let plex = collect(provider: "FarsiPlex", series: series)
        async let land = collect(provider: "FarsiLand", series: series)
        let (preferred, additional) = await (plex, land)
        var matched = Set<String>()
        let result = (preferred + additional).enumerated().compactMap { index, film -> Film? in
            guard matched.insert(identity(film)).inserted else { return nil }
            return Film(id: film.id, title: film.title, poster: film.poster,
                        isSeries: film.isSeries, provider: film.provider,
                        rank: index, publishedAt: film.publishedAt)
        }
        return result.sorted {
            if let a = $0.publishedAt, let b = $1.publishedAt, a != b { return a > b }
            if $0.publishedAt != nil && $1.publishedAt == nil { return true }
            if $0.publishedAt == nil && $1.publishedAt != nil { return false }
            return $0.rank < $1.rank
        }
    }

    func episodes(for film: Film) async -> [Episode] {
        guard let url = URL(string: film.id), let doc = try? await document(url) else { return [] }
        let selector = film.provider == "FarsiLand" ? "a[href*=/episodes/]" : "a[href*=/episode/]"
        guard let links = try? doc.select(selector).array() else { return [] }
        var result = [Episode]()
        var seen = Set<String>()
        for link in links {
            guard let linkURL = try? pageURL(link, base: url), !seen.contains(linkURL.absoluteString) else { continue }
            let nearby = ((try? link.text()) ?? "") + " " + ((try? link.attr("title")) ?? "")
                + " " + linkURL.lastPathComponent
            let season = regexCapture(nearby, #"(?i)(?:season|s)\s*0*([0-9]{1,2})"#).flatMap(Int.init) ?? 1
            guard let episode = regexCapture(nearby, #"(?i)(?:episode|ep|e)\s*0*([0-9]+)"#).flatMap(Int.init),
                  episode > 0 else { continue }
            seen.insert(linkURL.absoluteString)
            result.append(Episode(id: linkURL.absoluteString, season: season, number: episode))
        }
        return result.sorted { $0.season == $1.season ? $0.number < $1.number : $0.season < $1.season }
    }

    private func regexCapture(_ input: String, _ regex: String) -> String? {
        guard let pattern = try? NSRegularExpression(pattern: regex),
              let found = pattern.firstMatch(in: input, range: NSRange(input.startIndex..., in: input)),
              found.numberOfRanges > 1,
              let range = Range(found.range(at: 1), in: input) else { return nil }
        return String(input[range])
    }

    private func formValues(_ form: Element) -> [String: String] {
        guard let inputs = try? form.select("input[name]").array() else { return [:] }
        var dict = [String: String]()
        for input in inputs {
            guard let key = try? input.attr("name"), !key.isEmpty else { continue }
            dict[key] = (try? input.attr("value")) ?? ""
        }
        return dict
    }

    private func actionURL(_ form: Element, base: URL) -> URL? {
        let action = (try? form.attr("action")) ?? ""
        guard let target = URL(string: action.isEmpty ? base.absoluteString : action,
                               relativeTo: base)?.absoluteURL, hostAllowed(target) else { return nil }
        return target
    }

    func farsiLandStreams(for original: URL) async -> [Stream] {
        guard ["farsiland.com", "www.farsiland.com"].contains(original.host ?? ""),
              ["/movies/", "/episodes/"].contains(where: { original.path.hasPrefix($0) }) else { return [] }
        do {
            let entry = try await document(original)
            guard let first = try entry.select("form[action]").array().first(where: {
                ((try? $0.attr("abs:action")) ?? "").contains("farsiland.buzz/redirect.php")
            }), let destination = actionURL(first, base: original) else { return [] }
            let redirect = try await document(destination, fields: formValues(first), referer: original)
            guard let second = try redirect.select("form[action]").array().first(where: {
                guard let target = actionURL($0, base: destination) else { return false }
                return ["farsiland.buzz", "www.farsiland.buzz"].contains(target.host ?? "")
            }), let next = actionURL(second, base: destination) else { return [] }
            let qualityPage = try await document(next, fields: formValues(second), referer: destination)
            let forms = try qualityPage.select("form").array()
            var output = [Stream]()
            for quality in ["720", "1080", "480"] {
                guard let form = forms.first(where: {
                    let values = formValues($0)
                    return values["q"] == quality && !(values["postid"] ?? "").isEmpty
                }), let finalURL = actionURL(form, base: next) else { continue }
                do {
                    let final = try await document(finalURL, fields: formValues(form), referer: next)
                    let html = try final.outerHtml().replacingOccurrences(of: "&amp;", with: "&")
                    let pattern = #"https://pl\.farsiland\.buzz/[^'"\s<>]+?\.mp4\?md5=[A-Za-z0-9_-]+"#
                    guard let raw = regexCaptureFull(html, pattern), let media = URL(string: raw),
                          await verified(media) else { continue }
                    let q = regexCapture(media.path, #"[.-](480|720|1080)(?:\.[^.]+)*\.mp4$"#) ?? quality
                    if !output.contains(where: { $0.url == media }) {
                        output.append(Stream(url: media, quality: q, label: "FarsiLand"))
                    }
                } catch { continue }
            }
            return output.sorted { (Int($0.quality) ?? 0) < (Int($1.quality) ?? 0) }
        } catch { return [] }
    }

    private func regexCaptureFull(_ input: String, _ pattern: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: input, range: NSRange(input.startIndex..., in: input)),
              let range = Range(match.range, in: input) else { return nil }
        return String(input[range])
    }

    func verified(_ url: URL) async -> Bool {
        guard hostAllowed(url, stream: true), url.path.lowercased().hasSuffix(".mp4") else { return false }
        var req = URLRequest(url: url)
        req.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        req.timeoutInterval = 6
        do {
            let (_, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else { return false }
            return (200...206).contains(http.statusCode) &&
                ((http.value(forHTTPHeaderField: "Content-Type") ?? "").contains("video/") ||
                 (http.value(forHTTPHeaderField: "Content-Type") ?? "").contains("octet-stream"))
        } catch { return false }
    }

    func variants(for stream: Stream) async -> [Stream] {
        let path = stream.url.path
        let expression = #"[.-](360|480|720|1080|1440|2160)(?=(?:\.[^./]+)*\.mp4$)"#
        guard let regex = try? NSRegularExpression(pattern: expression, options: .caseInsensitive),
              let match = regex.firstMatch(in: path, range: NSRange(path.startIndex..., in: path)),
              let qualityRange = Range(match.range(at: 1), in: path) else { return [stream] }
        var candidates = [stream]
        for quality in ["480", "720", "1080"] where quality != stream.quality {
            var components = URLComponents(url: stream.url, resolvingAgainstBaseURL: false)
            components?.path = path.replacingCharacters(in: qualityRange, with: quality)
            guard let url = components?.url, await verified(url) else { continue }
            candidates.append(Stream(url: url, quality: quality, label: stream.label))
        }
        return candidates.sorted { (Int($0.quality) ?? 0) < (Int($1.quality) ?? 0) }
    }
}

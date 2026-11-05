import Foundation

/// A tiny in-memory XML tree, enough for SEC Atom feeds, Form 4s and 13F tables.
final class XNode {
    let name: String
    let attrs: [String: String]
    var children: [XNode] = []
    var text = ""

    init(name: String, attrs: [String: String]) {
        self.name = name
        self.attrs = attrs
    }

    /// Element name without a namespace prefix ("ns1:infoTable" -> "infoTable").
    var local: String { name.split(separator: ":").last.map(String.init) ?? name }

    func child(_ n: String) -> XNode? { children.first { $0.local == n } }
    func all(_ n: String) -> [XNode] { children.filter { $0.local == n } }

    /// Text at a slash path. Many Form 4 fields wrap their content in a <value> element.
    func text(_ path: String) -> String? {
        var el: XNode = self
        for part in path.split(separator: "/") {
            guard let c = el.child(String(part)) else { return nil }
            el = c
        }
        let inner = el.child("value") ?? el
        let t = inner.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    static func parse(_ data: Data) -> XNode? {
        let parser = XMLParser(data: data)
        let builder = Builder()
        parser.delegate = builder
        parser.shouldProcessNamespaces = false
        _ = parser.parse()
        return builder.root
    }

    private final class Builder: NSObject, XMLParserDelegate {
        var root: XNode?
        var stack: [XNode] = []

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
            let node = XNode(name: elementName, attrs: attributeDict)
            if let top = stack.last { top.children.append(node) } else { root = node }
            stack.append(node)
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            stack.last?.text += string
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?) {
            if !stack.isEmpty { stack.removeLast() }
        }
    }
}

# UIScope website folder

This is a static, multi-page website built from the supplied UIScope-jvm source ZIP. It uses plain HTML, CSS and a small vanilla JavaScript file. There is no Node.js, npm, build step, framework, third-party font or remote runtime dependency. The visual refinement is an additional local CSS file and supports light and dark system themes.

## Publish

Upload the **contents of this `web/` folder** to the static host's document root. The internal navigation and asset links are relative, so the site can also be deployed beneath a subpath.

## Production domain and Google verification

The site is configured for **https://uiscope.pages.dev/**. Canonical URLs, Open Graph URLs/images, Twitter images, JSON-LD site URLs, `sitemap.xml` and the `robots.txt` sitemap declaration use this domain.

The Google Search Console verification meta tag is included in every HTML page's `<head>`:

```html
<meta name="google-site-verification" content="sou-98cZYPWFJmbwxJgJptO-ZaIPNJqz2ven_iS4NjI" />
```

After deploying the contents of `web/`, open `https://uiscope.pages.dev/` and confirm the tag is present in the page source. Then complete the HTML-tag verification flow in Google Search Console and submit `https://uiscope.pages.dev/sitemap.xml`. Adding the tag does not itself verify ownership or submit the sitemap.

## SEO and answer-engine/GEO work included

- Unique title and meta description on every page.
- Canonical and Open Graph/Twitter metadata placeholders.
- Semantic headings, descriptive link text, accessible navigation and mobile layout.
- JSON-LD structured data for site identity, breadcrumbs and the visible FAQ page.
- `sitemap.xml`, `robots.txt` and `llms.txt` with factual product scope, platform boundaries, version and release caveat.
- Direct links to the authoritative repository, privacy policy, issues and troubleshooting guide.
- Answer-first feature pages and concise fact statements suitable for search snippets and generative search systems.

## Product accuracy notes

The website copy follows the supplied repository README, changelog, privacy policy, distribution notes and current source version. The code identifies version **1.0.1**, marked unreleased in the changelog/distribution documents. A matching installer for this rebuild is not advertised. UIScope is described as a Windows desktop application; Android is an ADB-connected inspection target, not an Android phone app. The documented desktop host is Windows 10 version 1809+ and Windows 11.

## Files

- `index.html` — landing page
- `features.html` — feature overview
- `windows-inspector.html` — Windows inspection
- `android-inspector.html` — Android inspection via ADB
- `selectors-and-codegen.html` — selector analysis and ten code targets
- `snapshots.html` — local snapshots and `.uiscope` bundles
- `compare.html` — capture diff workflow
- `quick-start.html` — first-run paths
- `privacy.html` — privacy and network behavior
- `faq.html` — FAQ with FAQPage JSON-LD
- `downloads.html` — release status, without a stale binary link
- `404.html` — not-found page
- `assets/` — base stylesheet, visual-refinement stylesheet, tiny navigation script, supplied app mark and social preview image

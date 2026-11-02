# UIScope website — hybrid static HTML edition

This folder is the merged website: the visual language and page coverage from `web.zip`, plus the app-linked documentation structure and troubleshooting depth from `UIScope-website.zip`.

## Stack and performance posture

- Static, multi-page HTML. Important product copy is present in the HTML response; it is not injected by JavaScript.
- Local CSS, images and two tiny first-party vanilla JavaScript files for the theme preference and menu dismissal. No framework, npm build, remote font, CDN runtime, analytics SDK or third-party script.
- The full site menu uses native HTML `<details>` / `<summary>` on every screen size; desktop also keeps four quick links visible. The menu works without JavaScript, while the small local script adds Escape/outside-click dismissal.
- Dark mode is the default regardless of operating-system preference. Visitors can explicitly switch to light mode; the choice is saved in local storage and restored across pages. If storage is unavailable, the switch still works for the current page.
- Responsive layout, semantic landmarks, skip links, visible focus styles, descriptive link text and reduced-motion support.
- Sitemap, robots.txt, canonical URLs, social metadata, basic JSON-LD and a concise llms.txt index.

## Publish to the existing domain

1. Upload the **contents** of this folder to the document root for `https://uiscope.pages.dev/`.
2. Do not upload the outer folder as an extra path level unless you intentionally want a subdirectory deployment. Canonical URLs in this build target the domain root.
3. Open the home page, `docs.html`, `robots.txt`, `sitemap.xml` and `llms.txt` in a browser to verify they return successfully.
4. Check page source for the existing Google Search Console HTML verification tag. The tag is preserved on every indexable HTML page:

   `meta name="google-site-verification" content="sou-98cZYPWFJmbwxJgJptO-ZaIPNJqz2ven_iS4NjI"`

5. In Google Search Console, complete HTML-tag verification if it has not already been completed, then submit `https://uiscope.pages.dev/sitemap.xml`.
6. Use URL Inspection on the home page and highest-value feature pages. A valid sitemap or verification tag does not guarantee indexing.
7. Add the domain to Bing Webmaster Tools and review crawl/indexing status. Where available to the account, check AI Performance reporting for cited URLs and grounding queries.

## Do not publish stale downloads

The checked-in source says version **1.0.1**, but `CHANGELOG.md` marks it as unreleased and the current repository page does not list a published matching installer. The website intentionally links to the repository and release page instead of presenting an old binary as current. Update `downloads.html`, `docs.html`, `faq.html`, `llms.txt` and the home-page release note when a real release is published.

## Product facts to keep synchronized

- UIScope is a Windows desktop application, not an Android app.
- Windows inspection uses Windows UI Automation.
- Android inspection is performed from the desktop through ADB.
- Current documented host support: Windows 10 version 1809 or later and Windows 11.
- The current repository documents ten code-generation targets; see the selector page and source README.
- Privacy claims must continue to match `PRIVACY.md` and `docs/network.md`.
- Do not add testimonials, adoption numbers, ratings, compatibility claims, or release links without evidence.

## Local preview

From this directory run:

```bash
python -m http.server 8000
```

Then open `http://localhost:8000/`.

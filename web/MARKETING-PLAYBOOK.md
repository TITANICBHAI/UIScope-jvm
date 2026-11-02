# UIScope discoverability and growth playbook

This is the operating plan for getting UIScope in front of the developers who need it. It separates work the website can do from distribution and product work that the website cannot do by itself.

## Positioning

**One-sentence description**

UIScope is a Windows desktop UI inspector that lets developers inspect Windows application windows and connected Android devices through ADB, understand element hierarchies, evaluate automation selectors, save captures and compare UI changes.

**Primary audiences**

1. Android UI automation developers who use ADB, Appium, UI Automator, uiautomator2 or Maestro.
2. Windows desktop automation and QA engineers who work with UI Automation, pywinauto, FlaUI, AutoHotkey or PowerShell.
3. Test engineers who need to inspect element trees, troubleshoot inaccessible controls, preserve captures or compare UI states.

**Differentiation to demonstrate, not merely claim**

- One desktop workspace for Windows UI Automation and connected Android ADB inspection.
- An element tree linked to a screenshot canvas and element details.
- Selector candidates with match counts, reliability guidance and warnings.
- Ten documented code-generation targets.
- Local snapshots, offline replay, portable `.uiscope` bundles and capture comparison.
- A clear privacy model with no telemetry or account system.

Do not call UIScope a cross-platform desktop app: the desktop host documented by the current source is Windows. Android is a connected target. Do not promise every app exposes a complete accessibility tree.

## Search intent map

Each page should answer one specific class of questions thoroughly. Avoid creating many near-duplicate keyword pages.

| Page | Main intent to serve | Related natural-language searches |
|---|---|---|
| Home | Windows and Android UI inspector | desktop UI inspector, inspect Windows app UI, inspect Android UI from PC |
| Windows inspector | Windows UI Automation inspection | Windows desktop element tree, inspect AutomationId, Windows app UI inspector |
| Android inspector | Android UI inspection through ADB | inspect Android view hierarchy, ADB UI inspector, Android element selector |
| Selectors & codegen | UI automation selector analysis and code generation | Appium selector generator, UI Automator selector, pywinauto locator, selector match count |
| Snapshots | Save and replay UI captures | UI inspection snapshot, offline UI capture, export .uiscope |
| Compare | Compare UI trees and snapshots | UI hierarchy diff, compare Android UI captures, added removed changed moved elements |
| Docs / Quick start | Solve setup and troubleshooting tasks | connect Android ADB, Windows UI Automation helper, selector troubleshooting |
| Privacy / FAQ | Verify fit, limits and data handling | is UIScope an Android app, supported Windows version, does UIScope upload screenshots |

Use these phrases naturally in titles, headings, image alt text and internal links. Do not repeat them unnaturally or create pages whose only purpose is to target a keyword variation.

## What actually improves search and AI-answer visibility

- Keep useful, original, technically accurate explanations in crawlable HTML. This site does that; the important text exists without JavaScript execution.
- Make every important page reachable through normal `<a href>` links. The shared navigation, footer and contextual links create discovery paths.
- Give each page a specific title, concise description, one descriptive H1, descriptive subheadings, and a canonical URL.
- Keep facts consistent across the site, GitHub README, changelog, privacy policy, releases and screenshots.
- Use structured data only to describe visible, verifiable facts. The JSON-LD here identifies the publisher, site, software and page breadcrumb; it contains no fake reviews, ratings, offers or download URLs.
- Use the social preview image for link sharing; add real product screenshots or short screen recordings when they are available and current. Label mockups as illustrative, not screenshots.
- Maintain a useful documentation page. Clear answers and exact steps are more valuable than repeated keyword blocks.
- `llms.txt` is included as an optional machine-readable index. It is not a recognized shortcut to Google rankings and does not guarantee LLM citations. Do not spend time stuffing it with duplicated keyword copy.

Google's current Search Central guidance says normal SEO fundamentals remain the foundation for AI features: crawlability, internal links, people-first content, good page experience, visible text and accurate structured data. It says there are no additional technical requirements or special AI markup required for AI Overviews / AI Mode. Google's newer generative-AI guide explicitly cautions against GEO/AI-search hacks and unnecessary AI text files. See the references at the end of this document.

## Distribution: where awareness comes from

### 1. Make the repository a reliable acquisition surface

- Keep the GitHub README's first screen focused on the problem, the product, a real screenshot/GIF, supported host/target distinction, and the website link.
- Add the website link near the beginning of the README and in the repository's About website field.
- Use accurate repository topics such as `ui-inspector`, `ui-automation`, `android`, `adb`, `appium`, `windows-automation`, `test-automation`, and `kotlin` only where they accurately describe the code.
- When a release is actually published, attach checksums, clear install instructions, a concise changelog and screenshots to the release itself. The website should link directly to that verified release.
- Keep issue templates clear so users can report Windows, ADB, selector and capture problems separately.

### 2. Publish proof-led demos

Create short, real screen recordings that show one workflow each:

- Select a Windows window, pick a control and reveal its Automation/UIA details.
- Connect an Android device through ADB, inspect the tree and select a view on the screenshot.
- Compare two captures and show the added / removed / changed / moved result categories.
- Evaluate a selector, show the match count and copy a framework snippet.

Each demo should identify the platform and the exact workflow. Link to the matching feature page, not only the homepage. Do not use a mock interface as though it were live product footage.

### 3. Participate where the problem is discussed

- Answer relevant questions in developer forums and communities with a useful technical explanation first; mention UIScope only when it genuinely solves the described problem.
- Share build notes, implementation decisions and troubleshooting lessons from the project, with links to the relevant docs.
- Do not mass-post identical promotional messages, buy low-quality backlinks, exchange keyword-stuffed guest posts or create fake accounts/reviews. Those tactics damage trust and can violate platform/search spam policies.

### 4. Use launch and directory listings deliberately

A listing is useful only when its audience overlaps with Windows desktop automation, Android automation or QA. Prepare a consistent product description, real screenshots, license, platform requirements, release status and repository link. Never claim an installer is available before it is published.

### 5. Build durable, useful supporting content

Prioritize articles that answer questions UIScope can demonstrate, for example:

- What Windows UI Automation exposes (and why some controls are missing).
- How Android ADB inspection differs from an Android app running on the phone.
- How to assess selector uniqueness and why a unique selector can still be unstable.
- How to compare captured UI hierarchies between app versions.
- Common ADB authorization and Windows helper troubleshooting.

Write these only when you can add tested steps, screenshots, code examples or lessons from the actual implementation. Link each guide to the relevant product page and source documentation.

## Measurement loop

### Google Search Console

After deployment and verification:

1. Submit `https://uiscope.pages.dev/sitemap.xml`.
2. Inspect `/`, `/windows-inspector.html`, `/android-inspector.html`, `/selectors-and-codegen.html`, `/docs.html` and `/downloads.html`.
3. Review Page indexing, duplicate/canonical reports, crawl errors and the Performance report.
4. Group queries by intent: brand, Windows inspection, Android ADB inspection, selector generation, snapshots/diffs and troubleshooting.
5. Improve pages that earn impressions but weak clicks by making title/description more specific. Improve pages with impressions but poor engagement by ensuring the page answers the search query quickly.

### Bing Webmaster Tools and AI visibility

- Add and verify the domain in Bing Webmaster Tools; importing an already verified Search Console property may be available.
- Review crawl/index coverage and sitemap status.
- Bing announced an AI Performance public preview in February 2026, with sample grounding queries and page-level citation activity for supported AI experiences. Check whether it is available to this property/account and use it to identify which pages are being referenced. Citation counts are not search rankings.

### Product funnel

A practical funnel is: impression/referral → relevant landing page → proof (real demo or accurate docs) → repository/release page → install/run → issue/feedback. Measure each step only after deciding on a privacy-respecting analytics method. This edition intentionally ships with no analytics or tracking script.

## Launch checklist

- [ ] Deploy the contents of this folder to the domain root.
- [ ] Verify the homepage, every sitemap URL, docs anchors, favicon and social image.
- [ ] Complete Search Console ownership verification and submit the sitemap.
- [ ] Add/verify Bing Webmaster Tools and submit the sitemap.
- [ ] Test with Google URL Inspection and the Rich Results Test / Schema Markup Validator as appropriate; do not expect every schema type to produce a rich result.
- [ ] Run PageSpeed Insights on mobile and desktop; address actual layout, image and Core Web Vitals findings rather than optimizing for a single lab score.
- [ ] Update the GitHub repository About website and README link.
- [ ] Record at least two real workflows as short demos; include platform context and captions.
- [ ] Publish one useful guide that answers a real developer question, then share it where relevant.
- [ ] When the installer is published, update Downloads, Quick Start, Docs, FAQ and llms.txt in the same change.

## Official references

- Google Search Essentials: https://developers.google.com/search/docs/essentials
- Google AI features and your website: https://developers.google.com/search/docs/appearance/ai-features
- Google's generative-AI optimization guide: https://developers.google.com/search/docs/fundamentals/ai-optimization-guide
- Google crawlable link guidance: https://developers.google.com/search/docs/crawling-indexing/links-crawlable
- Google Search Console: https://search.google.com/search-console/about
- Bing Webmaster Tools: https://www.bing.com/webmasters/about
- Bing AI Performance announcement (10 February 2026): https://blogs.bing.com/webmaster/2026/2/Introducing-AI-Performance-in-Bing-Webmaster-Tools-Public-Preview/
- UIScope repository: https://github.com/TITANICBHAI/UIScope-jvm

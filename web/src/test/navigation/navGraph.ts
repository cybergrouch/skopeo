import * as capabilityRules from "@/auth/capabilities";
import type { Capability } from "@/auth/capabilities";

/**
 * A click-cost model of the web app's navigation (#1094), derived from source rather than maintained
 * by hand: the route table in `App.tsx`, the dashboard's capability-gated `sections[]`, and every
 * `to=` / `navigate(…)` target in the code a page renders.
 *
 * ## What it measures, and what it cannot
 *
 * **Reachability, not behaviour.** A budget passing proves a flow *can* be done in N clicks. It cannot
 * see someone spend forty seconds hunting for the control, scroll past it, or backtrack; nor a control
 * that is on screen but not noticeable, which costs one click here and infinity in practice. It is a
 * regression guard and an argument-settler, not a substitute for watching someone use the thing.
 *
 * **Routes and dashboard sections only.** A node is a route pattern or a dashboard tab. Steps *inside*
 * a page — expanding a card, opening a form, picking a match in `EventManagerView` to enter its result —
 * are invisible, so a flow is measured to the page where it happens, never to the control within it.
 *
 * **Links are attributed by import, not by render.** A page's links are those in every file its root
 * component transitively imports, so a `<Link>` in a shared card is an edge from every page that uses
 * the card. Conditional rendering is not modelled: a link behind `canScoreLive` counts as present. The
 * persona a budget names (`as`) therefore decides which dashboard *sections* exist — evaluated with the
 * real `auth/capabilities` predicates — and nothing inside a page.
 *
 * **Data-dependent links are over-approximated.** A link to "an event in your history" is an edge to
 * `/events/:code` whether or not the viewer has any. A flow that must not ride on such links (a host
 * reaching an event they run but never played in) says so with `excluding`.
 */

/** Screen-size class, because the dashboard's menu costs differ by breakpoint (#1095). */
export type Breakpoint = "mobile" | "desktop";

/**
 * Clicks spent opening the dashboard menu before a section can be picked: the drawer below `md:`, and
 * nothing from `md:` up, where the menu is a persistent rail (#1095). This is the one hand-set input to
 * the model; DashboardPage's "at desktop width" tests are what hold the rail to it.
 */
export const NAV_DRAWER_CLICKS: Record<Breakpoint, number> = {
  mobile: 1,
  desktop: 0,
};

/** Every non-test source file, as text, keyed by `/src/…` path. */
const SOURCES: Record<string, string> = import.meta.glob<string>(
  [
    "/src/**/*.{ts,tsx}",
    "!/src/**/*.test.{ts,tsx}",
    "!/src/test/**",
    "!/src/api/generated/**",
  ],
  { query: "?raw", import: "default", eager: true },
);

const APP_FILE = "/src/App.tsx";
const DASHBOARD_FILE = "/src/routes/DashboardPage.tsx";
/** The dashboard menu. Links written in it sit behind the drawer below `md:`, like its sections do. */
const MENU_FILE = "/src/routes/dashboard/SectionNav.tsx";
/**
 * The DashboardPage flag gating the menu's own links — the "My clubs" shortcuts (#1096). The model
 * cannot see ownership, so a persona passing this gate is assumed to own a club: true of `CLUB_OWNER`
 * in the budgets, and an over-approximation for an ADMINISTRATOR, who owns none and sees no entries.
 */
const MENU_LINKS_FLAG = "showMyClubs";
const DASHBOARD_ROUTE = "/dashboard";
const LANDING_TAB = "profile";

function source(file: string): string {
  const text = SOURCES[file];
  if (text === undefined) throw new Error(`navGraph: no source for ${file}`);
  return text;
}

/** Resolves an import specifier to a `/src/…` file, or undefined for a package import. */
function resolveImport(fromFile: string, specifier: string): string | undefined {
  let base: string;
  if (specifier.startsWith("@/")) {
    base = `/src/${specifier.slice(2)}`;
  } else if (specifier.startsWith(".")) {
    const parts = fromFile.split("/").slice(0, -1);
    for (const part of specifier.split("/")) {
      if (part === "..") parts.pop();
      else if (part !== ".") parts.push(part);
    }
    base = parts.join("/");
  } else {
    return undefined;
  }
  return [base, `${base}.tsx`, `${base}.ts`, `${base}/index.tsx`, `${base}/index.ts`].find(
    (candidate) => candidate in SOURCES,
  );
}

/** A file's value imports (`import type` renders nothing, so it is skipped), resolved to files. */
function importsOf(file: string): string[] {
  const pattern =
    /\bimport\s+(type\s)?[^'"]*?from\s*["']([^"']+)["']|\bimport\(\s*["']([^"']+)["']\s*\)/g;
  const files: string[] = [];
  for (const match of source(file).matchAll(pattern)) {
    if (match[1]) continue;
    const resolved = resolveImport(file, match[2] ?? match[3]);
    if (resolved) files.push(resolved);
  }
  return files;
}

/** Maps each named import in [file] to the file it comes from. */
function namedImportsOf(file: string): Map<string, string> {
  const names = new Map<string, string>();
  const pattern = /\bimport\s*\{([^}]*)\}\s*from\s*["']([^"']+)["']/g;
  for (const match of source(file).matchAll(pattern)) {
    const resolved = resolveImport(file, match[2]);
    if (!resolved) continue;
    for (const name of match[1].split(",")) {
      const local = name.trim().split(/\s+as\s+/).pop();
      if (local) names.set(local, resolved);
    }
  }
  return names;
}

/**
 * Maps each code-split component in [file] — `const X = lazy(() => import("…"))`, or any wrapper of
 * that shape such as `lazyWithPreload` — to the file it loads.
 */
function lazyImportsOf(file: string): Map<string, string> {
  const names = new Map<string, string>();
  for (const match of source(file).matchAll(
    /const\s+(\w+)\s*=\s*\w+\(\s*\(\)\s*=>\s*import\(\s*["']([^"']+)["']\s*\)/g,
  )) {
    const resolved = resolveImport(file, match[2]);
    if (!resolved) throw new Error(`navGraph: cannot resolve lazy import ${match[2]} in ${file}`);
    names.set(match[1], resolved);
  }
  return names;
}

/** A route in the router: its path pattern and the page component file it renders. */
export interface RouteNode {
  path: string;
  file: string;
}

/**
 * The router's pages, from `App.tsx`: each `<Route path>` paired with the lazily-imported page it
 * renders. Redirect routes (`/`, `*`) render no page and are left out — a redirect is not a click.
 */
export function routes(): RouteNode[] {
  const app = source(APP_FILE);
  const lazyPages = lazyImportsOf(APP_FILE);
  const found: RouteNode[] = [];
  for (const chunk of app.split("<Route").slice(1)) {
    const path = /\bpath=["']([^"']+)["']/.exec(chunk)?.[1];
    if (!path) continue;
    const page = [...chunk.matchAll(/<(\w+)/g)]
      .map((match) => lazyPages.get(match[1]))
      .find((file) => file !== undefined);
    if (page) found.push({ path, file: page });
  }
  return found;
}

/** A dashboard section: its `?tab=` value, label, root component file and capability gate. */
export interface SectionNode {
  value: string;
  label: string;
  file: string;
  /** The `auth/capabilities` predicate gating it, or undefined for an always-shown section. */
  gate: string | undefined;
}

/**
 * The dashboard's `sections[]`, in order, read from `DashboardPage.tsx`. A section inside a
 * `...(showX ? [...] : [])` spread is gated by whatever predicate `showX` is assigned from.
 */
export function sections(): SectionNode[] {
  const page = source(DASHBOARD_FILE);
  const predicates = dashboardFlags();
  // A section component is either imported (Profile, the landing) or code-split (the rest, #1092).
  const components = new Map([
    ...namedImportsOf(DASHBOARD_FILE),
    ...lazyImportsOf(DASHBOARD_FILE),
  ]);
  const start = page.indexOf("const sections: Section[] = [");
  const end = page.indexOf("\n  ];", start);
  if (start < 0 || end < 0) throw new Error("navGraph: sections[] not found in DashboardPage");
  const block = page.slice(start, end);

  const found: SectionNode[] = [];
  for (const match of block.matchAll(/value:\s*"([^"]+)"/g)) {
    const at = match.index;
    const rest = block.slice(at);
    const label = /label:\s*"([^"]+)"/.exec(rest)?.[1];
    const component = /element:\s*\(?\s*<(\w+)/.exec(rest)?.[1];
    const file = component ? components.get(component) : undefined;
    if (!label || !file) throw new Error(`navGraph: cannot read section "${match[1]}"`);

    const before = block.slice(0, at);
    const spreadAt = before.lastIndexOf("...(show");
    const closedAt = before.lastIndexOf(": [])");
    let gate: string | undefined;
    if (spreadAt > closedAt) {
      const flag = /\.\.\.\((show\w+)/.exec(before.slice(spreadAt))?.[1] ?? "";
      gate = predicates.get(flag);
      if (!gate) throw new Error(`navGraph: section "${match[1]}" gated by unknown ${flag}`);
    }
    found.push({ value: match[1], label, file, gate });
  }
  return found;
}

/** DashboardPage's `const showX = predicate(capabilities)` flags, as flag → predicate name. */
function dashboardFlags(): Map<string, string> {
  const flags = new Map<string, string>();
  for (const match of source(DASHBOARD_FILE).matchAll(
    /const\s+(show\w+)\s*=\s*(\w+)\(\s*capabilities\s*\)/g,
  )) {
    flags.set(match[1], match[2]);
  }
  return flags;
}

/** Evaluates an `auth/capabilities` predicate, by name, for a persona. */
function passes(predicateName: string, capabilities: readonly Capability[]): boolean {
  const predicate = (capabilityRules as Record<string, unknown>)[predicateName];
  if (typeof predicate !== "function") {
    throw new Error(`navGraph: ${predicateName} is not exported from auth/capabilities`);
  }
  return Boolean((predicate as (caps: readonly Capability[]) => unknown)(capabilities));
}

/** Whether a persona holding [capabilities] is shown [section], by the dashboard's own predicate. */
export function isVisible(section: SectionNode, capabilities: readonly Capability[]): boolean {
  return !section.gate || passes(section.gate, capabilities);
}

/** Whether the menu's own links (the "My clubs" shortcuts) exist for a persona. */
function menuLinksVisible(capabilities: readonly Capability[]): boolean {
  const predicate = dashboardFlags().get(MENU_LINKS_FLAG);
  if (!predicate) throw new Error(`navGraph: DashboardPage has no ${MENU_LINKS_FLAG} flag`);
  return passes(predicate, capabilities);
}

/**
 * The files a page renders: everything [root] transitively imports, stopping at any other page or
 * section root — those are separate nodes, reached by a click, not part of this one — and at any
 * [excluded] component, which takes everything it renders with it.
 */
function subtree(
  root: string,
  roots: ReadonlySet<string>,
  excluded: ReadonlySet<string>,
): string[] {
  const seen = new Set<string>([root]);
  const stack = [root];
  while (stack.length > 0) {
    const file = stack.pop() as string;
    for (const next of importsOf(file)) {
      if (seen.has(next) || roots.has(next) || excluded.has(next)) continue;
      seen.add(next);
      stack.push(next);
    }
  }
  return [...seen];
}

/** A link written in [file]: the raw target, normalised so `${…}` becomes a `:param` segment. */
export interface LinkSite {
  file: string;
  target: string;
}

/**
 * The navigation targets written in [file]: `to=` on a link (not on `<Navigate>`, which is a redirect
 * rather than a click) and `navigate(…)` with a path. Only string and template-literal targets are
 * read; a computed one (the public pages' `origin ?? '/dashboard'` Back link) is invisible here.
 */
export function linksIn(file: string): LinkSite[] {
  const text = source(file);
  const targets: string[] = [];
  for (const match of text.matchAll(/\bto=\{?\s*(["'`])((?:(?!\1).)*)\1/g)) {
    const element = text.slice(text.lastIndexOf("<", match.index), match.index);
    if (/^<Navigate\b/.test(element)) continue;
    targets.push(match[2]);
  }
  for (const match of text.matchAll(/\bnavigate\(\s*(["'`])((?:(?!\1).)*)\1/g)) {
    targets.push(match[2]);
  }
  return targets.map((target) => ({
    file,
    target: target.replace(/\$\{[^}]*\}/g, ":param"),
  }));
}

/** Whether a concrete-or-parameterised [target] matches a route [pattern], segment by segment. */
function matchesRoute(target: string, pattern: string): boolean {
  const a = target.split("/");
  const b = pattern.split("/");
  return (
    a.length === b.length &&
    // A route parameter matches anything; a target's own `${…}` placeholder matches only a parameter,
    // so a fully computed `${to}/${code}` resolves to nothing rather than to every two-segment route.
    a.every((segment, i) => segment === b[i] || b[i].startsWith(":"))
  );
}

/** A graph node id: a route pattern, or `tab:<value>` for a dashboard section. */
export type NodeId = string;

const tabNode = (value: string): NodeId => `tab:${value}`;

/** Options for a click-cost query. */
export interface ClickQuery {
  /** The persona's capabilities: they decide which dashboard sections exist. */
  as: readonly Capability[];
  breakpoint: Breakpoint;
  /**
   * Components the flow must not pass through — with everything they render — e.g. the cards listing
   * the viewer's own history, which link to events and matches only if the viewer has any.
   */
  excluding?: readonly string[];
}

/** A target: a route pattern (`/matches/:code/score`) or a dashboard section (`{ tab: "admin" }`). */
export type ClickTarget = string | { tab: string };

/** The cheapest route found to a target: its click count and the nodes it passes through. */
export interface ClickPath {
  clicks: number;
  path: NodeId[];
}

interface Edge {
  to: NodeId;
  cost: number;
}

/** Link targets that resolve to no page, with no edge built for them — see the budget test's list. */
export function unresolvedLinks(): LinkSite[] {
  const known = routes();
  return Object.keys(SOURCES)
    .flatMap(linksIn)
    .filter(({ target }) => {
      const path = target.split("?")[0];
      return !known.some((route) => matchesRoute(path, route.path));
    });
}

function buildEdges(query: ClickQuery): Map<NodeId, Edge[]> {
  const pages = routes();
  const allSections = sections();
  const visible = allSections.filter((section) => isVisible(section, query.as));
  // The menu is a root too: its links are not the page chrome's, since they sit behind the drawer.
  const roots = new Set<string>([
    ...pages.map((p) => p.file),
    ...allSections.map((s) => s.file),
    MENU_FILE,
  ]);
  const excluded = new Set(query.excluding ?? []);
  for (const file of excluded) {
    if (!(file in SOURCES)) throw new Error(`navGraph: excluded file ${file} does not exist`);
  }

  // Where a link lands. `/dashboard?tab=x` lands on section x, or on the landing tab when this persona
  // has no such section — the same fallback DashboardPage applies to a stale or forbidden ?tab=.
  const land = (target: string): NodeId | undefined => {
    const [path, search] = target.split("?");
    if (path === DASHBOARD_ROUTE) {
      const tab = new URLSearchParams(search ?? "").get("tab");
      return tabNode(visible.some((s) => s.value === tab) ? (tab as string) : LANDING_TAB);
    }
    return pages.find((route) => matchesRoute(path, route.path))?.path;
  };

  const linkEdges = (files: string[]): Edge[] =>
    files
      .flatMap(linksIn)
      .map(({ target }) => land(target))
      .filter((to): to is NodeId => to !== undefined)
      .map((to) => ({ to, cost: 1 }));

  const edges = new Map<NodeId, Edge[]>();
  const dashboardChrome = subtree(DASHBOARD_FILE, roots, excluded);
  const menuClicks = NAV_DRAWER_CLICKS[query.breakpoint] + 1;
  const menuLinks =
    !excluded.has(MENU_FILE) && menuLinksVisible(query.as)
      ? linkEdges(subtree(MENU_FILE, roots, excluded)).map((edge) => ({ ...edge, cost: menuClicks }))
      : [];
  for (const page of pages) {
    if (page.path === DASHBOARD_ROUTE) continue;
    edges.set(page.path, linkEdges(subtree(page.file, roots, excluded)));
  }
  for (const section of visible) {
    const menu = visible
      .filter((other) => other.value !== section.value)
      .map((other) => ({ to: tabNode(other.value), cost: menuClicks }));
    edges.set(tabNode(section.value), [
      ...menu,
      ...menuLinks,
      ...linkEdges([...subtree(section.file, roots, excluded), ...dashboardChrome]),
    ]);
  }
  return edges;
}

/**
 * The fewest clicks from the post-login landing (the dashboard's Profile tab) to [target]. Throws —
 * rather than returning Infinity — when the target is unreachable, so a flow that stops being possible
 * fails loudly with its name in the message.
 */
export function clicksTo(target: ClickTarget, query: ClickQuery): ClickPath {
  const edges = buildEdges(query);
  const goal = typeof target === "string" ? target : tabNode(target.tab);
  if (!edges.has(goal)) {
    throw new Error(`navGraph: ${goal} is not a page or a section this persona has`);
  }
  const start = tabNode(LANDING_TAB);
  const best = new Map<NodeId, ClickPath>([[start, { clicks: 0, path: [start] }]]);
  const queue: NodeId[] = [start];
  while (queue.length > 0) {
    queue.sort((x, y) => (best.get(x)?.clicks ?? 0) - (best.get(y)?.clicks ?? 0));
    const node = queue.shift() as NodeId;
    const here = best.get(node) as ClickPath;
    if (node === goal) return here;
    for (const edge of edges.get(node) ?? []) {
      const clicks = here.clicks + edge.cost;
      if (clicks < (best.get(edge.to)?.clicks ?? Infinity)) {
        best.set(edge.to, { clicks, path: [...here.path, edge.to] });
        if (!queue.includes(edge.to)) queue.push(edge.to);
      }
    }
  }
  throw new Error(`navGraph: ${goal} is unreachable from ${start}`);
}

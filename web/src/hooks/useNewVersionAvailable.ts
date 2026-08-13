import { useCallback, useEffect, useRef, useState } from 'react'
import { APP_BUILD, IS_DEV_BUILD, buildId, type AppBuild } from '@/lib/appBuild'

/** How often to re-check while a tab stays open. Long: a deploy is rare and the check is a courtesy. */
const POLL_MS = 5 * 60 * 1000

/**
 * Whether a newer bundle has been deployed since this tab loaded (#752).
 *
 * A single-page app keeps running whatever it downloaded, indefinitely — a tab left open for weeks
 * still renders options the server may since have started rejecting. This polls the build identity the
 * deploy publishes at `/version.json` and reports a mismatch so the UI can offer a reload.
 *
 * Checks on mount, on an interval, and whenever the tab regains focus — the last one matters most,
 * since the realistic case is a tab reopened days later rather than one watched through a deploy.
 *
 * Deliberately quiet about failures: a missing or unparseable `version.json`, or an offline tab, is
 * never reported as "new version available". A false prompt trains people to ignore a true one.
 *
 * Inert on a dev build, where there is no deployment to be behind.
 */
export function useNewVersionAvailable(pollMs: number = POLL_MS): boolean {
  const [available, setAvailable] = useState(false)
  // Once true it stays true: the deployed build could change again while the banner is up, and
  // withdrawing the prompt because a *third* build appeared would be nonsense.
  const settled = useRef(false)

  const check = useCallback(async () => {
    if (settled.current) return
    try {
      // Cache-busted twice over: `no-store` plus a unique query, because a CDN or service worker that
      // ignores the header would otherwise serve the check itself from cache and never report a change.
      const response = await fetch(`/version.json?t=${Date.now()}`, { cache: 'no-store' })
      if (!response.ok) return
      const deployed = (await response.json()) as Partial<AppBuild>
      if (typeof deployed?.builtAt !== 'string' || deployed.builtAt === '') return
      if (buildId(deployed as AppBuild) !== buildId(APP_BUILD)) {
        settled.current = true
        setAvailable(true)
      }
    } catch {
      // Offline, blocked, or malformed — say nothing.
    }
  }, [])

  useEffect(() => {
    if (IS_DEV_BUILD) return
    // The first check is deferred a tick rather than run in the effect body: this is a subscription to
    // an external system, and firing it inline would settle state during the same commit that mounted
    // the app — a cascading render on every page load, for a result that is almost always "unchanged".
    const initial = window.setTimeout(() => void check(), 0)
    const timer = window.setInterval(() => void check(), pollMs)
    const onFocus = () => void check()
    window.addEventListener('focus', onFocus)
    return () => {
      window.clearTimeout(initial)
      window.clearInterval(timer)
      window.removeEventListener('focus', onFocus)
    }
  }, [check, pollMs])

  return available
}

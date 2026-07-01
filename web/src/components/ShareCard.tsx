import { useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'

/** Whether the browser offers the native share sheet (mobile) — the one-tap path to IG/WhatsApp/etc. */
function canWebShare(): boolean {
  return typeof navigator !== 'undefined' && typeof navigator.share === 'function'
}

/**
 * A shareable-page card (#137): a QR code encoding [url], a copy-link button, and social sharing (#192).
 * On devices with the Web Share API a single "Share" button opens the native sheet (covering Instagram
 * and others); otherwise it falls back to per-network share links (Facebook, X, WhatsApp) — all plain
 * intent URLs needing no SDK or keys. [shareText] is the post copy (falls back to [title]). Reused by
 * every shareable page (public Match/Profile/Event + the owner's Profile/Event tabs).
 */
export function ShareCard({
  url,
  title = 'Share this page',
  description = 'Scan this code, copy the link, or share it.',
  shareText,
}: {
  url: string
  title?: string
  description?: string
  shareText?: string
}) {
  const [copied, setCopied] = useState(false)
  const text = shareText ?? title

  function copyLink() {
    void navigator.clipboard.writeText(url)
    setCopied(true)
  }

  function nativeShare() {
    navigator.share({ title, text, url }).catch(() => {
      // The user dismissed the native share sheet — nothing to do.
    })
  }

  const fb = `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(url)}`
  const x = `https://twitter.com/intent/tweet?url=${encodeURIComponent(url)}&text=${encodeURIComponent(text)}`
  const whatsapp = `https://wa.me/?text=${encodeURIComponent(`${text} ${url}`)}`

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col items-center gap-3">
        <QRCodeSVG value={url} size={144} />
        <div className="flex flex-wrap items-center justify-center gap-2">
          <Button type="button" variant="outline" size="sm" onClick={copyLink}>
            {copied ? 'Copied!' : 'Copy link'}
          </Button>
          {canWebShare() ? (
            <Button type="button" size="sm" onClick={nativeShare}>
              Share
            </Button>
          ) : (
            <>
              <a
                href={fb}
                target="_blank"
                rel="noopener noreferrer"
                className="rounded-md border px-3 py-1.5 text-sm font-medium hover:bg-muted"
              >
                Facebook
              </a>
              <a
                href={x}
                target="_blank"
                rel="noopener noreferrer"
                className="rounded-md border px-3 py-1.5 text-sm font-medium hover:bg-muted"
              >
                X
              </a>
              <a
                href={whatsapp}
                target="_blank"
                rel="noopener noreferrer"
                className="rounded-md border px-3 py-1.5 text-sm font-medium hover:bg-muted"
              >
                WhatsApp
              </a>
            </>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

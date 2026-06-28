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

/**
 * A shareable-page card (#137): a QR code encoding [url] plus a copy-link button. Reused by the
 * owner's Profile tab and the public Profile / Match pages so every shareable page shares one
 * scan-or-copy affordance.
 */
export function ShareCard({
  url,
  title = 'Share this page',
  description = 'Scan this code or copy the link to share it.',
}: {
  url: string
  title?: string
  description?: string
}) {
  const [copied, setCopied] = useState(false)

  function copyLink() {
    void navigator.clipboard.writeText(url)
    setCopied(true)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col items-center gap-3">
        <QRCodeSVG value={url} size={144} />
        <Button type="button" variant="outline" size="sm" onClick={copyLink}>
          {copied ? 'Copied!' : 'Copy link'}
        </Button>
      </CardContent>
    </Card>
  )
}

import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'

/** Placeholder panel for tabs delivered in later PRs (Matches, Admin). */
export function ComingSoon({ title }: { title: string }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>Coming soon.</CardDescription>
      </CardHeader>
    </Card>
  )
}

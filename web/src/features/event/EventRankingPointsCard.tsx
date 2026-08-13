import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

/**
 * Whether finalizing this event awards ranking points (#559). Set once at creation, so this is a
 * statement of fact rather than a control — it exists so an organizer can check the answer without
 * reopening the create form.
 */
export function EventRankingPointsCard({ awards }: { awards?: boolean }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Ranking points</CardTitle>
        <CardDescription>
          Whether finalizing this event awards ranking points per the global
          schedules.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <p className="text-sm" data-testid="award-ranking-points-summary">
          {awards
            ? "This event awards ranking points on finalize."
            : "This event awards no ranking points."}
        </p>
      </CardContent>
    </Card>
  );
}

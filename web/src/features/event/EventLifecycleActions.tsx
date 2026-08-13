import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

/**
 * The four ways an event's life ends or is rewound: finalize (#403), un-finalize (#477), reverse
 * ratings (#478), and delete (#243).
 *
 * They are one component because they are one decision — which of them is even offered depends on the
 * same two facts (is the event finalized, is the viewer an administrator), and they share the
 * confirm-then-act shape that keeps a destructive click from being a single click. Grouping them puts
 * that mutual exclusion in one place instead of four sibling conditionals in the page.
 *
 * Each action is confirmed here and performed by the caller, which owns the mutations and reports
 * failure its own way (a toast). The confirmation closes once the caller's promise settles, whichever
 * way it went: a refusal has already been explained, and leaving a primed destructive button behind
 * invites a second, uninformed click.
 *
 * Reverse ratings is administrator-only and styled as a caution because, unlike un-finalize, it
 * rewrites rating history rather than just reopening the event — [canReverseRatings] is passed in
 * rather than derived here, so this component states the rule and the caller owns the capability.
 */
export function EventLifecycleActions({
  finalized,
  canReverseRatings,
  onFinalize,
  finalizing = false,
  onUnfinalize,
  unfinalizing = false,
  onReverseRatings,
  reversing = false,
  onDelete,
  deleting = false,
}: {
  finalized: boolean;
  canReverseRatings: boolean;
  onFinalize: () => Promise<void>;
  finalizing?: boolean;
  onUnfinalize: () => Promise<void>;
  unfinalizing?: boolean;
  onReverseRatings: () => Promise<void>;
  reversing?: boolean;
  onDelete: () => Promise<void>;
  deleting?: boolean;
}) {
  const [confirmingFinalize, setConfirmingFinalize] = useState(false);
  const [confirmingUnfinalize, setConfirmingUnfinalize] = useState(false);
  const [confirmingReverse, setConfirmingReverse] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  async function confirmFinalize() {
    await onFinalize();
    setConfirmingFinalize(false);
  }

  async function confirmUnfinalize() {
    await onUnfinalize();
    setConfirmingUnfinalize(false);
  }

  async function confirmReverse() {
    await onReverseRatings();
    setConfirmingReverse(false);
  }

  async function confirmDelete() {
    await onDelete();
    setConfirmingDelete(false);
  }

  return (
    <>
      {finalized ? null : (
        <Card>
          <CardHeader>
            <CardTitle>Finalize event</CardTitle>
            <CardDescription>
              Finalizing closes this event to further changes and queues its
              matches for rating. This cannot be undone.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            {confirmingFinalize ? (
              <div className="flex gap-2">
                <Button
                  type="button"
                  size="sm"
                  disabled={finalizing}
                  onClick={confirmFinalize}
                >
                  {finalizing ? "Finalizing…" : "Confirm finalize"}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={finalizing}
                  onClick={() => setConfirmingFinalize(false)}
                >
                  Cancel
                </Button>
              </div>
            ) : (
              <Button
                type="button"
                size="sm"
                onClick={() => setConfirmingFinalize(true)}
              >
                Finalize event
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {finalized ? (
        <Card>
          <CardHeader>
            <CardTitle>Un-finalize event</CardTitle>
            <CardDescription>
              Un-finalizing reopens this event so an erroneous score can be
              corrected. It revokes the ranking points this event awarded on
              finalize. This is refused if any of its matches have already been
              rated.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            {confirmingUnfinalize ? (
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="text-destructive hover:text-destructive"
                  disabled={unfinalizing}
                  onClick={confirmUnfinalize}
                >
                  {unfinalizing
                    ? "Un-finalizing…"
                    : "Confirm un-finalize (revokes points)"}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={unfinalizing}
                  onClick={() => setConfirmingUnfinalize(false)}
                >
                  Cancel
                </Button>
              </div>
            ) : (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setConfirmingUnfinalize(true)}
              >
                Un-finalize event
              </Button>
            )}
          </CardContent>
        </Card>
      ) : null}

      {finalized && canReverseRatings ? (
        <Card className="border-destructive/50">
          <CardHeader>
            <CardTitle className="text-destructive">Reverse ratings</CardTitle>
            <CardDescription>
              This is a destructive correction for an event whose matches have
              already been rated. It restores every participant to their
              pre-event rating, reverses this event’s rating history, and
              revokes the ranking points it awarded, then reopens the event so
              the score can be corrected and re-finalized. It is refused unless
              this event is at the tip of the rated timeline — no later match
              may have been rated on top of it.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            {confirmingReverse ? (
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="border-destructive text-destructive hover:bg-destructive hover:text-destructive-foreground"
                  disabled={reversing}
                  onClick={confirmReverse}
                >
                  {reversing
                    ? "Reversing ratings…"
                    : "Confirm reverse (rewinds ratings, revokes points)"}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={reversing}
                  onClick={() => setConfirmingReverse(false)}
                >
                  Cancel
                </Button>
              </div>
            ) : (
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="border-destructive text-destructive hover:bg-destructive hover:text-destructive-foreground"
                onClick={() => setConfirmingReverse(true)}
              >
                Reverse ratings
              </Button>
            )}
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle>Delete event</CardTitle>
          <CardDescription>
            An event can be deleted only while it has no recorded matches.
            Delete recorded matches first; an event with rated matches can’t be
            deleted.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          {confirmingDelete ? (
            <div className="flex gap-2">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="text-destructive hover:text-destructive"
                disabled={deleting}
                onClick={confirmDelete}
              >
                {deleting ? "Deleting…" : "Confirm delete"}
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={deleting}
                onClick={() => setConfirmingDelete(false)}
              >
                Cancel
              </Button>
            </div>
          ) : (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="text-destructive hover:text-destructive"
              onClick={() => setConfirmingDelete(true)}
            >
              Delete event
            </Button>
          )}
        </CardContent>
      </Card>
    </>
  );
}

import type { ReactNode } from "react";
import { NtrpLabel } from "@/components/NtrpLabel";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { PageContainer } from "@/components/PageContainer";

interface AuthLayoutProps {
  title: string;
  description: string;
  children: ReactNode;
  footer?: ReactNode;
}

/**
 * Centered card shell shared by the sign-up and login screens. Stays narrow while the rest of the app
 * goes fluid (#742): a login box that spans a desktop window reads as a mistake, so `form` is the one
 * page size that doesn't grow.
 */
export function AuthLayout({
  title,
  description,
  children,
  footer,
}: AuthLayoutProps) {
  return (
    <div className="flex min-h-svh items-center justify-center bg-muted/40 py-4">
      <PageContainer size="form">
        <Card>
          <CardHeader className="text-center">
            <div className="mb-2 flex flex-col items-center gap-2">
              <img
                src="/logo-mark.svg"
                alt="Skopeo"
                className="h-12 w-12"
                width={48}
                height={48}
              />
              <span className="text-xl font-semibold tracking-tight">
                Skopeo
              </span>
              <p className="text-sm text-muted-foreground">
                Skopeo calculates performance-based <NtrpLabel /> tennis ratings from
                your match results.
              </p>
            </div>
            <CardTitle className="text-2xl">{title}</CardTitle>
            <CardDescription>{description}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">{children}</CardContent>
          {footer ? (
            <div className="px-6 pb-6 text-center text-sm text-muted-foreground">
              {footer}
            </div>
          ) : null}
        </Card>
      </PageContainer>
    </div>
  );
}

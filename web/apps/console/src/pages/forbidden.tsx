import { Link } from 'react-router-dom';

import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@store-app/ui';

export function ForbiddenPage() {
  return (
    <div className="flex min-h-dvh items-center justify-center p-4">
      <Card className="w-full max-w-sm text-center">
        <CardHeader>
          <CardTitle>You do not have access to this area</CardTitle>
          <CardDescription>
            Your account is signed in, but its role does not permit this page. Admins use the
            admin panel; shop owners use the shop dashboard.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Button asChild>
            <Link to="/">Back to the console</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

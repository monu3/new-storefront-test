import { Link } from 'react-router-dom';

import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@store-app/ui';

export function NotFound() {
  return (
    <div className="flex min-h-dvh items-center justify-center p-4">
      <Card className="w-full max-w-sm text-center">
        <CardHeader>
          <CardTitle>Page not found</CardTitle>
          <CardDescription>That console route does not exist.</CardDescription>
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
